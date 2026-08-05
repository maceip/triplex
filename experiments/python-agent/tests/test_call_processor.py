"""Call-scoped Chime processor orchestration tests."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from triplex.pipeline import LatencyMetrics, PipelineOutput
from triplex.synthesis.dual_router import VoiceMode
from triplex.transport.call_processor import (
    CallCapacityExceededError,
    CallMediaEvent,
    CallProcessor,
    ProcessorConfig,
    _config_from_environment,
)
from triplex.transport.chime_meeting import RecordingMeetingAudioOutput


class FakeMeetings:
    def get_meeting(self, **_kwargs: object) -> dict[str, object]:
        return {"Meeting": {"MeetingId": "meeting-1"}}

    def create_attendee(self, **_kwargs: object) -> dict[str, object]:
        return {"Attendee": {"AttendeeId": "agent-1", "JoinToken": "secret"}}


class FakeConsumer:
    instances: list[FakeConsumer] = []

    def __init__(self, config: Any) -> None:
        self.config = config
        self.closed = False
        self.instances.append(self)

    async def read_stream(self) -> AsyncIterator[bytes]:
        yield b"\x00\x00" * 320

    def close(self) -> None:
        self.closed = True


class FakeOutput:
    instances: list[FakeOutput] = []

    def __init__(self, config: Any) -> None:
        self.config = config
        self.sent: list[tuple[str, bytes]] = []
        self.closed = False
        self.callback = None
        self.instances.append(self)

    def set_played_callback(self, callback: Any) -> None:
        self.callback = callback

    async def initialize(self) -> None:
        return None

    async def flush(self, _generation_id: str) -> int:
        return 0

    async def send_chunk(
        self, audio: bytes, *, generation_id: str, sequence: int
    ) -> int:
        self.sent.append((generation_id, audio))
        return sequence

    async def close(self) -> None:
        self.closed = True


class FakePipeline:
    def __init__(self) -> None:
        self.flush = None
        self.acknowledged: list[tuple[str, int]] = []
        self.echo: list[bytes] = []

    def set_playout_flush(self, callback: Any) -> None:
        self.flush = callback

    def acknowledge_played(self, generation_id: str, samples: int) -> None:
        self.acknowledged.append((generation_id, samples))

    def push_echo_reference(self, audio: bytes) -> None:
        self.echo.append(audio)

    async def process_audio_stream(self, stream: AsyncIterator[bytes]):
        async for _ in stream:
            yield PipelineOutput(
                audio=b"\x01\x00" * 320,
                metrics=LatencyMetrics(total_ms=21.0),
                generation_id="generation-1",
                sequence=0,
                text="hello",
                sample_rate=16000,
            )


def test_parse_start_event_filters_non_caller_streams() -> None:
    envelope = {
        "detail-type": "Chime Media Pipeline State Change",
        "detail": {
            "eventType": "chime:MediaPipelineKinesisVideoStreamStart",
            "meetingId": "meeting-1",
            "streamArn": "arn:aws:kinesisvideo:stream/caller",
            "streamType": "IndividualAudio",
            "externalUserId": "caller-call-7",
        },
    }
    event = CallMediaEvent.from_eventbridge(envelope)

    assert event is not None
    assert event.call_id == "call-7"
    envelope["detail"]["externalUserId"] = "triplex-agent-call-7"
    assert CallMediaEvent.from_eventbridge(envelope) is None
    envelope["detail"]["externalUserId"] = ""
    assert CallMediaEvent.from_eventbridge(envelope) is None


async def test_process_call_connects_ingress_egress_ack_and_echo() -> None:
    pipeline = FakePipeline()
    processor = CallProcessor(
        ProcessorConfig(meeting_gateway_admin_token="admin"),
        sqs_client=SimpleNamespace(),
        meetings_client=FakeMeetings(),
        pipeline_factory=lambda: pipeline,
        consumer_factory=FakeConsumer,
        output_factory=FakeOutput,
    )
    event = CallMediaEvent(
        meeting_id="meeting-1",
        stream_arn="arn:aws:kinesisvideo:stream/caller",
        external_user_id="caller-call-7",
        status="started",
    )

    await processor.process_call(event)

    output = FakeOutput.instances[-1]
    consumer = FakeConsumer.instances[-1]
    assert output.sent == [("generation-1", b"\x01\x00" * 320)]
    assert pipeline.echo == [b"\x01\x00" * 320]
    assert output.callback == pipeline.acknowledge_played
    assert output.closed and consumer.closed


async def test_capacity_exhaustion_leaves_start_event_for_sqs_retry() -> None:
    processor = CallProcessor(
        ProcessorConfig(meeting_gateway_admin_token="admin", max_concurrent_calls=1),
        sqs_client=SimpleNamespace(),
        meetings_client=FakeMeetings(),
        pipeline_factory=FakePipeline,
    )
    blocker = asyncio.create_task(asyncio.sleep(10))
    processor._active_calls["other:caller"] = blocker
    event = CallMediaEvent("meeting-1", "stream-arn", "caller-call-7", "started")

    with pytest.raises(CallCapacityExceededError):
        await processor.handle_media_event(event)
    blocker.cancel()
    await asyncio.gather(blocker, return_exceptions=True)


async def test_transport_never_sends_a_generation_after_its_flush() -> None:
    output = RecordingMeetingAudioOutput()
    await output.flush("generation-1")

    result = await output.send_chunk(b"\x01\x00", generation_id="generation-1")

    assert result is None
    assert output.chunks == []


def test_clone_mode_requires_and_loads_auditable_runtime_profile(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with pytest.raises(ValueError, match="consent-validated profile"):
        ProcessorConfig(
            meeting_gateway_admin_token="admin", voice_mode=VoiceMode.CLONED
        )

    values = {
        "MEETING_GATEWAY_ADMIN_TOKEN": "admin",
        "VOICE_MODE": "cloned",
        "VOICE_CLONE_PROFILE_ID": "customer-voice",
        "VOICE_CLONE_SPEAKER_ID": "speaker-42",
        "VOICE_CLONE_REFERENCE_AUDIO": "/voices/customer.wav",
        "VOICE_CLONE_REFERENCE_TEXT": "Authorized reference text.",
        "VOICE_CLONE_CONSENT_ID": "consent-7",
        "VOICE_CLONE_CONSENT_VERIFIED_AT": datetime.now(UTC).isoformat(),
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)

    config = _config_from_environment()

    assert config.voice_mode is VoiceMode.CLONED
    assert config.voice_clone_profile is not None
    assert config.voice_clone_profile.reference_audio == Path(
        "/voices/customer.wav"
    )
