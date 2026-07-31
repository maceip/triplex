#!/usr/bin/env python3
"""Demo script for the voice pipeline.

Runs the pipeline with mock components to show the flow.
"""

import asyncio
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent))

from src.triplex.pipeline import VoicePipeline, PipelineConfig, LatencyMetrics


async def mock_audio_stream(duration_seconds: float = 3.0):
    """Generate mock audio chunks simulating speech + silence.

    Simulates:
    - 2 seconds of "speech" (non-silent audio)
    - 0.5 seconds of silence (triggers end-of-utterance)
    """
    sample_rate = 16000
    chunk_ms = 20
    chunk_samples = int(sample_rate * chunk_ms / 1000)
    chunk_bytes = chunk_samples * 2  # 16-bit

    # Speech-like audio (non-zero to trigger VAD)
    num_speech_chunks = int(2.0 * 1000 / chunk_ms)
    for i in range(num_speech_chunks):
        # Generate sine wave pattern (looks like speech to simple VAD)
        import struct
        import math

        samples = []
        for j in range(chunk_samples):
            # Mix of frequencies to look like speech
            t = (i * chunk_samples + j) / sample_rate
            sample = int(
                16000
                * (
                    0.3 * math.sin(2 * math.pi * 440 * t)
                    + 0.2 * math.sin(2 * math.pi * 880 * t)
                    + 0.1 * math.sin(2 * math.pi * 1320 * t)
                )
            )
            samples.append(sample)

        yield struct.pack(f"<{len(samples)}h", *samples)

    # Silence to trigger end-of-utterance
    num_silence_chunks = int(0.5 * 1000 / chunk_ms) + 20  # Extra silence
    for _ in range(num_silence_chunks):
        yield b"\x00" * chunk_bytes


async def main():
    """Run the pipeline demo."""
    print("=" * 60)
    print("TRIPLEX VOICE AGENT - PIPELINE DEMO")
    print("=" * 60)
    print()

    # Use mock LLM and ASR (no GPU required)
    config = PipelineConfig(
        use_mock_llm=True,
        use_mock_asr=True,
    )

    print("Configuration:")
    print(f"  • Target latency: {config.target_total_latency_ms}ms")
    print(f"  • VAD backend: {config.vad_backend}")
    print(f"  • Using mock LLM: {config.use_mock_llm}")
    print(f"  • Using mock ASR: {config.use_mock_asr}")
    print()

    pipeline = VoicePipeline(config)
    pipeline.initialize()

    print("Pipeline initialized. Starting audio stream...")
    print("-" * 60)

    turn_count = 0
    async for audio_chunk, metrics in pipeline.process_audio_stream(mock_audio_stream()):
        turn_count += 1
        print(f"\nTurn {turn_count}:")
        print(f"  Audio output: {len(audio_chunk)} bytes")
        print(f"  Latency breakdown:")
        print(f"    VAD:    {metrics.vad_ms:.1f}ms")
        print(f"    LLM:    {metrics.llm_first_token_ms:.1f}ms")
        print(f"    TTS:    {metrics.tts_first_chunk_ms:.1f}ms")
        print(f"    TOTAL:  {metrics.total_ms:.1f}ms")
        print(f"  Target met: {'✅ YES' if metrics.total_ms < config.target_total_latency_ms else '❌ NO'}")

    print()
    print("-" * 60)
    print("Demo complete!")
    print()
    print("Status:")
    print("  ✅ Pipeline architecture wired")
    print("  ✅ VAD (Silero) integrated")
    print("  ✅ LLM (vLLM/Llama 3) scaffolded")
    print("  ✅ TTS (Kokoro-82M) integrated")
    print("  ✅ ASR (Whisper) scaffolded")
    print()
    print("Next steps:")
    print("  1. Test with real Kokoro TTS (requires: pip install kokoro)")
    print("  2. Test with Whisper ASR (requires: pip install faster-whisper)")
    print("  3. Test with vLLM (requires: CUDA GPU + pip install vllm)")
    print("  4. Wire echo cancellation (AEC)")
    print("  5. Implement KVS consumer for Chime SDK")


if __name__ == "__main__":
    asyncio.run(main())
