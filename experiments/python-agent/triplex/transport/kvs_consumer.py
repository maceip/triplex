"""Continuous Kinesis Video Streams audio ingress for Chime meetings.

Amazon Chime SDK media stream pipelines write AAC audio access units inside
Matroska fragments. ``GetMedia`` is a persistent, chunked HTTP response, so the
container must be parsed and its AAC frames decoded before audio reaches
VAD/ASR.
"""

from __future__ import annotations

import asyncio
import importlib
import math
import struct
from collections.abc import AsyncIterator, Callable, Iterator
from dataclasses import dataclass
from typing import Any

import numpy as np

from .audio_buffer import resample
from .mkv import MatroskaFrame, MatroskaParseError, MatroskaTrack, StreamingMatroskaParser

try:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError

    HAS_BOTO3 = True
except ImportError:  # pragma: no cover - exercised by deployment environments
    HAS_BOTO3 = False
    BotoCoreError = Exception
    ClientError = Exception

av: Any
try:
    av = importlib.import_module("av")
    HAS_AV = True
except ImportError:  # pragma: no cover - deployment validation handles this
    av = None
    HAS_AV = False


@dataclass(frozen=True)
class KVSConfig:
    """Configuration for one Chime-assigned KVS audio stream."""

    stream_name: str | None = None
    stream_arn: str | None = None
    region: str = "us-east-1"
    chunk_duration_ms: int = 20
    sample_rate: int = 16000
    starting_position: str = "NOW"
    track_number: int | None = None
    reconnect_initial_seconds: float = 0.25
    reconnect_max_seconds: float = 5.0

    def __post_init__(self) -> None:
        if not self.stream_name and not self.stream_arn:
            raise ValueError("KVS stream_name or stream_arn is required")
        if self.chunk_duration_ms <= 0 or self.sample_rate <= 0:
            raise ValueError("audio chunk duration and sample rate must be positive")


