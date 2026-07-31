"""Multi-call Chime/KVS worker with call-scoped state and meeting egress."""

from __future__ import annotations

import asyncio
import json
import logging
import os
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from functools import partial
from pathlib import Path
from typing import Any

import boto3

from ..asr.whisper_engine import ASRConfig, MockASR, WhisperASR
from ..pipeline import PipelineConfig, VoicePipeline
from ..reasoning.vllm_engine import MockReasoningEngine, VLLMEngine
from ..synthesis.dual_router import DualVoiceRouter, VoiceMode
from ..synthesis.kokoro_engine import KokoroEngine
from ..synthesis.qwen3_tts_engine import Qwen3TTSEngine, VoiceCloneProfile
from .chime_meeting import MeetingAudioOutput, MeetingConfig
from .kvs_consumer import KVSConfig, KVSConsumer

LOGGER = logging.getLogger(__name__)


class CallCapacityExceededError(RuntimeError):
    """Raised so SQS retries a start event after capacity becomes available."""


@dataclass(frozen=True)
class ProcessorConfig:
    """Runtime contract for the long-lived call processor."""

    aws_region: str = "us-east-1"
    events_queue_url: str = ""
    meeting_gateway_url: str = "http://127.0.0.1:8765"
    meeting_gateway_admin_token: str = ""
    sample_rate: int = 16000
    chunk_duration_ms: int = 20
    max_concurrent_calls: int = 10
    echo_delay_ms: int = 0
    use_mock_llm: bool = False
    use_mock_asr: bool = False
    voice_mode: VoiceMode = VoiceMode.BRANDED
    voice_clone_profile: VoiceCloneProfile | None = None
    sqs_wait_seconds: int = 20

    def __post_init__(self) -> None:
        if not self.meeting_gateway_admin_token:
            raise ValueError("meeting_gateway_admin_token is required")
        if self.max_concurrent_calls <= 0:
            raise ValueError("max_concurrent_calls must be positive")
        if self.echo_delay_ms < 0:
            raise ValueError("echo_delay_ms cannot be negative")
        if self.voice_mode is VoiceMode.CLONED and self.voice_clone_profile is None:
            raise ValueError("cloned voice mode requires a consent-validated profile")


@dataclass(frozen=True)
class CallMediaEvent:
    """Normalized Chime media-stream lifecycle event."""

    meeting_id: str
    stream_arn: str
    external_user_id: str
    status: str
    media_pipeline_id: str = ""

    @property
    def call_id(self) -> str:
        if self.external_user_id.startswith("caller-"):
            return self.external_user_id.removeprefix("caller-")
        return self.external_user_id or self.meeting_id

    @property
    def key(self) -> str:
        return f"{self.meeting_id}:{self.external_user_id}"

    @classmethod
    def from_eventbridge(cls, envelope: dict[str, Any]) -> CallMediaEvent | None:
        """Parse the documented Chime EventBridge fields defensively."""
        detail = envelope.get("detail")
        if not isinstance(detail, dict):
            raise ValueError("EventBridge envelope does not contain an object detail")
        event_type = str(
            detail.get("eventType") or envelope.get("detail-type") or ""
        ).lower()
        stream_arn = str(
            detail.get("kinesisVideoStreamArn") or detail.get("streamArn") or ""
        )
        meeting_id = str(
            detail.get("chimeMeetingId") or detail.get("meetingId") or ""
        )
        external_user_id = str(detail.get("externalUserId") or "")
        stream_type = str(detail.get("streamType") or "").lower()
        streaming_status = str(detail.get("streamingStatus") or "").lower()

        is_start = "start" in event_type or streaming_status in {
            "started",
            "streaming",
            "active",
        }
        is_end = "end" in event_type or "stop" in event_type or streaming_status in {
            "ended",
            "stopped",
            "failed",
            "inactive",
        }
        if not is_start and not is_end:
            return None
        if stream_type and "audio" not in stream_type:
            return None
        if not external_user_id.startswith("caller-"):
            return None
        if not meeting_id:
            raise ValueError("Chime media event is missing meetingId")
        if is_start and not stream_arn:
            raise ValueError("Chime media start event is missing streamArn")
        return cls(
            meeting_id=meeting_id,
            stream_arn=stream_arn,
            external_user_id=external_user_id,
            status="started" if is_start else "ended",
            media_pipeline_id=str(detail.get("mediaPipelineId") or ""),
        )


