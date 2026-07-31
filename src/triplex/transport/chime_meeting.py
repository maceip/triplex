"""Audio output to Chime Meeting.

Sends synthesized audio back to the meeting for the caller to hear.

Uses Chime SDK Data Messages or direct audio injection (if available).
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import AsyncIterator

try:
    import boto3

    HAS_BOTO3 = True
except ImportError:
    HAS_BOTO3 = False


@dataclass
class MeetingConfig:
    """Configuration for meeting audio output."""

    meeting_id: str
    attendee_id: str
    region: str = "us-east-1"
    sample_rate: int = 16000


class MeetingAudioOutput:
    """Sends audio to Chime meeting.

    Options for audio injection:
    1. Chime SDK Data Messages (send audio as data)
    2. WebRTC audio track (if using native SDK)
    3. SIP Media App PlayAudio action (for PSTN leg)

    For PSTN calls, we use the SIP Media App to bridge back.
    """

    def __init__(self, config: MeetingConfig):
        if not HAS_BOTO3:
            raise ImportError("boto3 required: pip install boto3")

        self.config = config
        self._chime_client = None

    def initialize(self) -> None:
        """Initialize Chime client."""
        self._chime_client = boto3.client("chime", region_name=self.config.region)

    async def send_audio_stream(self, audio_stream: AsyncIterator[bytes]) -> None:
        """Send audio chunks to the meeting.

        This is a simplification - real implementation depends on:
        - Chime SDK client library (WebRTC)
        - Or signaling via SIP Media App

        Args:
            audio_stream: PCM audio chunks (16-bit, 16kHz)
        """
        if not self._chime_client:
            self.initialize()

        # For PSTN calls, we need to:
        # 1. Generate audio file to S3, OR
        # 2. Use real-time audio injection if available

        # Collect audio into buffer
        audio_buffer = bytearray()
        async for chunk in audio_stream:
            audio_buffer.extend(chunk)

        # For now, this is a placeholder
        # Real implementation needs Chime SDK meeting client
        # to inject audio in real-time

    async def send_to_s3_and_play(self, audio: bytes, bucket: str, call_id: str) -> dict:
        """Upload audio to S3 and return PlayAudio action.

        This is the SIP Media App approach - slower but works with PSTN.

        Args:
            audio: Complete audio file (WAV format)
            bucket: S3 bucket name
            call_id: Call ID for the SIP Media App

        Returns:
            Action dict for SIP Media App response
        """
        import io
        import wave

        s3 = boto3.client("s3")

        # Create WAV file
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, "wb") as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2)
            wav_file.setframerate(self.config.sample_rate)
            wav_file.writeframes(audio)

        wav_buffer.seek(0)

        # Upload to S3
        key = f"audio/{call_id}/{int(time.time() * 1000)}.wav"
        s3.upload_fileobj(wav_buffer, bucket, key)

        # Return PlayAudio action
        s3_uri = f"s3://{bucket}/{key}"
        return {
            "Type": "PlayAudio",
            "Parameters": {
                "AudioSource": {
                    "Type": "S3",
                    "S3Uri": s3_uri,
                },
                "CallId": call_id,
                "ParticipantTag": "LEG-A",  # Usually the caller
            },
        }


class MockMeetingAudioOutput:
    """Mock meeting output for testing."""

    def __init__(self, config: MeetingConfig | None = None):
        self.config = config or MeetingConfig(meeting_id="mock", attendee_id="mock")

    def initialize(self) -> None:
        pass

    async def send_audio_stream(self, audio_stream: AsyncIterator[bytes]) -> None:
        """Consume audio stream (no-op)."""
        async for chunk in audio_stream:
            pass  # Discard

    async def send_to_s3_and_play(self, audio: bytes, bucket: str, call_id: str) -> dict:
        """Return mock action."""
        return {
            "Type": "PlayAudio",
            "Parameters": {
                "AudioSource": {"Type": "S3", "S3Uri": "s3://mock/mock.wav"},
                "CallId": call_id,
            },
        }


def get_meeting_output(
    config: MeetingConfig, use_mock: bool = False
) -> MeetingAudioOutput | MockMeetingAudioOutput:
    """Factory function to get meeting audio output."""
    if use_mock or not HAS_BOTO3:
        return MockMeetingAudioOutput(config)
    return MeetingAudioOutput(config)
