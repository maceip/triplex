# SUPERSEDED-BY: RUNTIME_INVARIANTS.md §7.4, §3.2
# RETIRED: this module does not ship and is not maintained.
# Its contract was ported; see DISPOSITION_LEDGER.md. Read for
# reference only — do not extend, and do not treat as a fallback.

"""Streaming acoustic echo cancellation for full-duplex calls.

The canceller consumes the microphone signal (near end) together with the audio
that Triplex sent to the caller (far end).  It uses a block normalized least
mean squares adaptive filter, which is a real echo canceller rather than an
energy gate: the filter learns the call's delay/gain/room impulse response and
subtracts only that correlated component from the microphone signal.

WebRTC AEC3 can be added as a backend later without changing the streaming
contract.  The built-in NLMS backend keeps the core path deterministic and
available on both Apple Silicon development machines and Linux call workers.
"""

from __future__ import annotations

import threading
from collections import deque
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class EchoCancellerConfig:
    """DSP and stream settings for :class:`EchoCanceller`."""

    sample_rate: int = 16000
    frame_duration_ms: int = 20
    filter_length_ms: int = 80
    delay_ms: int = 0
    adaptation_rate: float = 0.35
    leakage: float = 1e-5
    epsilon: float = 1e-7
    double_talk_ratio: float = 2.5
    double_talk_correlation: float = 0.25

    @property
    def frame_samples(self) -> int:
        return self.sample_rate * self.frame_duration_ms // 1000

    @property
    def filter_samples(self) -> int:
        return max(8, self.sample_rate * self.filter_length_ms // 1000)

    @property
    def delay_samples(self) -> int:
        return max(0, self.sample_rate * self.delay_ms // 1000)


@dataclass(frozen=True)
class EchoMetrics:
    """Measurements from the most recently processed frame."""

    input_power: float
    reference_power: float
    residual_power: float
    echo_return_loss_enhancement_db: float
    adapted: bool
    double_talk: bool


class EchoCanceller:
    """Adaptive full-duplex echo canceller operating on signed 16-bit PCM.

    ``push_reference`` is called when output audio is committed to the playout
    device. ``process`` is called for each microphone frame.  The methods may
    be called from different threads; access to the far-end FIFO and adaptive
    coefficients is serialized.
    """

    def __init__(self, config: EchoCancellerConfig | None = None) -> None:
        self.config = config or EchoCancellerConfig()
        self._weights = np.zeros(self.config.filter_samples, dtype=np.float64)
        self._reference_tail = np.zeros(
            self.config.filter_samples - 1, dtype=np.float64
        )
        self._delay_line = np.zeros(self.config.delay_samples, dtype=np.float64)
        self._reference_frames: deque[np.ndarray] = deque()
        self._reference_offset = 0
        self._lock = threading.Lock()
        self._last_metrics = EchoMetrics(0.0, 0.0, 0.0, 0.0, False, False)

    @property
    def last_metrics(self) -> EchoMetrics:
        """Return measurements from the most recently processed frame."""
        return self._last_metrics

    @property
    def queued_reference_samples(self) -> int:
        """Number of far-end samples waiting to be paired with near-end input."""
        with self._lock:
            if not self._reference_frames:
                return 0
            return sum(len(frame) for frame in self._reference_frames) - self._reference_offset

    def push_reference(self, pcm: bytes | np.ndarray) -> None:
        """Queue far-end audio after it has been committed to playout."""
        reference = _to_float_audio(pcm)
        if reference.size == 0:
            return
        with self._lock:
            self._reference_frames.append(reference)

    def clear_reference(self) -> None:
        """Discard queued far-end audio after an interruption flush."""
        with self._lock:
            self._reference_frames.clear()
            self._reference_offset = 0
            self._delay_line = np.zeros(self.config.delay_samples, dtype=np.float64)

    def reset(self) -> None:
        """Reset the learned echo path and all queued stream state."""
        with self._lock:
            self._weights.fill(0.0)
            self._reference_tail.fill(0.0)
            self._reference_frames.clear()
            self._reference_offset = 0
            self._delay_line = np.zeros(self.config.delay_samples, dtype=np.float64)
            self._last_metrics = EchoMetrics(0.0, 0.0, 0.0, 0.0, False, False)

    def process(
        self,
        near_end_pcm: bytes | np.ndarray,
        far_end_pcm: bytes | np.ndarray | None = None,
    ) -> bytes:
        """Remove correlated far-end speech from one near-end PCM frame.

        A far-end frame can be supplied directly for deterministic pairing. If
        omitted, samples are consumed from the FIFO populated by
        :meth:`push_reference`; missing reference samples are treated as
        silence and never cause the microphone stream to stall.
        """
        near = _to_float_audio(near_end_pcm)
        if near.size == 0:
            return b""

        with self._lock:
            reference = (
                _to_float_audio(far_end_pcm)
                if far_end_pcm is not None
                else self._take_reference(len(near))
            )
            reference = _fit_length(reference, len(near))
            delayed_reference = self._apply_delay(reference)
            residual = self._process_float(near, delayed_reference)

        return _to_pcm16(residual)

    def _take_reference(self, sample_count: int) -> np.ndarray:
        output = np.zeros(sample_count, dtype=np.float64)
        written = 0

        while written < sample_count and self._reference_frames:
            frame = self._reference_frames[0]
            available = len(frame) - self._reference_offset
            take = min(sample_count - written, available)
            output[written : written + take] = frame[
                self._reference_offset : self._reference_offset + take
            ]
            written += take
            self._reference_offset += take
            if self._reference_offset == len(frame):
                self._reference_frames.popleft()
                self._reference_offset = 0

        return output

    def _apply_delay(self, reference: np.ndarray) -> np.ndarray:
        if self.config.delay_samples == 0:
            return reference

        combined = np.concatenate((self._delay_line, reference))
        delayed = combined[: len(reference)]
        self._delay_line = combined[len(reference) :]
        return delayed

    def _process_float(self, near: np.ndarray, reference: np.ndarray) -> np.ndarray:
        window = np.concatenate((self._reference_tail, reference))
        regressors = np.lib.stride_tricks.sliding_window_view(
            window, self.config.filter_samples
        )[:, ::-1]

        estimated_echo = regressors @ self._weights
        residual = near - estimated_echo

        near_power = float(np.mean(near * near))
        reference_power = float(np.mean(reference * reference))
        residual_power = float(np.mean(residual * residual))
        correlation = _normalized_correlation(near, reference, self.config.epsilon)
        double_talk = (
            reference_power > self.config.epsilon
            and near_power > reference_power * self.config.double_talk_ratio
            and abs(correlation) < self.config.double_talk_correlation
        )

        adapted = reference_power > self.config.epsilon and not double_talk
        if adapted:
            gradient = regressors.T @ residual
            normalization = (
                np.sum(regressors * regressors, axis=0) + self.config.epsilon
            )
            self._weights *= 1.0 - self.config.leakage
            self._weights += self.config.adaptation_rate * gradient / normalization

        self._reference_tail = window[-(self.config.filter_samples - 1) :].copy()

        erle = 10.0 * np.log10(
            (near_power + self.config.epsilon)
            / (residual_power + self.config.epsilon)
        )
        self._last_metrics = EchoMetrics(
            input_power=near_power,
            reference_power=reference_power,
            residual_power=residual_power,
            echo_return_loss_enhancement_db=float(erle),
            adapted=adapted,
            double_talk=double_talk,
        )
        return np.clip(residual, -1.0, 1.0)


def _to_float_audio(audio: bytes | np.ndarray | None) -> np.ndarray:
    if audio is None:
        return np.array([], dtype=np.float64)
    if isinstance(audio, bytes):
        if len(audio) % 2:
            raise ValueError("PCM byte length must be divisible by two")
        return np.frombuffer(audio, dtype="<i2").astype(np.float64) / 32768.0

    array = np.asarray(audio)
    if array.ndim != 1:
        raise ValueError("echo canceller accepts mono audio only")
    if np.issubdtype(array.dtype, np.integer):
        return array.astype(np.float64) / 32768.0
    return array.astype(np.float64, copy=False)


def _fit_length(audio: np.ndarray, length: int) -> np.ndarray:
    if len(audio) == length:
        return audio
    if len(audio) > length:
        return audio[:length]
    return np.pad(audio, (0, length - len(audio)))


def _to_pcm16(audio: np.ndarray) -> bytes:
    return bytes((np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes())


def _normalized_correlation(first: np.ndarray, second: np.ndarray, epsilon: float) -> float:
    denominator = float(np.linalg.norm(first) * np.linalg.norm(second)) + epsilon
    return float(np.dot(first, second) / denominator)