class KVSConsumer:
    """Read, parse, normalize, and rechunk a Chime KVS audio stream."""

    def __init__(
        self,
        config: KVSConfig,
        *,
        control_client: Any | None = None,
        media_client_factory: Callable[[str], Any] | None = None,
    ) -> None:
        if not HAS_BOTO3 and control_client is None:
            raise ImportError("AWS transport requires boto3: pip install boto3")

        self.config = config
        self._control_client = control_client
        self._media_client_factory = media_client_factory
        self._stream_arn = config.stream_arn
        self._continuation_token: str | None = None
        self._pcm_buffer = bytearray()
        self._aac_decoders: dict[int, _AACDecoder] = {}
        self._closed = False

    def initialize(self) -> None:
        """Resolve the stream ARN and media endpoint."""
        if self._control_client is None:
            self._control_client = boto3.client(
                "kinesisvideo", region_name=self.config.region
            )
        if self._stream_arn is None:
            response = self._control_client.describe_stream(
                StreamName=self.config.stream_name
            )
            self._stream_arn = response["StreamInfo"]["StreamARN"]

    def close(self) -> None:
        """Request termination of the persistent read loop."""
        self._closed = True

    async def read_stream(self) -> AsyncIterator[bytes]:
        """Yield continuous mono PCM16 frames at the configured sample rate."""
        if self._control_client is None or self._stream_arn is None:
            await asyncio.to_thread(self.initialize)

        reconnect_delay = self.config.reconnect_initial_seconds
        while not self._closed:
            try:
                media_client = await asyncio.to_thread(self._create_media_client)
                selector = self._start_selector()
                response = await asyncio.to_thread(
                    media_client.get_media,
                    StreamARN=self._stream_arn,
                    StartSelector=selector,
                )
                async for audio_chunk in self._parse_kvs_frames(response["Payload"]):
                    reconnect_delay = self.config.reconnect_initial_seconds
                    yield audio_chunk
                if self._closed:
                    return
            except asyncio.CancelledError:
                self.close()
                raise
            except ClientError as exc:
                code = str(exc.response.get("Error", {}).get("Code", ""))
                if code == "InvalidArgumentException" and self._continuation_token:
                    self._continuation_token = None
                    continue
                if code not in {
                    "ResourceNotFoundException",
                    "ConnectionLimitExceededException",
                    "ClientLimitExceededException",
                }:
                    raise
            except (BotoCoreError, OSError, MatroskaParseError):
                # GetMedia connections are expected to time out/reconnect. The
                # embedded continuation token resumes after the last fragment.
                pass

            await asyncio.sleep(reconnect_delay)
            reconnect_delay = min(
                reconnect_delay * 2.0, self.config.reconnect_max_seconds
            )

    def _create_media_client(self) -> Any:
        control_client = self._control_client
        stream_arn = self._stream_arn
        if control_client is None or stream_arn is None:
            raise RuntimeError("KVS consumer is not initialized")
        response = control_client.get_data_endpoint(
            StreamARN=stream_arn,
            APIName="GET_MEDIA",
        )
        endpoint = response["DataEndpoint"]
        if self._media_client_factory is not None:
            return self._media_client_factory(endpoint)
        return boto3.client(
            "kinesis-video-media",
            endpoint_url=endpoint,
            region_name=self.config.region,
        )

    def _start_selector(self) -> dict[str, str]:
        if self._continuation_token:
            return {
                "StartSelectorType": "CONTINUATION_TOKEN",
                "ContinuationToken": self._continuation_token,
            }
        return {"StartSelectorType": self.config.starting_position}

    async def _parse_kvs_frames(self, payload: Any) -> AsyncIterator[bytes]:
        parser = StreamingMatroskaParser()
        iterator = iter(payload.iter_chunks(chunk_size=8192))

        while not self._closed:
            network_chunk = await asyncio.to_thread(_next_or_none, iterator)
            if network_chunk is None:
                break
            for frame in parser.feed(network_chunk):
                for pcm_chunk in self._normalize_frame(frame, parser.tracks):
                    yield pcm_chunk
            if parser.continuation_token:
                self._continuation_token = parser.continuation_token

        parser.finish()
        if parser.continuation_token:
            self._continuation_token = parser.continuation_token

    def _normalize_frame(
        self,
        frame: MatroskaFrame,
        tracks: dict[int, MatroskaTrack],
    ) -> list[bytes]:
        track = tracks.get(frame.track_number)
        if self.config.track_number is not None and frame.track_number != self.config.track_number:
            return []
        if track is None:
            raise MatroskaParseError(
                f"Matroska frame references unknown track {frame.track_number}"
            )
        if not track.is_audio:
            return []
        if track.codec_id == "A_AAC":
            decoder = self._aac_decoders.get(track.number)
            if decoder is None or decoder.codec_private != track.codec_private:
                decoder = _AACDecoder(track, self.config.sample_rate)
                self._aac_decoders[track.number] = decoder
            chunks: list[bytes] = []
            for decoded_pcm in decoder.decode(frame.payload):
                chunks.extend(self._append_pcm(decoded_pcm))
            return chunks
        if track.codec_id != "A_PCM/INT/LIT":
            raise MatroskaParseError(
                f"unsupported Chime KVS audio codec {track.codec_id!r}"
            )
        if track.bit_depth != 16:
            raise MatroskaParseError(
                f"unsupported Chime KVS PCM bit depth {track.bit_depth}"
            )

        if len(frame.payload) % 2:
            raise MatroskaParseError("PCM16 frame has an odd byte length")
        audio = np.frombuffer(frame.payload, dtype="<i2")
        channels = track.channels
        if channels > 1:
            if len(audio) % channels:
                raise MatroskaParseError("interleaved PCM does not align to channels")
            audio = np.rint(
                audio.reshape(-1, channels).astype(np.float64).mean(axis=1)
            ).astype(np.int16)

        source_rate = (
            track.sample_rate
            if track.sample_rate is not None
            else self.config.sample_rate
        )
        if source_rate != self.config.sample_rate:
            audio = resample(audio, source_rate, self.config.sample_rate).astype("<i2")

        return self._append_pcm(audio.astype("<i2", copy=False).tobytes())

    def _append_pcm(self, pcm: bytes) -> list[bytes]:
        self._pcm_buffer.extend(pcm)
        chunk_bytes = (
            self.config.sample_rate * self.config.chunk_duration_ms // 1000 * 2
        )
        chunks: list[bytes] = []
        while len(self._pcm_buffer) >= chunk_bytes:
            chunks.append(bytes(self._pcm_buffer[:chunk_bytes]))
            del self._pcm_buffer[:chunk_bytes]
        return chunks


