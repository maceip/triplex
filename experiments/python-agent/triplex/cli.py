#!/usr/bin/env python3
"""Triplex prototype - audio in → gatekeeper → LLM → response out.

This is the main entry point for testing the pipeline.

Usage:
    python -m triplex.cli --audio input.wav
    python -m triplex.cli --mic  # Live microphone input
    python -m triplex.cli --test  # Quick test with generated audio
"""

from __future__ import annotations

import argparse
import asyncio
import time
from pathlib import Path

import numpy as np

from triplex.gate.frontend import AudioFrontend, load_audio
from triplex.gate.gatekeeper import Gatekeeper, GateScores
from triplex.llm.voice_client import create_voice_llm, VoiceLLM


async def process_audio(
    audio: np.ndarray,
    frontend: AudioFrontend,
    gatekeeper: Gatekeeper,
    llm: VoiceLLM | None = None,
    threshold: float = 0.5,
) -> tuple[GateScores, str | None, float]:
    """Process audio through the full pipeline.

    Returns:
        Tuple of (gate_scores, llm_response_text, total_latency_ms)
    """
    start = time.perf_counter()

    # Step 1: Extract mel spectrogram
    mel = frontend.extract_mel(audio)
    mel_time = time.perf_counter()
    mel_latency_ms = (mel_time - start) * 1000

    # Step 2: Run through gatekeeper
    scores = gatekeeper.score(mel)
    gate_time = time.perf_counter()
    gate_latency_ms = (gate_time - mel_time) * 1000

    # Step 3: If interesting, send to LLM
    response_text = None
    llm_latency_ms = 0.0

    if scores.is_interesting >= threshold:
        if llm is not None:
            # Transcribe and respond
            response = await llm.respond(audio=audio)
            response_text = response.text
            llm_time = time.perf_counter()
            llm_latency_ms = (llm_time - gate_time) * 1000
        else:
            response_text = "[LLM not configured]"

    total_latency_ms = (time.perf_counter() - start) * 1000

    return scores, response_text, total_latency_ms


async def main():
    parser = argparse.ArgumentParser(description="Triplex voice agent prototype")
    parser.add_argument("--audio", type=str, help="Path to audio file")
    parser.add_argument("--mic", action="store_true", help="Use microphone input")
    parser.add_argument("--test", action="store_true", help="Run quick test")
    parser.add_argument("--llm", type=str, default="mock", help="LLM backend (mock, openai, gemini)")
    parser.add_argument("--threshold", type=float, default=0.5, help="isInteresting threshold")
    parser.add_argument("--model", type=str, help="Path to gatekeeper model")

    args = parser.parse_args()

    print("=" * 60)
    print("TRIPLEX PROTOTYPE")
    print("=" * 60)
    print()

    # Initialize components
    print("Loading frontend...")
    frontend = AudioFrontend()

    print("Loading gatekeeper...")
    gatekeeper = Gatekeeper(model_path=args.model)
    # Don't load yet - defer until first use

    print(f"Initializing LLM ({args.llm})...")
    llm = create_voice_llm(backend=args.llm)

    print()

    # Get audio input
    if args.test:
        print("Generating test audio (1 second of silence)...")
        audio = np.zeros(16000, dtype=np.float32)
    elif args.audio:
        print(f"Loading audio from {args.audio}...")
        audio = load_audio(args.audio)
    elif args.mic:
        print("Microphone input not yet implemented.")
        print("Use --audio <file> or --test for now.")
        return
    else:
        print("No input specified. Use --audio, --mic, or --test")
        return

    duration = len(audio) / 16000
    print(f"Audio duration: {duration:.2f}s")
    print()

    # Process
    print("Processing...")
    scores, response, total_ms = await process_audio(
        audio=audio,
        frontend=frontend,
        gatekeeper=gatekeeper,
        llm=llm,
        threshold=args.threshold,
    )

    # Results
    print()
    print("-" * 40)
    print("RESULTS")
    print("-" * 40)
    print(f"Gatekeeper scores:")
    print(f"  isInteresting: {scores.is_interesting:.4f}")
    print(f"  Top intents: {scores.top_intents(3)}")
    print()
    print(f"Latency: {total_ms:.1f}ms total")
    print()

    if scores.is_interesting >= args.threshold:
        print(f"→ Audio IS interesting (>{args.threshold})")
        print(f"→ LLM response: {response}")
    else:
        print(f"→ Audio NOT interesting (<{args.threshold})")
        print("→ Skipped LLM call")

    print()
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
