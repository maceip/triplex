"""Gatekeeper model wrapper.

Uses the Cosmo audio_gatekeeper_edgetpu.tflite to score audio.

Input: mel spectrogram [1, T, 128]
Output: 13 sigmoid scores (isInteresting + 12 intents)

The scores tell us if audio is "interesting" (directed at assistant)
and what intent it contains - all without running ASR.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

# Head order from the Cosmo/inherent contract
HEAD_ORDER = (
    "isInteresting",
    "hasAddToListIntent",
    "hasTermSearchQuery",
    "hasPhotoQuery",
    "hasCalendarEvent",
    "hasCreateDocIntent",
    "hasPersonContext",
    "hasEventContext",
    "hasDeepResearchIntent",
    "hasInsightIntent",
    "hasBrowsingAgentIntent",
    "hasCallingAgentIntent",
    "hasStartTimerIntent",
)

# Default thresholds from Inherent (may need recalibration for Cosmo model)
DEFAULT_THRESHOLDS = {
    "isInteresting": 0.57,
    "hasAddToListIntent": 0.65,
    "hasTermSearchQuery": 0.90,
    "hasPhotoQuery": 0.90,
    "hasCalendarEvent": 0.90,
    "hasCreateDocIntent": 0.90,
    "hasPersonContext": 0.56,
    "hasEventContext": 0.74,
    "hasDeepResearchIntent": 0.90,
    "hasInsightIntent": 0.90,
    "hasBrowsingAgentIntent": 0.90,
    "hasCallingAgentIntent": 0.90,
    "hasStartTimerIntent": 0.90,
}


@dataclass
class GateScores:
    """Output from the gatekeeper."""

    scores: np.ndarray  # [13] raw sigmoid scores

    @property
    def is_interesting(self) -> float:
        """Is this audio directed at the assistant?"""
        return float(self.scores[0])

    @property
    def intents(self) -> dict[str, float]:
        """Intent scores (indices 1-12)."""
        return {HEAD_ORDER[i]: float(self.scores[i]) for i in range(1, 13)}

    def check(self, head: str, threshold: float | None = None) -> bool:
        """Check if a score is above threshold."""
        if threshold is None:
            threshold = DEFAULT_THRESHOLDS.get(head, 0.5)

        idx = HEAD_ORDER.index(head)
        return float(self.scores[idx]) >= threshold

    def top_intents(self, n: int = 3) -> list[tuple[str, float]]:
        """Get top N intents by score."""
        intent_scores = [(HEAD_ORDER[i], float(self.scores[i])) for i in range(1, 13)]
        return sorted(intent_scores, key=lambda x: x[1], reverse=True)[:n]

    def __repr__(self) -> str:
        return f"GateScores(is_interesting={self.is_interesting:.3f}, top={self.top_intents(2)})"


class Gatekeeper:
    """Wrapper for the Cosmo/Inherent gatekeeper model.

    Usage:
        gate = Gatekeeper()  # Uses default model path
        mel = frontend.extract_mel(audio)  # [T, 128]
        scores = gate.score(mel)
        if scores.is_interesting > 0.5:
            # Send to LLM
    """

    MAX_FRAMES = 3000  # From runtime contract

    def __init__(
        self,
        model_path: str | Path | None = None,
    ):
        self.model_path = Path(model_path) if model_path else self._find_model()
        self._session = None
        self._backend = None

    def _find_model(self) -> Path:
        """Find the gatekeeper model."""
        # Try inherent ONNX exports first (work on Python 3.14)
        inherent_onnx_candidates = [
            Path("/Users/mac/inherent/artifacts/static3000-smoke-export/inherent.onnx"),
            Path("/Users/mac/inherent/artifacts/local-smoke-export2/inherent.onnx"),
        ]
        for p in inherent_onnx_candidates:
            if p.exists():
                return p

        # Fall back to Cosmo TFLite (requires tensorflow, Python <3.14)
        cosmo_candidates = [
            Path("/Users/mac/Downloads/cosmo_recovered_large_assets/assets__gatekeeper__audio_gatekeeper_edgetpu.tflite"),
        ]
        for p in cosmo_candidates:
            if p.exists():
                return p

        raise FileNotFoundError(
            "Gatekeeper model not found. Looking for:\n"
            f"  ONNX: {inherent_onnx_candidates}\n"
            f"  TFLite: {cosmo_candidates}"
        )

    def load(self) -> None:
        """Load the model into memory."""
        # Check extension to decide backend
        if self.model_path.suffix == ".onnx":
            import onnxruntime as ort

            self._session = ort.InferenceSession(str(self.model_path))
            self._backend = "onnx"
            return

        # TFLite backend
        try:
            import tensorflow as tf

            self._session = tf.lite.Interpreter(model_path=str(self.model_path))
            self._session.allocate_tensors()
            self._backend = "tflite"
            return
        except ImportError:
            pass

        raise ImportError(
            "No model backend available.\n"
            "For ONNX: pip install onnxruntime\n"
            "For TFLite: pip install tensorflow (Python <3.14)"
        )

    def score(self, mel: np.ndarray) -> GateScores:
        """Score mel spectrogram through the gatekeeper.

        Args:
            mel: Mel spectrogram [T, 128] or [1, T, 128]

        Returns:
            GateScores with isInteresting and intent scores
        """
        if self._session is None:
            self.load()

        # Ensure correct shape [1, T, 128]
        if mel.ndim == 2:
            mel = mel[np.newaxis, :, :]

        # Pad or truncate to MAX_FRAMES
        n_frames = mel.shape[1]
        if n_frames < self.MAX_FRAMES:
            # Pad with zeros
            pad = np.zeros((1, self.MAX_FRAMES - n_frames, 128), dtype=np.float32)
            mel = np.concatenate([mel, pad], axis=1)
        elif n_frames > self.MAX_FRAMES:
            # Truncate
            mel = mel[:, : self.MAX_FRAMES, :]

        # Ensure float32
        mel = mel.astype(np.float32)

        # Run inference
        if self._backend == "onnx":
            input_name = self._session.get_inputs()[0].name
            output = self._session.run(None, {input_name: mel})
            scores = output[0].squeeze()
        elif self._backend == "tflite":
            input_details = self._session.get_input_details()
            output_details = self._session.get_output_details()
            self._session.set_tensor(input_details[0]["index"], mel)
            self._session.invoke()
            scores = self._session.get_tensor(output_details[0]["index"]).squeeze()
        else:
            raise RuntimeError(f"Unknown backend: {self._backend}")

        return GateScores(scores=scores)

    def is_interesting(self, mel: np.ndarray, threshold: float = 0.5) -> bool:
        """Quick check if audio is interesting."""
        return self.score(mel).is_interesting >= threshold