class CallProcessor:
    """Admit Chime stream events and run one isolated pipeline per caller."""

    def __init__(
        self,
        config: ProcessorConfig,
        *,
        sqs_client: Any | None = None,
        meetings_client: Any | None = None,
        pipeline_factory: Callable[[], VoicePipeline] | None = None,
        consumer_factory: Callable[[KVSConfig], Any] = KVSConsumer,
        output_factory: Callable[[MeetingConfig], Any] = MeetingAudioOutput,
    ) -> None:
        self.config = config
        self._sqs = sqs_client
        self._meetings = meetings_client
        self._pipeline_factory = pipeline_factory
        self._consumer_factory = consumer_factory
        self._output_factory = output_factory
        self._active_calls: dict[str, asyncio.Task[None]] = {}
        self._slots = asyncio.Semaphore(config.max_concurrent_calls)
        self._initialized = False
        self._shared_reasoning: Any | None = None
        self._shared_asr: Any | None = None
        self._shared_tts: Any | None = None
        self._shared_clone_tts: Any | None = None

    def initialize(self) -> None:
        """Load shared model weights and AWS clients before taking traffic."""
        if self._initialized:
            return
        if self._sqs is None:
            self._sqs = boto3.client("sqs", region_name=self.config.aws_region)
        if self._meetings is None:
            self._meetings = boto3.client(
                "chime-sdk-meetings", region_name=self.config.aws_region
            )
        if self._pipeline_factory is None:
            self._shared_reasoning = (
                MockReasoningEngine()
                if self.config.use_mock_llm
                else VLLMEngine()
            )
            self._shared_asr = (
                MockASR()
                if self.config.use_mock_asr
                else WhisperASR(ASRConfig(model_size="small.en"))
            )
            self._shared_tts = KokoroEngine()
            if self.config.voice_mode is VoiceMode.CLONED:
                profile = self.config.voice_clone_profile
                if profile is None:
                    raise RuntimeError("cloned voice profile is not configured")
                self._shared_clone_tts = Qwen3TTSEngine()
                self._shared_clone_tts.initialize()
                self._shared_clone_tts.register_voice(profile)
            if not self.config.use_mock_llm:
                initialize_reasoning = getattr(
                    self._shared_reasoning, "initialize", None
                )
                if callable(initialize_reasoning):
                    initialize_reasoning()
            if not self.config.use_mock_asr:
                initialize_asr = getattr(self._shared_asr, "initialize", None)
                if callable(initialize_asr):
                    initialize_asr()
            self._shared_tts.initialize()
        self._initialized = True

    def _new_pipeline(self) -> VoicePipeline:
        if self._pipeline_factory is not None:
            return self._pipeline_factory()
        shared_reasoning = self._shared_reasoning
        if shared_reasoning is None:
            raise RuntimeError("call processor reasoning engine is not initialized")
        branded_tts = self._shared_tts
        if branded_tts is None:
            raise RuntimeError("call processor branded TTS engine is not initialized")
        reasoning = shared_reasoning.new_session()
        tts = DualVoiceRouter(branded_tts, self._shared_clone_tts)
        if self.config.voice_mode is VoiceMode.CLONED:
            profile = self.config.voice_clone_profile
            if profile is None:
                raise RuntimeError("cloned voice profile is not configured")
            tts.use_cloned_voice(profile.profile_id)
        return VoicePipeline(
            PipelineConfig(
                sample_rate=self.config.sample_rate,
                output_sample_rate=self.config.sample_rate,
                chunk_duration_ms=self.config.chunk_duration_ms,
                echo_delay_ms=self.config.echo_delay_ms,
                use_mock_llm=self.config.use_mock_llm,
                use_mock_asr=self.config.use_mock_asr,
            ),
            asr=self._shared_asr,
            llm=reasoning,
            tts=tts,
        )

    async def run_forever(self) -> None:
        """Long-poll SQS and admit start/stop events idempotently."""
        self.initialize()
        if not self.config.events_queue_url:
            raise RuntimeError("CALL_EVENTS_QUEUE_URL is required")
        sqs = self._sqs
        if sqs is None:
            raise RuntimeError("call processor SQS client is not initialized")
        LOGGER.info("Call processor is polling Chime media events")
        while True:
            response = await asyncio.to_thread(
                sqs.receive_message,
                QueueUrl=self.config.events_queue_url,
                MaxNumberOfMessages=10,
                WaitTimeSeconds=self.config.sqs_wait_seconds,
            )
            for message in response.get("Messages", []):
                try:
                    envelope = json.loads(message["Body"])
                    event = CallMediaEvent.from_eventbridge(envelope)
                    if event is not None:
                        await self.handle_media_event(event)
                except CallCapacityExceededError:
                    LOGGER.warning("Call capacity is full; leaving event for SQS retry")
                    continue
                except Exception:
                    LOGGER.exception("Rejected malformed Chime media event")
                    continue
                await asyncio.to_thread(
                    sqs.delete_message,
                    QueueUrl=self.config.events_queue_url,
                    ReceiptHandle=message["ReceiptHandle"],
                )

    async def handle_media_event(self, event: CallMediaEvent) -> None:
        """Start or stop exactly one call pipeline for a lifecycle event."""
        if event.status == "ended":
            task = self._active_calls.get(event.key)
            if task is not None:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            return
        if event.key in self._active_calls:
            return
        if len(self._active_calls) >= self.config.max_concurrent_calls:
            raise CallCapacityExceededError(
                f"maximum concurrent calls reached ({self.config.max_concurrent_calls})"
            )
        admitted: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        task = asyncio.create_task(
            self.process_call(event, admitted=admitted),
            name=f"triplex-call-{event.call_id}",
        )
        self._active_calls[event.key] = task
        task.add_done_callback(partial(self._call_done, event.key))
        await admitted

    def _call_done(self, key: str, task: asyncio.Task[None]) -> None:
        if self._active_calls.get(key) is task:
            self._active_calls.pop(key, None)
        if not task.cancelled() and (exception := task.exception()) is not None:
            LOGGER.error(
                "Call processor task failed",
                exc_info=(type(exception), exception, exception.__traceback__),
            )

    async def process_call(
        self,
        event: CallMediaEvent,
        *,
        admitted: asyncio.Future[None] | None = None,
    ) -> None:
        """Connect KVS ingress to pipeline and acknowledged Chime egress."""
        self.initialize()
        meetings = self._meetings
        if meetings is None:
            raise RuntimeError("call processor Chime client is not initialized")
        try:
            async with self._slots:
                meeting = await asyncio.to_thread(
                    meetings.get_meeting, MeetingId=event.meeting_id
                )
                attendee = await asyncio.to_thread(
                    meetings.create_attendee,
                    MeetingId=event.meeting_id,
                    ExternalUserId=f"triplex-agent-{event.call_id}"[:64],
                )
                output = self._output_factory(
                    MeetingConfig(
                        meeting=meeting["Meeting"],
                        attendee=attendee["Attendee"],
                        call_id=event.call_id,
                        gateway_base_url=self.config.meeting_gateway_url,
                        gateway_admin_token=self.config.meeting_gateway_admin_token,
                        sample_rate=self.config.sample_rate,
                    )
                )
                consumer = self._consumer_factory(
                    KVSConfig(
                        stream_arn=event.stream_arn,
                        region=self.config.aws_region,
                        chunk_duration_ms=self.config.chunk_duration_ms,
                        sample_rate=self.config.sample_rate,
                    )
                )
                pipeline = self._new_pipeline()
                pipeline.set_playout_flush(output.flush)
                output.set_played_callback(pipeline.acknowledge_played)
                try:
                    await output.initialize()
                    if admitted is not None and not admitted.done():
                        admitted.set_result(None)
                    async for response in pipeline.process_audio_stream(
                        consumer.read_stream()
                    ):
                        sent_sequence = await output.send_chunk(
                            response.audio,
                            generation_id=response.generation_id,
                            sequence=response.sequence,
                        )
                        if sent_sequence is None:
                            continue
                        pipeline.push_echo_reference(response.audio)
                        LOGGER.info(
                            "call=%s generation=%s sequence=%d latency_ms=%.1f",
                            event.call_id,
                            response.generation_id,
                            response.sequence,
                            response.metrics.total_ms,
                        )
                finally:
                    consumer.close()
                    await output.close()
        except BaseException as exc:
            if admitted is not None and not admitted.done():
                admitted.set_exception(exc)
            raise


