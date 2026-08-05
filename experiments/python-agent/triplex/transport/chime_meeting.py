"""Real-time response-audio egress to an Amazon Chime SDK meeting."""

from __future__ import annotations

import asyncio
import json
import os
import shutil

# Browser launch uses a resolved executable, explicit arguments, and no shell.
import subprocess  # nosec B404
import uuid
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from typing import Any, cast
from urllib.parse import urljoin, urlparse, urlunparse

import httpx
import websockets


@dataclass(frozen=True)
class MeetingConfig:
    """Meeting credentials and gateway settings for one output attendee."""

    meeting: dict[str, Any]
    attendee: dict[str, Any]
    call_id: str
    gateway_base_url: str = "http://127.0.0.1:8765"
    gateway_admin_token: str = ""
    sample_rate: int = 16000
    launch_browser: bool = True
    browser_executable: str | None = None
    browser_start_timeout_seconds: float = 30.0

    def __post_init__(self) -> None:
        if not self.gateway_admin_token:
            raise ValueError("gateway_admin_token is required")
        if self.sample_rate <= 0:
            raise ValueError("sample_rate must be positive")


class MeetingAudioOutput:
    """Send PCM to a Chime meeting through the authenticated browser bridge."""

    def __init__(self, config: MeetingConfig) -> None:
        self.config = config
        self._session: dict[str, Any] | None = None
        self._socket: Any | None = None
        self._receiver_task: asyncio.Task[None] | None = None
        self._browser_process: subprocess.Popen[bytes] | None = None
        self._send_lock = asyncio.Lock()
        self._played_samples: dict[str, int] = {}
        self._flush_waiters: dict[str, asyncio.Future[int]] = {}
        self._sequence = 0
        self._played_callback: Callable[[str, int], None] | None = None
        self._flushed_generations: set[str] = set()

    @property
    def join_url(self) -> str | None:
        if self._session is None:
            return None
        join_path = str(self._session["join_path"])
        token = str(self._session["browser_token"])
        return f"{urljoin(self.config.gateway_base_url, join_path)}#{token}"

    def played_samples(self, generation_id: str) -> int:
        """Return the sample count acknowledged by the AudioWorklet."""
        return self._played_samples.get(generation_id, 0)

    def set_played_callback(
        self, callback: Callable[[str, int], None] | None
    ) -> None:
        """Observe cumulative AudioWorklet playout acknowledgements."""
        self._played_callback = callback

    async def initialize(self) -> None:
        """Register the attendee, launch its browser, and open producer media."""
        if self._socket is not None:
            return

        try:
            async with httpx.AsyncClient(
                base_url=self.config.gateway_base_url,
                timeout=self.config.browser_start_timeout_seconds,
            ) as client:
                response = await client.post(
                    "/sessions",
                    headers={
                        "Authorization": f"Bearer {self.config.gateway_admin_token}"
                    },
                    json={
                        "meeting": self.config.meeting,
                        "attendee": self.config.attendee,
                        "call_id": self.config.call_id,
                    },
                )
                response.raise_for_status()
                self._session = response.json()

            if self.config.launch_browser:
                self._browser_process = _launch_browser(
                    self.join_url or "", self.config.browser_executable
                )

            producer_url = _websocket_url(
                self.config.gateway_base_url, str(self._session["producer_path"])
            )
            self._socket = await websockets.connect(
                producer_url,
                subprotocols=cast(
                    Any, ["triplex", str(self._session["producer_token"])]
                ),
                open_timeout=self.config.browser_start_timeout_seconds,
                max_size=2**20,
            )
            self._receiver_task = asyncio.create_task(
                self._receive_acknowledgements(),
                name=f"chime-acks-{self.config.call_id}",
            )
        except Exception:
            await self.close()
            raise

    async def send_chunk(
        self,
        audio: bytes,
        *,
        generation_id: str,
        sequence: int | None = None,
    ) -> int | None:
        """Queue one PCM16 chunk and return its sequence number."""
        if len(audio) % 2:
            raise ValueError("meeting output requires signed 16-bit PCM")
        if self._socket is None:
            await self.initialize()
        socket = self._socket
        if socket is None:
            raise RuntimeError("meeting output WebSocket is not initialized")

        if sequence is None:
            sequence = self._sequence
            self._sequence += 1
        metadata = {
            "type": "audio",
            "generation_id": generation_id,
            "sequence": sequence,
            "sample_count": len(audio) // 2,
            "sample_rate": self.config.sample_rate,
        }
        async with self._send_lock:
            if generation_id in self._flushed_generations:
                return None
            await socket.send(json.dumps(metadata, separators=(",", ":")))
            await socket.send(audio)
        return sequence

    async def send_audio_stream(
        self,
        audio_stream: AsyncIterator[bytes],
        *,
        generation_id: str | None = None,
    ) -> str:
        """Send an iterator of PCM chunks as one interruptible generation."""
        generation_id = generation_id or uuid.uuid4().hex
        async for chunk in audio_stream:
            await self.send_chunk(chunk, generation_id=generation_id)
        return generation_id

    async def flush(self, generation_id: str, timeout_seconds: float = 0.25) -> int:
        """Flush queued audio and return the actual AudioWorklet playout count."""
        self._flushed_generations.add(generation_id)
        if self._socket is None:
            return self.played_samples(generation_id)
        loop = asyncio.get_running_loop()
        waiter = loop.create_future()
        self._flush_waiters[generation_id] = waiter
        async with self._send_lock:
            await self._socket.send(
                json.dumps(
                    {"type": "flush", "generation_id": generation_id},
                    separators=(",", ":"),
                )
            )
        try:
            return await asyncio.wait_for(waiter, timeout=timeout_seconds)
        finally:
            self._flush_waiters.pop(generation_id, None)

    async def close(self) -> None:
        """Leave the meeting bridge and release the browser process."""
        socket = self._socket
        self._socket = None
        if socket is not None:
            try:
                await socket.send('{"type":"close"}')
            finally:
                await socket.close()

        if self._receiver_task is not None:
            self._receiver_task.cancel()
            await asyncio.gather(self._receiver_task, return_exceptions=True)
            self._receiver_task = None

        if self._session is not None:
            session_id = str(self._session["session_id"])
            async with httpx.AsyncClient(
                base_url=self.config.gateway_base_url, timeout=5.0
            ) as client:
                response = await client.delete(
                    f"/sessions/{session_id}",
                    headers={
                        "Authorization": f"Bearer {self.config.gateway_admin_token}"
                    },
                )
                if response.status_code not in {204, 404}:
                    response.raise_for_status()
            self._session = None

        if self._browser_process is not None:
            self._browser_process.terminate()
            try:
                await asyncio.to_thread(self._browser_process.wait, 5.0)
            except subprocess.TimeoutExpired:
                self._browser_process.kill()
                await asyncio.to_thread(self._browser_process.wait)
            self._browser_process = None

    async def _receive_acknowledgements(self) -> None:
        socket = self._socket
        if socket is None:
            raise RuntimeError("meeting output WebSocket is not initialized")
        async for raw_message in socket:
            if not isinstance(raw_message, str):
                continue
            message = json.loads(raw_message)
            message_type = message.get("type")
            generation_id = str(message.get("generation_id", ""))
            if message_type == "played":
                played_samples = int(message["played_samples"])
                self._played_samples[generation_id] = played_samples
                if self._played_callback is not None:
                    self._played_callback(generation_id, played_samples)
            elif message_type == "flushed":
                played_samples = int(message["played_samples"])
                self._played_samples[generation_id] = played_samples
                if self._played_callback is not None:
                    self._played_callback(generation_id, played_samples)
                waiter = self._flush_waiters.get(generation_id)
                if waiter is not None and not waiter.done():
                    waiter.set_result(played_samples)


