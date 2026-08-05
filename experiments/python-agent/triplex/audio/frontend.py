"""Audio frontend for mel spectrogram extraction.

Compatible with the Inherent project's preprocessing pipeline.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import torch
import torchaudio


@dataclass
class AudioFrontendConfig:
    """Configuration for mel spectrogram extraction.

    Defaults match the Inherent project's expected input.
    """

    sample_rate: int = 16000
    n_fft: int = 512
    hop_length: int = 160  # 10ms at 16kHz
    n_mels: int = 128
    f_min: float = 0.0
    f_max: float = 8000.0
    power: float = 2.0
    normalized: bool = False


class AudioFrontend:
    """Extract mel spectrograms from audio for the Inherent gate.

    Input: Raw PCM audio (16kHz mono)
    Output: Mel spectrogram [1, T, 128]
    """

    def __init__(self, config: AudioFrontendConfig | None = None):
        self.config = config or AudioFrontendConfig()

        # Create mel filterbank
        self.mel_transform = torchaudio.transforms.MelSpectrogram(
            sample_rate=self.config.sample_rate,
            n_fft=self.config.n_fft,
            hop_length=self.config.hop_length,
            n_mels=self.config.n_mels,
            f_min=self.config.f_min,
            f_max=self.config.f_max,
            power=self.config.power,
            normalized=self.config.normalized,
        )

    def extract_mel(self, audio: torch.Tensor | bytes) -> torch.Tensor:
        """Extract mel spectrogram from audio.

        Args:
            audio: Either a tensor [samples] or raw PCM bytes

        Returns:
            Mel spectrogram [1, T, 128] where T depends on audio length
        """
        if isinstance(audio, bytes):
            # Convert raw PCM bytes to tensor
            audio = self._pcm_bytes_to_tensor(audio)

        # Ensure correct shape [1, samples]
        if audio.dim() == 1:
            audio = audio.unsqueeze(0)

        # Extract mel spectrogram [1, n_mels, T]
        mel = self.mel_transform(audio)

        # Transpose to [1, T, n_mels] for Inherent model
        mel = mel.transpose(1, 2)

        # Log scaling (common practice for voice)
        mel = torch.log(mel + 1e-8)

        return mel

    def _pcm_bytes_to_tensor(self, pcm: bytes) -> torch.Tensor:
        """Convert raw PCM bytes to float tensor."""
        import struct

        # Assume 16-bit PCM
        samples = struct.unpack(f"<{len(pcm) // 2}h", pcm)
        return torch.tensor(samples, dtype=torch.float32) / 32768.0

    def frame_duration_ms(self, n_frames: int) -> float:
        """Calculate duration in milliseconds for a given number of frames."""
        return n_frames * self.config.hop_length / self.config.sample_rate * 1000

    def duration_to_frames(self, duration_ms: float) -> int:
        """Calculate number of frames for a given duration in ms."""
        return math.ceil(
            duration_ms * self.config.sample_rate / (self.config.hop_length * 1000)
        )


def load_inherent_frontend(model_path: str) -> "AudioFrontend":
    """Load audio frontend matching the Inherent project's preprocessing.

    The Inherent project ships with a TFLite frontend model at
    data/audio_frontend.tflite. For Python inference, we recreate
    the same transform using torchaudio.
    """
    # The Inherent frontend uses these exact settings:
    return AudioFrontend(AudioFrontendConfig(
        sample_rate=16000,
        n_fft=512,
        hop_length=160,
        n_mels=128,
        f_min=0.0,
        f_max=8000.0,
    ))
