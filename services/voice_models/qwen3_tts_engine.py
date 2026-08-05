"""Consent-gated Qwen3-TTS zero-shot voice cloning.

The clone engine is deliberately separate from Kokoro.  Selecting a customer
voice must either produce that voice or fail closed; a silent fallback to a
branded voice would be a consent and product correctness bug.
"""

from __future__ import annotations

import asyncio
import hashlib
import importlib.util
import threading
import wave
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

from .kokoro_engine import SynthesizedAudioChunk

try:
    import torch
    from qwen_tts import Qwen3TTSModel

    HAS_QWEN_TTS = True
except ImportError:  # pragma: no cover - optional production dependency
    torch = None  # type: ignore[assignment]
    Qwen3TTSModel = None
    HAS_QWEN_TTS = False


@dataclass(frozen=True)
class Qwen3TTSConfig:
    """Model and reference-audio safety limits."""

    model_name: str = "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
    model_revision: str = "5d83992436eae1d760afd27aff78a71d676296fc"
    device: str = "auto"
    language: str = "English"
    chunk_duration_ms: int = 100
    minimum_reference_seconds: float = 3.0
    maximum_reference_seconds: float = 30.0
    use_flash_attention: bool = True


@dataclass(frozen=True)
class VoiceCloneProfile:
    """Auditable authority and source material for one cloned voice."""

    profile_id: str
    speaker_id: str
    reference_audio: Path
    reference_text: str
    consent_id: str
    consent_verified_at: datetime
    language: str = "English"

    def __post_init__(self) -> None:
        if not self.profile_id.strip() or not self.speaker_id.strip():
            raise ValueError("profile_id and speaker_id are required")
        if not self.consent_id.strip():
            raise ValueError("voice cloning requires a consent_id")
        if self.consent_verified_at.tzinfo is None:
            raise ValueError("consent_verified_at must be timezone-aware")
        if self.consent_verified_at > datetime.now(self.consent_verified_at.tzinfo):
            raise ValueError("consent_verified_at cannot be in the future")
        if not self.reference_text.strip():
            raise ValueError("reference_text is required for faithful cloning")


@dataclass(frozen=True)
class RegisteredVoiceClone:
    """Validated profile plus model-specific reusable prompt."""

    profile: VoiceCloneProfile
    reference_sha256: str
    duration_seconds: float
    prompt: Any


