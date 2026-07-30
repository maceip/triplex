"""Parler-TTS based speech synthesis.

Parler-TTS Mini (880M params) provides:
- Good quality speech
- Style control via description prompts
- ~6s generation on CPU for short phrases
- 44.1kHz output

For the tiered response system, we pre-cache common immediate responses
for < 100ms playback.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

try:
    import torch
    from parler_tts import ParlerTTSForConditionalGeneration
    from transformers import AutoTokenizer
    import soundfile as sf

    HAS_PARLER = True
except ImportError:
    HAS_PARLER = False


@dataclass
class TTSConfig:
    """Configuration for Parler-TTS."""

    model_name: str = "parler-tts/parler-tts-mini-v1"
    device: str = "auto"  # "auto", "cpu", "cuda", "mps"
    default_description: str = "A female speaker delivers a slightly expressive and animated speech."
    sample_rate: int = 44100


class ParlerTTS:
    """Parler-TTS wrapper with caching for immediate responses.

    For the tiered response system:
    - Immediate responses (< 100ms): Pre-cached audio
    - Fast/Normal responses: Generated on-demand
    """

    def __init__(self, config: TTSConfig | None = None):
        if not HAS_PARLER:
            raise ImportError("parler-tts required: pip install git+https://github.com/huggingface/parler-tts.git")

        self.config = config or TTSConfig()
        self._model = None
        self._tokenizer = None
        self._device = None

        # Cache for immediate responses (text -> audio)
        self._cache: dict[str, np.ndarray] = {}
        self._cache_dir = Path.home() / ".cache" / "triplex" / "tts_cache"

    def _get_device(self) -> str:
        if self.config.device == "auto":
            if torch.cuda.is_available():
                return "cuda:0"
            elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                return "mps"
            return "cpu"
        return self.config.device

    def load(self) -> None:
        """Load the model (lazy)."""
        if self._model is not None:
            return

        self._device = self._get_device()
        print(f"Loading Parler-TTS on {self._device}...")

        self._model = ParlerTTSForConditionalGeneration.from_pretrained(
            self.config.model_name
        ).to(self._device)
        self._tokenizer = AutoTokenizer.from_pretrained(self.config.model_name)

    def synthesize(
        self,
        text: str,
        description: str | None = None,
    ) -> tuple[np.ndarray, int]:
        """Synthesize speech from text.

        Args:
            text: Text to speak
            description: Style description (e.g., "A female speaker delivers...")

        Returns:
            Tuple of (audio_array, sample_rate)
        """
        self.load()

        description = description or self.config.default_description

        input_ids = self._tokenizer(description, return_tensors="pt").input_ids.to(self._device)
        prompt_input_ids = self._tokenizer(text, return_tensors="pt").input_ids.to(self._device)

        generation = self._model.generate(
            input_ids=input_ids,
            prompt_input_ids=prompt_input_ids,
        )

        audio = generation.cpu().numpy().squeeze()
        return audio, self.config.sample_rate

    def synthesize_for_immediate(self, text: str) -> np.ndarray:
        """Synthesize and cache an immediate response.

        These are pre-generated at startup for < 100ms playback.
        """
        audio, sr = self.synthesize(text)

        # Resample to 16kHz for consistency with rest of pipeline
        if sr != 16000:
            import torchaudio.transforms as T

            resampler = T.Resample(sr, 16000)
            audio = resampler(torch.from_numpy(audio)).numpy()

        self._cache[text] = audio
        return audio

    def get_cached(self, text: str) -> np.ndarray | None:
        """Get cached audio if available."""
        return self._cache.get(text)

    async def synthesize_async(
        self,
        text: str,
        description: str | None = None,
    ) -> tuple[np.ndarray, int]:
        """Async wrapper for synthesis."""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            None,
            lambda: self.synthesize(text, description),
        )

    def preload_immediate_responses(self, responses: list[str]) -> None:
        """Pre-generate and cache immediate responses.

        Call this at startup to ensure < 100ms responses for common phrases.
        """
        print(f"Pre-caching {len(responses)} immediate responses...")
        start = time.time()

        for text in responses:
            if text not in self._cache:
                self.synthesize_for_immediate(text)

        elapsed = time.time() - start
        print(f"Cached {len(self._cache)} responses in {elapsed:.1f}s")


# Convenience function
def create_tts(config: TTSConfig | None = None) -> ParlerTTS:
    """Create a TTS instance."""
    return ParlerTTS(config)
