"""Authenticated PCM bridge between Triplex and a Chime SDK browser attendee.

The Amazon Chime SDK exposes real-time meeting media through its client SDKs.
This service gives the Python call processor a bounded WebSocket producer while
the bundled browser client joins the meeting and supplies that PCM stream as a
custom audio input ``MediaStream``.
"""

from __future__ import annotations

import asyncio
import json
import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

STATIC_DIR = Path(__file__).with_name("meeting_gateway_static")


class MeetingCredentials(BaseModel):
    """CreateMeeting/GetMeeting and CreateAttendee response payloads."""

    meeting: dict[str, Any]
    attendee: dict[str, Any]
    call_id: str = Field(min_length=1, max_length=256)


class SessionRegistration(BaseModel):
    session_id: str
    producer_path: str
    join_path: str
    producer_token: str
    browser_token: str


@dataclass
class _BridgeSession:
    session_id: str
    credentials: MeetingCredentials
    producer_token: str
    browser_token: str
    browser: WebSocket | None = None
    producer: WebSocket | None = None
    browser_ready: asyncio.Event = field(default_factory=asyncio.Event)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class MeetingSessionRegistry:
    """Concurrency-safe registry for active, ephemeral meeting bridges."""

    def __init__(self) -> None:
        self._sessions: dict[str, _BridgeSession] = {}
        self._lock = asyncio.Lock()

    async def create(self, credentials: MeetingCredentials) -> _BridgeSession:
        session = _BridgeSession(
            session_id=secrets.token_urlsafe(18),
            credentials=credentials,
            producer_token=secrets.token_urlsafe(32),
            browser_token=secrets.token_urlsafe(32),
        )
        async with self._lock:
            self._sessions[session.session_id] = session
        return session

    async def get(self, session_id: str) -> _BridgeSession | None:
        async with self._lock:
            return self._sessions.get(session_id)

    async def delete(self, session_id: str) -> None:
        async with self._lock:
            self._sessions.pop(session_id, None)


def create_app(
    registry: MeetingSessionRegistry | None = None,
    *,
    admin_token: str | None = None,
) -> FastAPI:
    """Build the gateway ASGI application."""
    sessions = registry or MeetingSessionRegistry()
    configured_admin_token = admin_token or os.environ.get("MEETING_GATEWAY_ADMIN_TOKEN")
    if not configured_admin_token:
        raise RuntimeError("MEETING_GATEWAY_ADMIN_TOKEN is required")

    app = FastAPI(title="Triplex Chime audio gateway", docs_url=None, redoc_url=None)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/sessions", response_model=SessionRegistration)
    async def register_session(
        credentials: MeetingCredentials,
        authorization: str | None = Header(default=None),
    ) -> SessionRegistration:
        _require_bearer(authorization, configured_admin_token)
        session = await sessions.create(credentials)
        return SessionRegistration(
            session_id=session.session_id,
            producer_path=f"/ws/producer/{session.session_id}",
            join_path=f"/sessions/{session.session_id}/join",
            producer_token=session.producer_token,
            browser_token=session.browser_token,
        )

    @app.delete("/sessions/{session_id}", status_code=204)
    async def delete_session(
        session_id: str,
        authorization: str | None = Header(default=None),
    ) -> None:
        _require_bearer(authorization, configured_admin_token)
        await sessions.delete(session_id)

    @app.get("/sessions/{session_id}/credentials")
    async def get_credentials(
        session_id: str,
        authorization: str | None = Header(default=None),
    ) -> MeetingCredentials:
        session = await _get_session_or_404(sessions, session_id)
        _require_bearer(authorization, session.browser_token)
        return session.credentials

    @app.get("/sessions/{session_id}/join")
    async def join_page(session_id: str) -> FileResponse:
        await _get_session_or_404(sessions, session_id)
        return FileResponse(STATIC_DIR / "index.html")

    @app.get("/static/meeting-bridge.js")
    async def meeting_bridge_script() -> FileResponse:
        return FileResponse(STATIC_DIR / "meeting-bridge.js")

    @app.get("/static/pcm-worklet.js")
    async def pcm_worklet_script() -> FileResponse:
        return FileResponse(STATIC_DIR / "pcm-worklet.js")

    @app.websocket("/ws/producer/{session_id}")
    async def producer_socket(websocket: WebSocket, session_id: str) -> None:
        session = await sessions.get(session_id)
        if session is None or not _websocket_bearer_matches(
            websocket, session.producer_token
        ):
            await websocket.close(code=4401)
            return
        try:
            await asyncio.wait_for(session.browser_ready.wait(), timeout=30.0)
        except TimeoutError:
            await websocket.close(code=4408)
            return
        await websocket.accept(subprotocol="triplex")
        async with session.lock:
            if session.producer is not None:
                await websocket.close(code=4409)
                return
            session.producer = websocket

        try:
            await asyncio.wait_for(session.browser_ready.wait(), timeout=30.0)
            while True:
                message = await websocket.receive()
                if message.get("type") == "websocket.disconnect":
                    break
                browser = session.browser
                if browser is None:
                    raise WebSocketDisconnect(code=1011)
                if message.get("bytes") is not None:
                    await browser.send_bytes(message["bytes"])
                elif message.get("text") is not None:
                    await browser.send_text(message["text"])
        except (TimeoutError, WebSocketDisconnect):
            pass
        finally:
            async with session.lock:
                if session.producer is websocket:
                    session.producer = None

    @app.websocket("/ws/browser/{session_id}")
    async def browser_socket(websocket: WebSocket, session_id: str) -> None:
        session = await sessions.get(session_id)
        if session is None or not _websocket_bearer_matches(
            websocket, session.browser_token
        ):
            await websocket.close(code=4401)
            return
        await websocket.accept(subprotocol="triplex")
        async with session.lock:
            if session.browser is not None:
                await websocket.close(code=4409)
                return
            session.browser = websocket

        try:
            while True:
                message = await websocket.receive()
                if message.get("type") == "websocket.disconnect":
                    break
                producer = session.producer
                text_message = message.get("text")
                if text_message is not None:
                    try:
                        payload = json.loads(text_message)
                    except json.JSONDecodeError:
                        payload = None
                    if isinstance(payload, dict) and payload.get("type") == "ready":
                        session.browser_ready.set()
                        continue
                if producer is None:
                    continue
                if message.get("bytes") is not None:
                    await producer.send_bytes(message["bytes"])
                elif text_message is not None:
                    await producer.send_text(text_message)
        except WebSocketDisconnect:
            pass
        finally:
            async with session.lock:
                if session.browser is websocket:
                    session.browser = None
                    session.browser_ready.clear()

    return app


async def _get_session_or_404(
    registry: MeetingSessionRegistry, session_id: str
) -> _BridgeSession:
    session = await registry.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="meeting session not found")
    return session


def _require_bearer(authorization: str | None, expected: str) -> None:
    prefix = "Bearer "
    if (
        authorization is None
        or not authorization.startswith(prefix)
        or not secrets.compare_digest(authorization[len(prefix) :], expected)
    ):
        raise HTTPException(status_code=401, detail="invalid bearer token")


def _websocket_bearer_matches(websocket: WebSocket, expected: str) -> bool:
    protocols = [
        value.strip()
        for value in websocket.headers.get("sec-websocket-protocol", "").split(",")
    ]
    return (
        len(protocols) == 2
        and protocols[0] == "triplex"
        and secrets.compare_digest(protocols[1], expected)
    )


def app_from_environment() -> FastAPI:
    """Uvicorn factory entry point."""
    return create_app()