def _config_from_environment() -> ProcessorConfig:
    token = os.environ.get("MEETING_GATEWAY_ADMIN_TOKEN", "")
    voice_mode = VoiceMode(os.environ.get("VOICE_MODE", VoiceMode.BRANDED.value))
    return ProcessorConfig(
        aws_region=os.environ.get("AWS_REGION", "us-east-1"),
        events_queue_url=os.environ.get("CALL_EVENTS_QUEUE_URL", ""),
        meeting_gateway_url=os.environ.get(
            "MEETING_GATEWAY_URL", "http://127.0.0.1:8765"
        ),
        meeting_gateway_admin_token=token,
        max_concurrent_calls=int(os.environ.get("MAX_CONCURRENT_CALLS", "10")),
        echo_delay_ms=int(os.environ.get("ECHO_DELAY_MS", "0")),
        use_mock_llm=os.environ.get("USE_MOCK_LLM", "false").lower() == "true",
        use_mock_asr=os.environ.get("USE_MOCK_ASR", "false").lower() == "true",
        voice_mode=voice_mode,
        voice_clone_profile=(
            _voice_clone_profile_from_environment()
            if voice_mode is VoiceMode.CLONED
            else None
        ),
    )


def _voice_clone_profile_from_environment() -> VoiceCloneProfile:
    names = {
        "profile_id": "VOICE_CLONE_PROFILE_ID",
        "speaker_id": "VOICE_CLONE_SPEAKER_ID",
        "reference_audio": "VOICE_CLONE_REFERENCE_AUDIO",
        "reference_text": "VOICE_CLONE_REFERENCE_TEXT",
        "consent_id": "VOICE_CLONE_CONSENT_ID",
        "consent_verified_at": "VOICE_CLONE_CONSENT_VERIFIED_AT",
    }
    values = {name: os.environ.get(variable, "") for name, variable in names.items()}
    missing = [names[name] for name, value in values.items() if not value]
    if missing:
        raise ValueError(
            "cloned voice mode requires environment variables: "
            + ", ".join(sorted(missing))
        )
    timestamp = values["consent_verified_at"].replace("Z", "+00:00")
    try:
        verified_at = datetime.fromisoformat(timestamp)
    except ValueError as exc:
        raise ValueError(
            "VOICE_CLONE_CONSENT_VERIFIED_AT must be an ISO-8601 timestamp"
        ) from exc
    return VoiceCloneProfile(
        profile_id=values["profile_id"],
        speaker_id=values["speaker_id"],
        reference_audio=Path(values["reference_audio"]),
        reference_text=values["reference_text"],
        consent_id=values["consent_id"],
        consent_verified_at=verified_at,
        language=os.environ.get("VOICE_CLONE_LANGUAGE", "English"),
    )


async def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))
    await CallProcessor(_config_from_environment()).run_forever()


if __name__ == "__main__":
    asyncio.run(main())
