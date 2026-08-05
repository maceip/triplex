"""Audio frontend - mel spectrogram extraction.

Implements the same transform as Cosmo's audio_frontend.tflite:
- Input: Raw PCM audio (16kHz mono)
- Output: Mel spectrogram [T, 128]

Specs from Inherent runtime contract:
- HOP_SAMPLES = 320 (20ms at 16kHz)
- n_mels = 128
- max_frames = 3000 (60 seconds)
"""

from __future__ import annotations

import warnings
from dataclasses import dataclass

import numpy as np

try:
    import torch
    import torchaudio

    HAS_TORCH = True
except ImportError:
    HAS_TORCH = False


@dataclass
class FrontendConfig:
    """Mel spectrogram config matching Inherent/Cosmo audio_frontend."""

    sample_rate: int = 16000
    n_fft: int = 640  # 40ms window
    hop_length: int = 320  # 20ms hop (from inherent HOP_SAMPLES)
    n_mels: int = 128
    f_min: float = 0.0
    f_max: float = 8000.0


class AudioFrontend:
    """Extract mel spectrograms from audio.

    Matches the Cosmo audio_frontend.tflite output so we can feed
    into the gatekeeper model.
    """

    def __init__(self, config: FrontendConfig | None = None):
        if not HAS_TORCH:
            raise ImportError("torchaudio required - install with: pip install torchaudio")

        self.config = config or FrontendConfig()

        # Suppress the mel filterbank warning (benign with our settings)
        with warnings.catch_warnings():
            warnings.filterwarnings("ignore", message="At least one mel filterbank")
            self._mel_transform = torchaudio.transforms.MelSpectrogram(
                sample_rate=self.config.sample_rate,
                n_fft=self.config.n_fft,
                hop_length=self.config.hop_length,
                n_mels=self.config.n_mels,
                f_min=self.config.f_min,
                f_max=self.config.f_max,
                power=2.0,
            )

    def extract_mel(self, audio: np.ndarray | bytes) -> np.ndarray:
        """Extract mel spectrogram from audio.

        Args:
            audio: Raw PCM audio as numpy array [samples] or bytes

        Returns:
            Mel spectrogram [T, 128] as numpy array
        """
        if isinstance(audio, bytes):
            audio = self._pcm_to_numpy(audio)

        # Convert to torch tensor
        audio_tensor = torch.from_numpy(audio.astype(np.float32))
        if audio_tensor.dim() == 1:
            audio_tensor = audio_tensor.unsqueeze(0)

        # Extract mel [1, n_mels, T]
        mel = self._mel_transform(audio_tensor)

        # Log scaling
        mel = torch.log(mel + 1e-8)

        # Transpose to [T, n_mels] then squeeze batch
        mel = mel.squeeze(0).transpose(0, 1)

        return mel.numpy()

    def _pcm_to_numpy(self, pcm: bytes) -> np.ndarray:
        """Convert raw PCM bytes to float32 numpy array."""
        import struct

        # 16-bit signed integers
        samples = struct.unpack(f"<{len(pcm) // 2}h", pcm)
        return np.array(samples, dtype=np.float32) / 32768.0

    def frames_to_seconds(self, n_frames: int) -> float:
        """Convert mel frames to duration in seconds."""
        return n_frames * self.config.hop_length / self.config.sample_rate

    def seconds_to_frames(self, seconds: float) -> int:
        """Convert duration in seconds to mel frames."""
        return int(seconds * self.config.sample_rate / self.config.hop_length)


def load_audio(path: str, sample_rate: int = 16000) -> np.ndarray:
    """Load audio file and resample if needed.

    Args:
        path: Path to audio file
        sample_rate: Target sample rate

    Returns:
        Audio as numpy array [samples]
    """
    if not HAS_TORCH:
        raise ImportError("torchaudio required")

    waveform, sr = torchaudio.load(path)

    # Resample if needed
    if sr != sample_rate:
        resampler = torchaudio.transforms.Resample(sr, sample_rate)
        waveform = resampler(waveform)

    # Convert to mono if stereo
    if waveform.shape[0] > 1:
        waveform = waveform.mean(dim=0, keepdim=True)

    return waveform.squeeze(0).numpy()
