"""Twilio Voice server for handling calls.

Receives calls via TwiML webhooks and streams audio via WebSocket.

Flow:
1. Twilio calls our /voice endpoint (TwiML webhook)
2. We return TwiML that connects to our WebSocket
3. Audio flows bidirectionally over WebSocket
4. We process audio through the pipeline and respond
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import numpy as np

try:
    from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
    from fastapi.responses import Response
    import uvicorn

    HAS_FASTAPI = True
except ImportError:
    HAS_FASTAPI = False


@dataclass
class TwilioConfig:
    """Configuration for Twilio server."""

    host: str = "0.0.0.0"
    port: int = 8080
    twiml_path: str = "/voice"
    websocket_path: str = "/media"
    sample_rate: int = 8000  # Twilio default is 8kHz mu-law
    # For better quality, use 24kHz via <Stream> with custom encoder


class TwilioMediaStream:
    """Handles Twilio media stream over WebSocket.

    Twilio sends audio as base64-encoded mu-law (8kHz) by default.
    For 24kHz Opus, need to configure <Stream> with track="inbound_track".
    """

    def __init__(
        self,
        on_audio: Callable[[bytes, float], None] | None = None,
        sample_rate: int = 8000,
    ):
        self.on_audio = on_audio
        self.sample_rate = sample_rate
        self._buffer: list[bytes] = []
        self._start_time: float | None = None
        self._is_connected = False

    async def handle_connection(self, websocket: Any) -> None:
        """Handle incoming WebSocket connection from Twilio."""
        self._is_connected = True
        self._start_time = time.time()

        try:
            async for message in websocket:
                data = json.loads(message)
                event = data.get("event")

                if event == "connected":
                    print("[Twilio] Stream connected")

                elif event == "start":
                    print(f"[Twilio] Stream started: {data.get('streamSid')}")

                elif event == "media":
                    # Audio payload is base64-encoded
                    import base64

                    payload = data.get("media", {}).get("payload", "")
                    audio_bytes = base64.b64decode(payload)

                    # Track timing
                    timestamp = time.time() - (self._start_time or time.time())

                    # Call the audio handler
                    if self.on_audio:
                        self.on_audio(audio_bytes, timestamp)

                elif event == "stop":
                    print("[Twilio] Stream stopped")

        except WebSocketDisconnect:
            print("[Twilio] Client disconnected")
        finally:
            self._is_connected = False

    def is_connected(self) -> bool:
        return self._is_connected


def create_twiml_response(websocket_url: str) -> str:
    """Generate TwiML to connect call to our media stream.

    For 24kHz Opus audio, use the <Stream> verb with proper encoding.
    """
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Say>Please hold while I connect you.</Say>
    <Connect>
        <Stream url="{websocket_url}" />
    </Connect>
</Response>"""


def create_twiml_24khz(websocket_url: str) -> str:
    """Generate TwiML for 24kHz audio (higher quality)."""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Say>Please hold while I connect you.</Say>
    <Connect>
        <Stream url="{websocket_url}" track="inbound_track" />
    </Connect>
</Response>"""


def create_app(config: TwilioConfig | None = None) -> "FastAPI":
    """Create FastAPI app for Twilio webhooks."""
    if not HAS_FASTAPI:
        raise ImportError("fastapi and uvicorn required: pip install fastapi uvicorn")

    config = config or TwilioConfig()
    app = FastAPI(title="Triplex Voice Server")

    # Store active connections
    active_streams: dict[str, TwilioMediaStream] = {}

    @app.post("/voice")
    async def voice_webhook(request: Request) -> Response:
        """Handle incoming call from Twilio.

        Returns TwiML that connects the call to our WebSocket media stream.
        """
        # In production, construct proper WebSocket URL
        # For local dev, use ngrok or similar tunnel
        ws_url = f"wss://your-server.com{config.websocket_path}"

        twiml = create_twiml_response(ws_url)
        return Response(content=twiml, media_type="application/xml")

    @app.websocket(config.websocket_path)
    async def media_stream(websocket: WebSocket) -> None:
        """Handle WebSocket media stream from Twilio."""
        await websocket.accept()

        stream = TwilioMediaStream(
            sample_rate=config.sample_rate,
            on_audio=lambda audio, ts: handle_incoming_audio(
                websocket, audio, ts, active_streams
            ),
        )

        stream_id = id(stream)
        active_streams[stream_id] = stream

        try:
            await stream.handle_connection(websocket)
        finally:
            active_streams.pop(stream_id, None)

    return app


def handle_incoming_audio(
    websocket: Any,
    audio_bytes: bytes,
    timestamp: float,
    active_streams: dict,
) -> None:
    """Process incoming audio from Twilio.

    This is where we:
    1. Decode mu-law to PCM
    2. Run through AEC (subtract our outgoing audio)
    3. Run through VAD
    4. Run through pipeline
    5. Send response back
    """
    # TODO: Wire up the full pipeline
    # For now, just log that we received audio
    pass
    # asyncio.create_task(send_audio_response(websocket, response_audio))


async def send_audio_response(websocket: Any, audio: bytes) -> None:
    """Send audio back to Twilio.

    Audio must be base64-encoded mu-law (8kHz) or configured format.
    """
    import base64

    payload = base64.b64encode(audio).decode()

    message = {
        "event": "media",
        "streamSid": "stream_id_here",  # Track this from 'start' event
        "media": {"payload": payload},
    }

    await websocket.send_json(message)


def run_server(config: TwilioConfig | None = None) -> None:
    """Run the Twilio server."""
    if not HAS_FASTAPI:
        raise ImportError("fastapi and uvicorn required")

    config = config or TwilioConfig()
    app = create_app(config)

    print(f"Starting Triplex server on {config.host}:{config.port}")
    print(f"  TwiML webhook: http://{config.host}:{config.port}{config.twiml_path}")
    print(f"  WebSocket: ws://{config.host}:{config.port}{config.websocket_path}")

    uvicorn.run(app, host=config.host, port=config.port)


if __name__ == "__main__":
    run_server()