class RecordingMeetingAudioOutput:
    """Explicit in-memory output used only by transport tests."""

    def __init__(self, sample_rate: int = 16000) -> None:
        self.sample_rate = sample_rate
        self.chunks: list[tuple[str, bytes]] = []
        self.flushed: set[str] = set()
        self._played_callback: Callable[[str, int], None] | None = None

    def set_played_callback(
        self, callback: Callable[[str, int], None] | None
    ) -> None:
        self._played_callback = callback

    async def initialize(self) -> None:
        return None

    async def send_chunk(
        self, audio: bytes, *, generation_id: str, sequence: int | None = None
    ) -> int | None:
        if generation_id in self.flushed:
            return None
        self.chunks.append((generation_id, audio))
        if self._played_callback is not None:
            played = sum(
                len(item) // 2
                for item_generation, item in self.chunks
                if item_generation == generation_id
            )
            self._played_callback(generation_id, played)
        return len(self.chunks) - 1 if sequence is None else sequence

    async def flush(self, generation_id: str, timeout_seconds: float = 0.25) -> int:
        del timeout_seconds
        self.flushed.add(generation_id)
        return sum(
            len(audio) // 2
            for chunk_generation, audio in self.chunks
            if chunk_generation == generation_id
        )

    async def close(self) -> None:
        return None


def _websocket_url(base_url: str, path: str) -> str:
    parsed = urlparse(urljoin(base_url, path))
    scheme = "wss" if parsed.scheme == "https" else "ws"
    return urlunparse(parsed._replace(scheme=scheme))


def _launch_browser(url: str, configured_executable: str | None) -> subprocess.Popen[bytes]:
    executable = configured_executable or os.environ.get("CHROME_EXECUTABLE")
    if executable is None:
        for candidate in (
            "chromium",
            "chromium-browser",
            "google-chrome",
            "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        ):
            resolved = shutil.which(candidate) if not candidate.startswith("/") else candidate
            if resolved and os.path.exists(resolved):
                executable = resolved
                break
    if executable is None:
        raise RuntimeError(
            "No Chromium executable found; set CHROME_EXECUTABLE for Chime egress"
        )
    arguments = [
        executable,
        "--headless=new",
        "--disable-gpu",
        "--autoplay-policy=no-user-gesture-required",
        "--use-fake-ui-for-media-stream",
    ]
    if os.environ.get("CHROME_NO_SANDBOX", "false").lower() == "true":
        arguments.append("--no-sandbox")
    arguments.append(url)
    # The explicit argv is passed directly with shell=False.
    return subprocess.Popen(  # nosec B603
        arguments,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
