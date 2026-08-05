"""Audio buffer and format handling for Twilio streams.

Twilio uses mu-law 8kHz by default. We need to:
1. Convert mu-law ↔ PCM
2. Resample 8kHz → 24kHz (for our models)
3. Buffer chunks for processing
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

import numpy as np

try:
    import scipy.signal

    HAS_SCIPY = True
except ImportError:
    HAS_SCIPY = False


@dataclass
class AudioFormat:
    """Audio format specification."""

    sample_rate: int
    channels: int = 1
    bits_per_sample: int = 16

    @property
    def bytes_per_sample(self) -> int:
        return self.bits_per_sample // 8


# Standard formats
FORMAT_TWILIO_MULAW = AudioFormat(sample_rate=8000)  # mu-law encoded
FORMAT_TWILIO_PCM = AudioFormat(sample_rate=8000)
FORMAT_PROCESSING = AudioFormat(sample_rate=24000)  # Our internal format


# Mu-law encoding/decoding tables (ITU-T G.711)
_MU_LAW_TABLE: list[int] = []
_MU_LAW_INVERSE: dict[int, int] = {}


def _init_mulaw_tables() -> None:
    """Initialize mu-law lookup tables."""
    global _MU_LAW_TABLE, _MU_LAW_INVERSE

    if _MU_LAW_TABLE:
        return

    for i in range(256):
        # Decode mu-law to linear
        # Complement all bits
        sign = 1 if (i & 0x80) == 0 else -1
        exponent = (i >> 4) & 0x07
        mantissa = i & 0x0F

        decoded = sign * (
            (2 * (mantissa + 16) + 32) * (2**exponent) - 32
        )
        _MU_LAW_TABLE.append(decoded)
        _MU_LAW_INVERSE[decoded] = i


def mulaw_to_pcm(mulaw_bytes: bytes) -> np.ndarray:
    """Convert mu-law encoded audio to 16-bit PCM.

    Args:
        mulaw_bytes: Mu-law encoded audio (8-bit per sample)

    Returns:
        PCM audio as int16 numpy array
    """
    _init_mulaw_tables()

    samples = np.frombuffer(mulaw_bytes, dtype=np.uint8)
    pcm = np.array([_MU_LAW_TABLE[s] for s in samples], dtype=np.int16)

    # Normalize to int16 range (mu-law is 14-bit internally)
    pcm = (pcm * 4).clip(-32768, 32767).astype(np.int16)

    return pcm


def pcm_to_mulaw(pcm: np.ndarray) -> bytes:
    """Convert 16-bit PCM to mu-law.

    Args:
        pcm: PCM audio as int16 numpy array

    Returns:
        Mu-law encoded bytes
    """
    _init_mulaw_tables()

    # Scale down from int16 range
    pcm_scaled = (pcm // 4).astype(np.int32)

    # Encode each sample
    mulaw = bytearray(len(pcm))
    for i, sample in enumerate(pcm_scaled):
        # Find closest mu-law value
        mulaw[i] = _MU_LAW_INVERSE.get(sample, 0xFF)

    return bytes(mulaw)


def resample(audio: np.ndarray, from_rate: int, to_rate: int) -> np.ndarray:
    """Resample audio to a different sample rate.

    Args:
        audio: Audio samples
        from_rate: Source sample rate
        to_rate: Target sample rate

    Returns:
        Resampled audio
    """
    if from_rate == to_rate:
        return audio

    if not HAS_SCIPY:
        # Simple linear interpolation fallback
        ratio = to_rate / from_rate
        new_length = int(len(audio) * ratio)
        indices = np.linspace(0, len(audio) - 1, new_length)
        return np.asarray(np.interp(indices, np.arange(len(audio)), audio))

    # Use scipy for quality resampling
    num_samples = int(len(audio) * to_rate / from_rate)
    resampled = scipy.signal.resample(audio, num_samples)

    # Preserve dtype
    if audio.dtype == np.int16:
        resampled = resampled.clip(-32768, 32767).astype(np.int16)

    return np.asarray(resampled)


class AudioChunker:
    """Break audio stream into fixed-size chunks for processing.

    For the reflex layer, we process 20ms chunks:
    - 8kHz: 160 samples
    - 16kHz: 320 samples
    - 24kHz: 480 samples
    """

    def __init__(
        self,
        sample_rate: int = 24000,
        chunk_ms: float = 20.0,
    ):
        self.sample_rate = sample_rate
        self.chunk_ms = chunk_ms
        self.chunk_samples = int(sample_rate * chunk_ms / 1000)

        self._buffer = np.array([], dtype=np.float32)

    def add(self, audio: np.ndarray) -> Iterator[np.ndarray]:
        """Add audio and yield complete chunks.

        Args:
            audio: New audio samples

        Yields:
            Complete chunks of `chunk_samples` size
        """
        if audio.dtype != np.float32:
            audio = audio.astype(np.float32) / 32768.0

        self._buffer = np.concatenate([self._buffer, audio])

        while len(self._buffer) >= self.chunk_samples:
            chunk = self._buffer[: self.chunk_samples]
            self._buffer = self._buffer[self.chunk_samples :]
            yield chunk

    def flush(self) -> np.ndarray | None:
        """Return remaining partial chunk, if any."""
        if len(self._buffer) > 0:
            remaining = self._buffer.copy()
            self._buffer = np.array([], dtype=np.float32)
            return remaining
        return None

    @property
    def buffered_samples(self) -> int:
        return len(self._buffer)


class AudioOutputBuffer:
    """Buffer for outgoing audio with timing control.

    Ensures smooth playback by buffering synthesized audio
    and sending at the right rate.
    """

    def __init__(self, sample_rate: int = 24000):
        self.sample_rate = sample_rate
        self._buffer: list[np.ndarray] = []
        self._position = 0

    def add(self, audio: np.ndarray) -> None:
        """Add audio to the output buffer."""
        self._buffer.append(audio.copy())

    def get_chunk(self, num_samples: int) -> np.ndarray:
        """Get a chunk of audio for playback.

        Returns zeros if buffer is empty (silence).
        """
        chunk = np.zeros(num_samples, dtype=np.float32)
        chunk_pos = 0

        while chunk_pos < num_samples and self._buffer:
            remaining = num_samples - chunk_pos

            if len(self._buffer[0]) <= remaining:
                # Take entire buffered chunk
                buffered = self._buffer.pop(0)
                chunk[chunk_pos : chunk_pos + len(buffered)] = buffered
                chunk_pos += len(buffered)
            else:
                # Take partial chunk
                chunk[chunk_pos:] = self._buffer[0][:remaining]
                self._buffer[0] = self._buffer[0][remaining:]
                chunk_pos = num_samples

        return chunk

    def clear(self) -> None:
        """Clear the buffer (for interruption)."""
        self._buffer.clear()

    def is_empty(self) -> bool:
        return len(self._buffer) == 0