class _AACDecoder:
    """Stateful FFmpeg/PyAV decoder and mono target-rate resampler."""

    def __init__(self, track: MatroskaTrack, target_rate: int) -> None:
        if not HAS_AV or av is None:
            raise RuntimeError(
                "Chime AAC ingress requires PyAV: install the 'av' package"
            )
        if not track.codec_private:
            raise MatroskaParseError(
                "Chime AAC track is missing Matroska CodecPrivate configuration"
            )
        self.codec_private = track.codec_private
        self._codec = av.CodecContext.create("aac", "r")
        self._codec.extradata = track.codec_private
        self._codec.open()
        self._resampler = av.AudioResampler(
            format="s16", layout="mono", rate=target_rate
        )

    def decode(self, access_unit: bytes) -> list[bytes]:
        try:
            decoded_frames = self._codec.decode(av.Packet(access_unit))
        except Exception as exc:
            raise MatroskaParseError("failed to decode Chime KVS AAC frame") from exc

        output: list[bytes] = []
        for decoded in decoded_frames:
            try:
                resampled_frames = self._resampler.resample(decoded)
            except Exception as exc:
                raise MatroskaParseError(
                    "failed to downmix or resample Chime KVS AAC frame"
                ) from exc
            for resampled_frame in resampled_frames:
                audio = resampled_frame.to_ndarray()
                if audio.dtype != np.int16:
                    raise MatroskaParseError("AAC decoder returned non-PCM16 audio")
                output.append(audio.astype("<i2", copy=False).reshape(-1).tobytes())
        return output


class SyntheticKVSConsumer:
    """Explicit deterministic audio source for demos and unit tests."""

    def __init__(self, config: KVSConfig | None = None) -> None:
        self.config = config or KVSConfig(stream_name="synthetic")

    def initialize(self) -> None:
        return None

    async def read_stream(self) -> AsyncIterator[bytes]:
        chunk_samples = (
            self.config.sample_rate * self.config.chunk_duration_ms // 1000
        )
        for frame_index in range(250):
            samples = []
            for sample_index in range(chunk_samples):
                timestamp = (
                    frame_index * chunk_samples + sample_index
                ) / self.config.sample_rate
                sample = int(
                    10000
                    * (
                        0.3 * math.sin(2 * math.pi * 440 * timestamp)
                        + 0.2 * math.sin(2 * math.pi * 880 * timestamp)
                        + 0.1 * math.sin(2 * math.pi * 1320 * timestamp)
                    )
                )
                samples.append(sample)
            yield struct.pack(f"<{len(samples)}h", *samples)
            await asyncio.sleep(self.config.chunk_duration_ms / 1000)

        silence = b"\x00" * chunk_samples * 2
        for _ in range(50):
            yield silence
            await asyncio.sleep(self.config.chunk_duration_ms / 1000)


def get_kvs_consumer(
    config: KVSConfig, *, synthetic: bool = False
) -> KVSConsumer | SyntheticKVSConsumer:
    """Construct an AWS consumer, or an explicitly requested synthetic source."""
    if synthetic:
        return SyntheticKVSConsumer(config)
    return KVSConsumer(config)


def _next_or_none(iterator: Iterator[bytes]) -> bytes | None:
    try:
        return next(iterator)
    except StopIteration:
        return None
