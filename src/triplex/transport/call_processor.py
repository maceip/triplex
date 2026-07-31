"""Real-time audio processor for Chime SDK calls.

Reads from Kinesis Video Streams, runs through VAD/LLM/TTS pipeline,
and sends audio back to the meeting.

This runs as a separate service (EC2/ECS/Fargate) - NOT Lambda,
due to Lambda's time limits and streaming requirements.

Deployment:
    # Docker container that runs continuously
    docker build -t triplex-processor .
    docker run -e KVS_STREAM_NAME=triplex-audio-stream triplex-processor
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from dataclasses import dataclass
from typing import AsyncIterator

import boto3

# Pipeline components
from ..asr.whisper_engine import WhisperASR, MockASR
from ..audio.vad import VoiceActivityDetector, VoiceState
from ..pipeline import PipelineConfig, VoicePipeline, LatencyMetrics
from ..reasoning.vllm_engine import VLLMEngine, MockReasoningEngine
from ..synthesis.kokoro_engine import KokoroEngine
from .kvs_consumer import KVSConsumer, KVSConfig
from .chime_meeting import MeetingAudioOutput, MeetingConfig


@dataclass
class ProcessorConfig:
    """Configuration for the call processor."""

    # KVS stream
    kvs_stream_name: str = "triplex-audio-stream"
    aws_region: str = "us-east-1"

    # Audio settings
    sample_rate: int = 16000
    chunk_duration_ms: int = 20

    # Pipeline settings
    use_mock_llm: bool = False
    use_mock_asr: bool = False

    # Meeting output
    audio_bucket: str = "triplex-audio-output"

    # Performance
    max_concurrent_calls: int = 10


class CallProcessor:
    """Processes audio from KVS through the voice pipeline.

    Architecture:
    ```
    KVS → [This Processor] → Meeting Audio
           │
           ├─ VAD (Silero)
           ├─ ASR (Whisper)
           ├─ LLM (vLLM/Llama 3)
           └─ TTS (Kokoro-82M)
    ```

    Can handle multiple concurrent calls (one KVS stream per call).
    """

    def __init__(self, config: ProcessorConfig | None = None):
        self.config = config or ProcessorConfig()
        self._active_calls: dict[str, asyncio.Task] = {}
        self._pipeline: VoicePipeline | None = None

    def initialize(self) -> None:
        """Initialize all components."""
        # Initialize pipeline once (shared across calls)
        pipeline_config = PipelineConfig(
            use_mock_llm=self.config.use_mock_llm,
            use_mock_asr=self.config.use_mock_asr,
        )
        self._pipeline = VoicePipeline(pipeline_config)
        self._pipeline.initialize()

        print("Call processor initialized")
        print(f"  - LLM: {'Mock' if self.config.use_mock_llm else 'vLLM/Llama 3'}")
        print(f"  - ASR: {'Mock' if self.config.use_mock_asr else 'Whisper'}")
        print(f"  - TTS: Kokoro-82M")

    async def run_forever(self) -> None:
        """Main loop - process calls as they arrive.

        In production, this would:
        1. Listen for new call events (via SQS or direct API)
        2. Start a processing task for each call
        3. Manage concurrent call limit

        For now, processes a single stream continuously.
        """
        if not self._pipeline:
            self.initialize()

        print(f"Starting call processor, listening to KVS: {self.config.kvs_stream_name}")

        kvs_config = KVSConfig(
            stream_name=self.config.kvs_stream_name,
            region=self.config.aws_region,
        )
        consumer = KVSConsumer(kvs_config)

        # Process the stream
        await self._process_stream(consumer)

    async def _process_stream(self, consumer: KVSConsumer) -> None:
        """Process a single KVS stream through the pipeline."""
        print("Waiting for audio...")

        call_start_time = time.time()
        total_latency_samples = []
        response_count = 0

        try:
            async for audio_chunk, metrics in self._pipeline.process_audio_stream(
                consumer.read_stream()
            ):
                response_count += 1
                total_latency_samples.append(metrics.total_ms)

                # In production: send audio_chunk back to meeting
                # For now, just log
                print(
                    f"Response {response_count}: "
                    f"{len(audio_chunk)} bytes, "
                    f"latency: {metrics.total_ms:.1f}ms"
                )

        except asyncio.CancelledError:
            print("Processing cancelled")
        except Exception as e:
            print(f"Processing error: {e}")
            raise

        finally:
            if total_latency_samples:
                avg_latency = sum(total_latency_samples) / len(total_latency_samples)
                print(f"\nCall summary:")
                print(f"  Duration: {time.time() - call_start_time:.1f}s")
                print(f"  Responses: {response_count}")
                print(f"  Avg latency: {avg_latency:.1f}ms")

    async def process_call(self, call_id: str, meeting_id: str, kvs_stream: str) -> None:
        """Process a specific call.

        Args:
            call_id: Unique call identifier
            meeting_id: Chime meeting ID
            kvs_stream: KVS stream name for this call
        """
        print(f"Processing call {call_id}")

        # Create KVS consumer for this call
        kvs_config = KVSConfig(stream_name=kvs_stream, region=self.config.aws_region)
        consumer = KVSConsumer(kvs_config)

        # Create meeting output
        meeting_config = MeetingConfig(
            meeting_id=meeting_id,
            attendee_id=f"agent-{call_id}",
        )
        output = MeetingAudioOutput(meeting_config)

        # Process audio through pipeline
        async for audio_chunk, metrics in self._pipeline.process_audio_stream(
            consumer.read_stream()
        ):
            # Send to meeting
            # In production, would stream audio in real-time
            pass


async def main():
    """Run the call processor."""
    config = ProcessorConfig(
        kvs_stream_name=os.environ.get("KVS_STREAM_NAME", "triplex-audio-stream"),
        aws_region=os.environ.get("AWS_REGION", "us-east-1"),
        use_mock_llm=os.environ.get("USE_MOCK_LLM", "true").lower() == "true",
        use_mock_asr=os.environ.get("USE_MOCK_ASR", "true").lower() == "true",
        audio_bucket=os.environ.get("AUDIO_BUCKET", "triplex-audio-output"),
    )

    processor = CallProcessor(config)
    processor.initialize()

    await processor.run_forever()


if __name__ == "__main__":
    asyncio.run(main())
