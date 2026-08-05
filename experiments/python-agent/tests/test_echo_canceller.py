"""DSP regression tests for the streaming echo canceller."""

from __future__ import annotations

import numpy as np

from triplex.transport.echo_canceller import EchoCanceller, EchoCancellerConfig


def _pcm(audio: np.ndarray) -> bytes:
    return (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()


def test_adaptive_filter_removes_a_delayed_multi_path_echo() -> None:
    rng = np.random.default_rng(7)
    canceller = EchoCanceller(
        EchoCancellerConfig(
            filter_length_ms=20,
            adaptation_rate=0.2,
            double_talk_ratio=100.0,
        )
    )

    echo_path = np.zeros(320)
    echo_path[12] = 0.55
    echo_path[47] = 0.22
    far_tail = np.zeros(len(echo_path) - 1)
    input_powers: list[float] = []
    output_powers: list[float] = []

    for frame_index in range(180):
        far_end = rng.normal(0.0, 0.2, 320)
        reference_window = np.concatenate((far_tail, far_end))
        microphone = np.convolve(reference_window, echo_path, mode="valid")
        far_tail = reference_window[-(len(echo_path) - 1) :]

        cleaned = np.frombuffer(
            canceller.process(_pcm(microphone), _pcm(far_end)), dtype="<i2"
        ).astype(np.float64) / 32768.0

        if frame_index >= 140:
            input_powers.append(float(np.mean(microphone * microphone)))
            output_powers.append(float(np.mean(cleaned * cleaned)))

    erle_db = 10.0 * np.log10(np.mean(input_powers) / np.mean(output_powers))
    assert erle_db > 20.0
    assert canceller.last_metrics.adapted


def test_reference_fifo_pairs_output_with_microphone_frames() -> None:
    canceller = EchoCanceller(EchoCancellerConfig(filter_length_ms=20))
    far_end = np.linspace(-0.2, 0.2, 640)
    canceller.push_reference(_pcm(far_end))

    canceller.process(_pcm(far_end[:320]))

    assert canceller.queued_reference_samples == 320
    canceller.clear_reference()
    assert canceller.queued_reference_samples == 0


def test_near_end_speech_freezes_adaptation_during_double_talk() -> None:
    rng = np.random.default_rng(11)
    canceller = EchoCanceller(
        EchoCancellerConfig(
            filter_length_ms=20,
            double_talk_ratio=1.5,
            double_talk_correlation=0.3,
        )
    )
    quiet_far_end = rng.normal(0.0, 0.03, 320)
    loud_near_end = rng.normal(0.0, 0.3, 320)

    canceller.process(_pcm(loud_near_end), _pcm(quiet_far_end))

    assert canceller.last_metrics.double_talk
    assert not canceller.last_metrics.adapted
