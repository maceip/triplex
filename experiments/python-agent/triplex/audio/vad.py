# SUPERSEDED-BY: RUNTIME_INVARIANTS.md §3.1 (agent-core::vad)
# RETIRED: this module does not ship and is not maintained.
# Its contract was ported; see DISPOSITION_LEDGER.md. Read for
# reference only — do not extend, and do not treat as a fallback.

"""Voice Activity Detection (VAD).

Detects when the recipient is speaking vs silence/noise.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

import torch


class VoiceState(Enum):
    """State of voice activity."""

    SILENCE = "silence"
    SPEECH = "speech"
    UNKNOWN = "unknown"


@dataclass
class VADConfig:
    """Configuration for VAD."""

    frame_duration_ms: int = 30  # WebRTC VAD frame size
    aggressiveness: int = 3  # 0-3, higher = more aggressive filtering
    speech_threshold: float = 0.5  # Probability threshold for speech
    min_speech_duration_ms: int = 250
    min_silence_duration_ms: int = 100


class VoiceActivityDetector:
    """Detect voice activity in audio.

    Can use different backends:
    - WebRTC VAD (fast, rule-based)
    - Silero VAD (neural, more accurate)
    - Simple energy-based (fallback)
    """

    def __init__(self, config: VADConfig | None = None, backend: str = "energy"):
        self.config = config or VADConfig()
        self.backend = backend
        self._model = None

        if backend == "silero":
            self._load_silero()

    def _load_silero(self) -> None:
        """Load Silero VAD model."""
        # Silero VAD is a pre-trained neural model
        # Model is loaded from torch hub
        self._model, self._utils = torch.hub.load(
            repo_or_dir="snakers4/silero-vad",
            model="silero_vad",
            force_reload=False,
            onnx=False,
            trust_repo=True,
        )
        self._model.eval()

    def detect(self, audio: torch.Tensor | bytes, sample_rate: int = 16000) -> VoiceState:
        """Detect if audio contains speech.

        Args:
            audio: Audio tensor or raw PCM bytes
            sample_rate: Audio sample rate (default 16kHz)

        Returns:
            VoiceState indicating speech, silence, or unknown
        """
        if isinstance(audio, bytes):
            audio = self._pcm_bytes_to_tensor(audio)

        if self.backend == "silero" and self._model is not None:
            return self._detect_silero(audio, sample_rate)
        else:
            return self._detect_energy(audio)

    def _detect_silero(self, audio: torch.Tensor, sample_rate: int) -> VoiceState:
        """Use Silero VAD model."""
        # Silero expects 512 samples at 16 kHz. Pad shorter real-time frames
        # (Triplex uses 20 ms / 320-sample chunks) so inference is valid.
        if sample_rate == 16000 and len(audio) < 512:
            audio = torch.nn.functional.pad(audio, (0, 512 - len(audio)))

        with torch.no_grad():
            speech_prob = self._model(audio, sample_rate)

        if speech_prob > self.config.speech_threshold:
            return VoiceState.SPEECH
        return VoiceState.SILENCE

    def _detect_energy(self, audio: torch.Tensor) -> VoiceState:
        """Simple energy-based VAD fallback."""
        # Calculate RMS energy
        energy = torch.sqrt(torch.mean(audio**2))

        # Threshold based on empirical values
        threshold = 0.01

        if energy > threshold:
            return VoiceState.SPEECH
        return VoiceState.SILENCE

    def _pcm_bytes_to_tensor(self, pcm: bytes) -> torch.Tensor:
        """Convert raw PCM bytes to float tensor."""
        import struct

        samples = struct.unpack(f"<{len(pcm) // 2}h", pcm)
        return torch.tensor(samples, dtype=torch.float32) / 32768.0

    def get_speech_segments(
        self, audio: torch.Tensor, sample_rate: int = 16000
    ) -> list[tuple[float, float]]:
        """Get start and end times of speech segments.

        Returns:
            List of (start_time_seconds, end_time_seconds) tuples
        """
        # Frame the audio and detect speech per frame
        frame_samples = int(self.config.frame_duration_ms * sample_rate / 1000)
        segments = []
        in_speech = False
        speech_start = 0.0

        for i in range(0, len(audio) - frame_samples, frame_samples):
            frame = audio[i : i + frame_samples]
            state = self.detect(frame, sample_rate)

            time_seconds = i / sample_rate

            if state == VoiceState.SPEECH and not in_speech:
                in_speech = True
                speech_start = time_seconds
            elif state == VoiceState.SILENCE and in_speech:
                in_speech = False
                segments.append((speech_start, time_seconds))

        if in_speech:
            segments.append((speech_start, len(audio) / sample_rate))

        return segments
