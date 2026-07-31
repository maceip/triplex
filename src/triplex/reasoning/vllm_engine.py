"""vLLM reasoning engine for Llama 3 8B.

Fast inference with streaming token generation.

Requirements:
- vLLM>=0.6.0
- CUDA GPU (or Apple Silicon with MPS fallback)
- Llama 3 8B weights

Install: pip install vllm
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import AsyncIterator

try:
    from vllm import LLM, SamplingParams
    from vllm.engine.arg_utils import EngineArgs
    from vllm.engine.async_llm_engine import AsyncLLMEngine

    HAS_VLLM = True
except ImportError:
    HAS_VLLM = False

    # Stubs for type hints when vLLM not installed
    class SamplingParams:
        pass


@dataclass
class ReasoningConfig:
    """Configuration for the reasoning engine."""

    model: str = "meta-llama/Meta-Llama-3-8B-Instruct"
    max_tokens: int = 150  # Voice responses should be concise
    temperature: float = 0.7
    top_p: float = 0.9
    repetition_penalty: float = 1.1

    # Performance tuning
    gpu_memory_utilization: float = 0.9
    max_model_len: int = 4096  # Context window

    # System prompt for voice agent
    system_prompt: str = """You are a helpful voice assistant. Keep responses:
- Concise (1-3 sentences for most responses)
- Conversational and natural
- Direct without hedging

