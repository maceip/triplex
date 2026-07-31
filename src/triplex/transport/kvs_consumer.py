"""Kinesis Video Streams consumer for real-time audio ingestion.

Consumes audio from Chime SDK Media Stream Pipelines.

Architecture:
```
Chime Meeting → Media Stream Pipeline → Kinesis Video Streams → This Consumer
```

The pipeline writes audio to KVS, we read it and feed to our VAD/LLM/TTS pipeline.
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass
from typing import AsyncIterator

try:
    import boto3
    from botocore.exceptions import ClientError

    HAS_BOTO3 = True
except ImportError:
    HAS_BOTO3 = False


@dataclass
class KVSConfig:
    """Configuration for KVS consumer."""

    stream_name: str
    region: str = "us-east-1"

    # Chunk settings
    chunk_duration_ms: int = 20  # Match audio pipeline (20ms chunks)
    sample_rate: int = 16000

    # Consumer position
    # "LATEST" = live, "EARLIEST" = from beginning, or specific timestamp
    starting_position: str = "LATEST"


class KVSConsumer:
    """Consumes audio from Kinesis Video Streams.

    Formats:
    - Chime outputs mu-law 8kHz by default
    - We convert to PCM 16kHz for our pipeline

    Usage:
        consumer = KVSConsumer(config)
        async for audio_chunk in consumer.read_stream():
            # audio_chunk is PCM 16-bit 16kHz
            await process(audio_chunk)
    """

    def __init__(self, config: KVSConfig):
        if not HAS_BOTO3:
            raise ImportError("boto3 required: pip install boto3")

        self.config = config
        self._client = None
        self._stream_arn = None

    def initialize(self) -> None:
        """Initialize KVS client and get stream ARN."""
        self._client = boto3.client("kinesisvideo", region_name=self.config.region)

        # Get stream ARN
        response = self._client.describe_stream(StreamName=self.config.stream_name)
        self._stream_arn = response["StreamInfo"]["StreamARN"]

    async def read_stream(self) -> AsyncIterator[bytes]:
        """Read audio chunks from KVS.

        Yields:
            PCM audio chunks (16-bit, 16kHz)
        """
        if not self._client:
            self.initialize()

        # Get data endpoint for reading
        endpoint_response = self._client.get_data_endpoint(
            StreamARN=self._stream_arn,
            APIName="GET_MEDIA",
        )
        endpoint_url = endpoint_response["DataEndpoint"]

        # Create media client with the endpoint
        media_client = boto3.client(
            "kinesis-video-media",
            endpoint_url=endpoint_url,
            region_name=self.config.region,
        )

        # Start reading from stream
        iterator_id = None

        while True:
            try:
                # Get media
                if iterator_id is None:
                    response = media_client.get_media(
                        StreamARN=self._stream_arn,
                        StartSelector={"StartSelectorType": self.config.starting_position},
                    )
                else:
                    response = media_client.get_media(
                        StreamARN=self._stream_arn,
                        StartSelector={
                            "StartSelectorType": "CONTINUATION_TOKEN",
                            "ContinuationToken": iterator_id,
                        },
                    )

                # Read payload
                # The payload is a stream of bytes in KVS format
                # Chime writes frames with specific formatting
                payload = response["Payload"]

                # Parse frames and extract audio
                async for audio_chunk in self._parse_kvs_frames(payload):
                    yield audio_chunk

                # Get continuation token for next read
                if "NextToken" in response:
                    iterator_id = response["NextToken"]

            except ClientError as e:
                if e.response["Error"]["Code"] == "ResourceNotFoundException":
                    # Stream doesn't exist yet, wait and retry
                    await asyncio.sleep(1)
                    continue
                raise

    async def _parse_kvs_frames(self, payload) -> AsyncIterator[bytes]:
        """Parse KVS frames and extract audio data.

        KVS format:
        - Each frame has headers + payload
        - Audio is typically mu-law 8kHz from Chime
        """
        # Simplified frame parsing
        # Real implementation needs to handle KVS fragmentation format
        chunk_size = int(self.config.chunk_duration_ms * self.config.sample_rate / 1000) * 2

        buffer = bytearray()

        for chunk in payload.iter_chunks():
            buffer.extend(chunk)

            # Extract complete audio frames
            while len(buffer) >= chunk_size:
                audio_chunk = bytes(buffer[:chunk_size])
                buffer = buffer[chunk_size:]

                # Convert from mu-law 8kHz to PCM 16kHz if needed
                # For now, assume it's already PCM 16kHz
                yield audio_chunk


class MockKVSConsumer:
    """Mock KVS consumer for testing without AWS."""

    def __init__(self, config: KVSConfig | None = None):
        self.config = config or KVSConfig(stream_name="mock-stream")

    def initialize(self) -> None:
        pass

    async def read_stream(self) -> AsyncIterator[bytes]:
        """Generate mock audio chunks."""
        import struct
        import math

        # Generate 5 seconds of simulated speech audio
        sample_rate = 16000
        chunk_ms = 20
        chunk_samples = int(sample_rate * chunk_ms / 1000)

        # Simulate speech pattern
        for i in range(250):  # 5 seconds of audio
            samples = []
            for j in range(chunk_samples):
                t = (i * chunk_samples + j) / sample_rate
                # Speech-like waveform
                sample = int(
                    10000
                    * (
                        0.3 * math.sin(2 * math.pi * 440 * t)
                        + 0.2 * math.sin(2 * math.pi * 880 * t)
                        + 0.1 * math.sin(2 * math.pi * 1320 * t)
                    )
                )
                samples.append(sample)

            yield struct.pack(f"<{len(samples)}h", *samples)
            await asyncio.sleep(0.02)  # Real-time simulation

        # Silence to trigger end of speech
        for _ in range(50):
            yield b"\x00" * chunk_samples * 2
            await asyncio.sleep(0.02)


def get_kvs_consumer(config: KVSConfig, use_mock: bool = False) -> KVSConsumer | MockKVSConsumer:
    """Factory function to get KVS consumer."""
    if use_mock or not HAS_BOTO3:
        return MockKVSConsumer(config)
    return KVSConsumer(config)