class Qwen3TTSEngine:
    """Local Qwen3-TTS adapter with validated, cached voice prompts."""

    def __init__(
        self,
        config: Qwen3TTSConfig | None = None,
        *,
        model: Any | None = None,
    ) -> None:
        if model is None and not HAS_QWEN_TTS:
            raise ImportError(
                "Qwen3-TTS required for cloned voices: pip install qwen-tts"
            )
        self.config = config or Qwen3TTSConfig()
        self._model = model
        self._voices: dict[str, RegisteredVoiceClone] = {}
        self._active_profile_id: str | None = None
        self._model_lock = threading.Lock()

    @property
    def active_profile_id(self) -> str | None:
        return self._active_profile_id

    @property
    def registered_profile_ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._voices))

    def initialize(self) -> None:
        """Load the base model once on the configured accelerator."""
        with self._model_lock:
            if self._model is not None:
                return
            if Qwen3TTSModel is None:
                raise RuntimeError("Qwen3-TTS model class is unavailable")
            device, dtype = _resolve_device_and_dtype(self.config.device)
            load_options: dict[str, Any] = {
                "device_map": device,
                "dtype": dtype,
                "revision": self.config.model_revision,
            }
            if (
                self.config.use_flash_attention
                and device.startswith("cuda")
                and importlib.util.find_spec("flash_attn") is not None
            ):
                load_options["attn_implementation"] = "flash_attention_2"
            self._model = Qwen3TTSModel.from_pretrained(
                self.config.model_name, **load_options
            )

    def register_voice(self, profile: VoiceCloneProfile) -> RegisteredVoiceClone:
        """Validate local reference audio and cache its clone prompt."""
        self.initialize()
        audio_path = profile.reference_audio.expanduser().resolve(strict=True)
        if not audio_path.is_file():
            raise ValueError("reference_audio must be a local file")
        duration = _validated_wav_duration(audio_path)
        if not (
            self.config.minimum_reference_seconds
            <= duration
            <= self.config.maximum_reference_seconds
        ):
            raise ValueError(
                "reference audio duration must be between "
                f"{self.config.minimum_reference_seconds:g} and "
                f"{self.config.maximum_reference_seconds:g} seconds"
            )
        digest = hashlib.sha256(audio_path.read_bytes()).hexdigest()
        existing = self._voices.get(profile.profile_id)
        if existing is not None:
            same_authority = (
                existing.reference_sha256 == digest
                and existing.profile.speaker_id == profile.speaker_id
                and existing.profile.consent_id == profile.consent_id
                and existing.profile.reference_text == profile.reference_text
            )
            if not same_authority:
                raise ValueError(
                    f"voice clone profile {profile.profile_id!r} is already registered "
                    "with different source or consent metadata"
                )
            return existing
        if self._model is None:
            raise RuntimeError("Qwen3-TTS initialization did not create a model")
        with self._model_lock:
            prompt = self._model.create_voice_clone_prompt(
                ref_audio=str(audio_path),
                ref_text=profile.reference_text,
                x_vector_only_mode=False,
            )
        registered = RegisteredVoiceClone(
            profile=profile,
            reference_sha256=digest,
            duration_seconds=duration,
            prompt=prompt,
        )
        self._voices[profile.profile_id] = registered
        return registered

    def select_voice(self, profile_id: str) -> None:
        """Select a previously consent-validated profile for synthesis."""
        if profile_id not in self._voices:
            raise KeyError(f"voice clone profile {profile_id!r} is not registered")
        self._active_profile_id = profile_id

    async def synthesize(
        self, text: str, *, profile_id: str | None = None
    ) -> tuple[np.ndarray, int]:
        """Generate cloned speech without blocking the audio event loop."""
        selected_profile_id = profile_id or self._active_profile_id
        if selected_profile_id is None:
            raise RuntimeError("a cloned voice profile must be selected explicitly")
        registered = self._voices[selected_profile_id]
        return await asyncio.to_thread(self._generate, text, registered)

    def _generate(
        self, text: str, registered: RegisteredVoiceClone
    ) -> tuple[np.ndarray, int]:
        if self._model is None:
            raise RuntimeError("Qwen3-TTS initialization did not create a model")
        with self._model_lock:
            wavs, sample_rate = self._model.generate_voice_clone(
                text=text,
                language=registered.profile.language or self.config.language,
                voice_clone_prompt=registered.prompt,
            )
        if not wavs:
            raise RuntimeError("Qwen3-TTS returned no audio")
        audio = np.asarray(wavs[0], dtype=np.float32).reshape(-1)
        if audio.size == 0:
            raise RuntimeError("Qwen3-TTS returned empty audio")
        if not np.all(np.isfinite(audio)):
            raise RuntimeError("Qwen3-TTS returned non-finite audio")
        return audio, int(sample_rate)

    async def synthesize_stream(self, text: str) -> AsyncIterator[bytes]:
        async for chunk in self.synthesize_stream_chunks(text):
            yield chunk.pcm

    async def synthesize_stream_chunks(
        self, text: str, *, profile_id: str | None = None
    ) -> AsyncIterator[SynthesizedAudioChunk]:
        """Chunk the model result for bounded Chime/WebSocket frames."""
        audio, sample_rate = await self.synthesize(text, profile_id=profile_id)
        pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")
        chunk_samples = max(1, sample_rate * self.config.chunk_duration_ms // 1000)
        chunk_count = max(1, (len(pcm) + chunk_samples - 1) // chunk_samples)
        # Qwen's high-level clone API does not return word timestamps. Attribute
        # text only to the final frame so interrupted partial words are never
        # written into conversation state.
        text_chunks = [""] * chunk_count
        text_chunks[-1] = text
        for index, offset in enumerate(range(0, len(pcm), chunk_samples)):
            yield SynthesizedAudioChunk(
                pcm=pcm[offset : offset + chunk_samples].tobytes(),
                sample_rate=sample_rate,
                text=text_chunks[index],
            )


def _validated_wav_duration(path: Path) -> float:
    if path.suffix.lower() != ".wav":
        raise ValueError("reference_audio must be a PCM WAV file")
    try:
        with wave.open(str(path), "rb") as source:
            if source.getnchannels() != 1:
                raise ValueError("reference audio must be mono")
            if source.getsampwidth() != 2:
                raise ValueError("reference audio must be 16-bit PCM")
            if source.getcomptype() != "NONE":
                raise ValueError("reference audio must be uncompressed PCM")
            return source.getnframes() / source.getframerate()
    except wave.Error as exc:
        raise ValueError("reference_audio is not a valid WAV file") from exc


def _resolve_device_and_dtype(configured_device: str) -> tuple[str, Any]:
    if torch is None:
        raise RuntimeError("PyTorch is required to select a Qwen3-TTS device")
    if configured_device != "auto":
        device = configured_device
    elif torch.cuda.is_available():
        device = "cuda:0"
    elif getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        device = "mps"
    else:
        device = "cpu"
    dtype = torch.bfloat16 if device.startswith(("cuda", "mps")) else torch.float32
    return device, dtype
