"""Explicit routing between the branded and customer-specific voice engines."""

from __future__ import annotations

from collections.abc import AsyncIterator
from enum import StrEnum
from typing import Any

from .kokoro_engine import SynthesizedAudioChunk
from .qwen3_tts_engine import VoiceCloneProfile


class VoiceMode(StrEnum):
    BRANDED = "branded"
    CLONED = "cloned"


class DualVoiceRouter:
    """Route each call to one declared engine with no implicit downgrade."""

    def __init__(self, branded_engine: Any, clone_engine: Any | None = None) -> None:
        self.branded_engine = branded_engine
        self.clone_engine = clone_engine
        self._mode = VoiceMode.BRANDED
        self._clone_profile_id: str | None = None

    @property
    def mode(self) -> VoiceMode:
        return self._mode

    def initialize(self) -> None:
        initialize = getattr(self.branded_engine, "initialize", None)
        if callable(initialize):
            initialize()
        if self._mode is VoiceMode.CLONED:
            self._require_clone_engine().initialize()

    def use_branded_voice(self) -> None:
        self._mode = VoiceMode.BRANDED
        self._clone_profile_id = None

    def use_cloned_voice(self, profile: VoiceCloneProfile | str) -> None:
        engine = self._require_clone_engine()
        profile_id = profile if isinstance(profile, str) else profile.profile_id
        if not isinstance(profile, str):
            engine.register_voice(profile)
        engine.select_voice(profile_id)
        self._clone_profile_id = profile_id
        self._mode = VoiceMode.CLONED

    async def synthesize_stream_chunks(
        self, text: str
    ) -> AsyncIterator[SynthesizedAudioChunk]:
        engine = (
            self.branded_engine
            if self._mode is VoiceMode.BRANDED
            else self._require_clone_engine()
        )
        if self._mode is VoiceMode.CLONED:
            async for chunk in engine.synthesize_stream_chunks(
                text, profile_id=self._clone_profile_id
            ):
                yield chunk
            return
        async for chunk in engine.synthesize_stream_chunks(text):
            yield chunk

    async def synthesize_stream(self, text: str) -> AsyncIterator[bytes]:
        async for chunk in self.synthesize_stream_chunks(text):
            yield chunk.pcm

    def _require_clone_engine(self) -> Any:
        if self.clone_engine is None:
            raise RuntimeError(
                "cloned voice requested but the Qwen3-TTS engine is not configured"
            )
        return self.clone_engine