You're speaking on a phone call, so avoid:
- Long lists or complex explanations
- Reading out URLs or codes unless asked
- Saying "um" or filler words (the TTS handles that)"""


@dataclass
class TokenChunk:
    """Streaming token chunk with metadata."""

    text: str
    is_final: bool = False
    tokens_generated: int = 0
    latency_ms: float = 0.0


class VLLMEngine:
    """vLLM-powered reasoning for voice responses.

    Features:
    - Streaming token generation (tokens arrive as generated)
    - Async API for non-blocking operation
    - Low latency via PagedAttention

    Latency targets:
    - First token: <100ms
    - Tokens/sec: 50-100+ on GPU
    """

    def __init__(self, config: ReasoningConfig | None = None):
        if not HAS_VLLM:
            raise ImportError(
                "vLLM required: pip install vllm\n"
                "Requires CUDA GPU or Apple Silicon with MPS support."
            )

        self.config = config or ReasoningConfig()
        self._engine: AsyncLLMEngine | None = None
        self._initialized = False

    def initialize(self) -> None:
        """Initialize vLLM engine (expensive, do once)."""
        if self._initialized:
            return

        engine_args = EngineArgs(
            model=self.config.model,
            gpu_memory_utilization=self.config.gpu_memory_utilization,
            max_model_len=self.config.max_model_len,
            # Disable logging for cleaner output
            disable_log_stats=True,
            disable_log_requests=True,
        )

        self._engine = AsyncLLMEngine.from_engine_args(engine_args)
        self._initialized = True

    async def generate_stream(
        self,
        prompt: str,
        conversation_history: list[dict] | None = None,
    ) -> AsyncIterator[TokenChunk]:
        """Generate response tokens as a stream.

        Args:
            prompt: User's transcribed speech
            conversation_history: Previous turns [{"role": "user/assistant", "content": "..."}]

        Yields:
            TokenChunk objects with text and metadata
        """
        if not self._initialized:
            self.initialize()

        # Build message sequence
        messages = [{"role": "system", "content": self.config.system_prompt}]

        if conversation_history:
            messages.extend(conversation_history)

        messages.append({"role": "user", "content": prompt})

        # Apply chat template
        # Llama 3 uses specific formatting
        formatted_prompt = self._format_chat(messages)

        sampling_params = SamplingParams(
            max_tokens=self.config.max_tokens,
            temperature=self.config.temperature,
            top_p=self.config.top_p,
            repetition_penalty=self.config.repetition_penalty,
        )

        start_time = time.perf_counter()
        tokens_generated = 0

        try:
            # Stream results
            async for output in self._engine.generate(  # type: ignore
                formatted_prompt, sampling_params, request_id=str(time.time())
            ):
                # output is a RequestOutput
                if output.outputs:
                    text = output.outputs[0].text
                    tokens_generated = len(output.outputs[0].token_ids)

                    elapsed_ms = (time.perf_counter() - start_time) * 1000

                    yield TokenChunk(
                        text=text,
                        is_final=output.finished,
                        tokens_generated=tokens_generated,
                        latency_ms=elapsed_ms,
                    )
        except Exception as e:
            # On error, yield nothing - caller should handle
            raise RuntimeError(f"LLM generation failed: {e}") from e

    async def generate(self, prompt: str, conversation_history: list[dict] | None = None) -> str:
        """Generate complete response (non-streaming convenience method)."""
        complete_text = ""
        async for chunk in self.generate_stream(prompt, conversation_history):
            complete_text = chunk.text
        return complete_text

    def _format_chat(self, messages: list[dict]) -> str:
        """Format messages using Llama 3 chat template.

        Llama 3 format:
        <|begin_of_text|><|start_header_id|>system<|end_header_id|>
        {system}<|eot_id|><|start_header_id|>user<|end_header_id|>
        {user}<|eot_id|><|start_header_id|>assistant<|end_header_id|>
        """
        formatted = "<|begin_of_text|>"

        for msg in messages:
            role = msg["role"]
            content = msg["content"]

            formatted += f"<|start_header_id|>{role}<|end_header_id|>\n\n"
            formatted += content
            formatted += "<|eot_id|>"

        # Start assistant's turn
        formatted += "<|start_header_id|>assistant<|end_header_id|>\n\n"

        return formatted

    async def abort(self) -> None:
        """Abort current generation (for interruption handling)."""
        # vLLM doesn't have a clean abort API per request
        # The engine handles this internally when we stop iterating
        pass

    def teardown(self) -> None:
        """Cleanup engine resources."""
        # vLLM handles cleanup on exit
        self._initialized = False


# Fallback for development/testing without vLLM
class MockReasoningEngine:
    """Mock engine for testing without GPU/vLLM."""

    def __init__(self, config: ReasoningConfig | None = None):
        self.config = config or ReasoningConfig()

    async def generate_stream(
        self, prompt: str, conversation_history: list[dict] | None = None
    ) -> AsyncIterator[TokenChunk]:
        """Fake streaming with pre-canned responses."""
        await asyncio.sleep(0.1)  # Simulate latency

        responses = {
            "hello": "Hi there! How can I help you today?",
            "how are you": "I'm doing well, thanks for asking!",
            "bye": "Goodbye, have a great day!",
        }

        # Default response
        response = responses.get(
            prompt.lower().strip(),
            "I understand. Let me help you with that.",
        )

        # Simulate streaming
        words = response.split()
        text = ""

        for i, word in enumerate(words):
            await asyncio.sleep(0.02)  # Simulate token generation time
            text += (" " if text else "") + word

            yield TokenChunk(
                text=text,
                is_final=(i == len(words) - 1),
                tokens_generated=i + 1,
                latency_ms=(i + 1) * 20,
            )

    async def generate(self, prompt: str, conversation_history: list[dict] | None = None) -> str:
        """Non-streaming generate."""
        final_text = ""
        async for chunk in self.generate_stream(prompt, conversation_history):
            final_text = chunk.text
        return final_text


def get_reasoning_engine(use_mock: bool = False) -> VLLMEngine | MockReasoningEngine:
    """Factory function to get appropriate engine.

    Args:
        use_mock: Force mock engine (for testing without GPU)

    Returns:
        VLLMEngine if vLLM installed, MockReasoningEngine otherwise
    """
    if use_mock or not HAS_VLLM:
        return MockReasoningEngine()
    return VLLMEngine()
