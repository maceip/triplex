"""Triplex speech synthesis engines and explicit voice routing."""

from .dual_router import DualVoiceRouter, VoiceMode
from .kokoro_engine import KokoroConfig, KokoroEngine, SynthesizedAudioChunk
from .qwen3_tts_engine import (
    Qwen3TTSConfig,
    Qwen3TTSEngine,
    RegisteredVoiceClone,
    VoiceCloneProfile,
)

__all__ = [
    "DualVoiceRouter",
    "KokoroConfig",
    "KokoroEngine",
    "Qwen3TTSConfig",
    "Qwen3TTSEngine",
    "RegisteredVoiceClone",
    "SynthesizedAudioChunk",
    "VoiceCloneProfile",
    "VoiceMode",
]
