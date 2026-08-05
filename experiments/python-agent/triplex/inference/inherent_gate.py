"""Integration with the Inherent audio gate from the inherent project.

The Inherent model provides fast on-device filtering to determine
if audio is "interesting" (directed at the assistant) and what
intent it contains, all without running ASR.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import numpy as np
import torch

# Import from inherent if available (same machine)
try:
    import inherent
    from inherent import HEAD_ORDER, NUM_HEADS, DEFAULT_THRESHOLDS

    INHERENT_AVAILABLE = True
except ImportError:
    INHERENT_AVAILABLE = False
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
    NUM_HEADS = 13
    DEFAULT_THRESHOLDS = (0.57, 0.65, 0.90, 0.90, 0.90, 0.90, 0.56, 0.74, 0.90, 0.90, 0.90, 0.90, 0.90)


@dataclass
class InherentScores:
    """Output from the Inherent gate."""

    is_interesting: float
    intents: dict[str, float]
    raw_scores: np.ndarray

    @classmethod
    def from_array(cls, scores: np.ndarray) -> "InherentScores":
        """Create from raw score array."""
        if len(scores) != NUM_HEADS:
            raise ValueError(f"Expected {NUM_HEADS} scores, got {len(scores)}")

        intent_scores = {HEAD_ORDER[i + 1]: scores[i + 1] for i in range(NUM_HEADS - 1)}

        return cls(
            is_interesting=scores[0],
            intents=intent_scores,
            raw_scores=scores,
        )

    def is_above_threshold(self, head: str, threshold: float | None = None) -> bool:
        """Check if a head is above its threshold."""
        if threshold is None:
            # Use default threshold
            idx = HEAD_ORDER.index(head)
            threshold = DEFAULT_THRESHOLDS[idx]

        if head == "isInteresting":
            return self.is_interesting >= threshold
        return self.intents.get(head, 0.0) >= threshold


class InherentGate:
    """Wrapper for the Inherent audio gate model.

    This is the key innovation over Duplex 2018:
    - No need to run ASR on every audio chunk
    - Fast filtering (2-6ms on device)
    - Intent detection in one pass

    Usage:
        gate = InherentGate("/path/to/inherent.tflite")
        scores = gate.score(mel_spectrogram)
        if scores.is_above_threshold("isInteresting"):
            # Process with LLM
    """

    def __init__(
        self,
        model_path: str | Path | None = None,
        backend: Literal["onnx", "litert", "mlx", "pytorch"] = "onnx",
    ):
        self.model_path = Path(model_path) if model_path else None
        self.backend = backend
        self._session = None

        # Default thresholds
        self.thresholds = dict(zip(HEAD_ORDER, DEFAULT_THRESHOLDS))

    def load(self) -> None:
        """Load the model for inference."""
        if self.backend == "onnx":
            self._load_onnx()
        elif self.backend == "pytorch":
            self._load_pytorch()
        else:
            raise ValueError(f"Unsupported backend: {self.backend}")

    def _load_onnx(self) -> None:
        """Load ONNX model."""
        import onnxruntime as ort

        if self.model_path is None:
            raise ValueError("model_path required for ONNX backend")

        self._session = ort.InferenceSession(str(self.model_path))

    def _load_pytorch(self) -> None:
        """Load PyTorch model from inherent project."""
        if not INHERENT_AVAILABLE:
            raise ImportError(
                "Inherent package not available. "
                "Install from ~/inherent or use ONNX backend."
            )

        # Load from inherent checkpoint
        # This would use the inherent.models.architecture module
        raise NotImplementedError("PyTorch backend not yet implemented")

    def score(
        self,
        mel: np.ndarray | torch.Tensor,
        threshold: float | None = None,
    ) -> InherentScores:
        """Score a mel spectrogram through the gate.

        Args:
            mel: Mel spectrogram [1, T, 128] or [T, 128]
            threshold: Optional threshold override

        Returns:
            InherentScores with isInteresting and intent scores
        """
        if self._session is None:
            self.load()

        # Ensure correct shape
        if isinstance(mel, torch.Tensor):
            mel = mel.numpy()

        if mel.ndim == 2:
            mel = mel[np.newaxis, :, :]

        # Pad to expected input size if needed
        # Runtime contract: max 3000 frames (~60 seconds at 16kHz)
        if mel.shape[1] < 3000:
            pad_width = ((0, 0), (0, 3000 - mel.shape[1]), (0, 0))
            mel = np.pad(mel, pad_width, mode="constant")
        elif mel.shape[1] > 3000:
            mel = mel[:, :3000, :]

        # Run inference
        if self.backend == "onnx":
            input_name = self._session.get_inputs()[0].name
            output = self._session.run(None, {input_name: mel.astype(np.float32)})
            scores = output[0].squeeze()
        else:
            raise ValueError(f"Unknown backend: {self.backend}")

        return InherentScores.from_array(scores)

    def is_relevant_speech(self, mel: np.ndarray | torch.Tensor) -> bool:
        """Quick check if audio contains relevant speech.

        This is the main gating function - if False, skip ASR/LLM.
        """
        scores = self.score(mel)
        return scores.is_above_threshold("isInteresting")


# Convenience function for direct use
def check_audio_interesting(
    mel: np.ndarray | torch.Tensor,
    model_path: str | Path | None = None,
) -> tuple[bool, InherentScores]:
    """Check if audio is interesting (directed at assistant).

    Returns:
        Tuple of (is_interesting, full_scores)
    """
    gate = InherentGate(model_path)
    scores = gate.score(mel)
    return scores.is_above_threshold("isInteresting"), scores
