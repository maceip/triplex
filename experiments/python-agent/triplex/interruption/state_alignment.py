# SUPERSEDED-BY: RUNTIME_INVARIANTS.md §7.3, §3.6
# RETIRED: this module does not ship and is not maintained.
# Its contract was ported; see DISPOSITION_LEDGER.md. Read for
# reference only — do not extend, and do not treat as a fallback.

"""Align generated assistant text with audio acknowledged by playout."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class SpeechSegment:
    """Text-to-audio mapping for one synthesizer output segment."""

    text: str
    samples: int
    start_sample: int

    @property
    def end_sample(self) -> int:
        return self.start_sample + self.samples


@dataclass
class GenerationAlignment:
    """Cumulative synthesis and playout state for one response."""

    generation_id: str
    segments: list[SpeechSegment] = field(default_factory=list)
    played_samples: int = 0

    @property
    def total_samples(self) -> int:
        return self.segments[-1].end_sample if self.segments else 0


class SpokenTextTracker:
    """Track the exact PCM boundary reached by the output AudioWorklet.

    Synthesizer chunks supply the text that generated each PCM segment.  When a
    barge-in flush reports its cumulative played-sample count, this tracker
    retains only completed synthesizer segments. Generated or partially played
    text never enters conversation history; this conservative boundary avoids
    inventing word timing that the synthesizer did not provide.
    """

    def __init__(self) -> None:
        self._generations: dict[str, GenerationAlignment] = {}

    def register_segment(self, generation_id: str, text: str, samples: int) -> None:
        if samples < 0:
            raise ValueError("speech segment sample count cannot be negative")
        alignment = self._generations.setdefault(
            generation_id, GenerationAlignment(generation_id)
        )
        alignment.segments.append(
            SpeechSegment(
                text=text,
                samples=samples,
                start_sample=alignment.total_samples,
            )
        )

    def acknowledge(self, generation_id: str, played_samples: int) -> None:
        alignment = self._generations.setdefault(
            generation_id, GenerationAlignment(generation_id)
        )
        alignment.played_samples = max(
            alignment.played_samples,
            min(max(0, played_samples), alignment.total_samples),
        )

    def total_samples(self, generation_id: str) -> int:
        alignment = self._generations.get(generation_id)
        return alignment.total_samples if alignment is not None else 0

    def played_samples(self, generation_id: str) -> int:
        alignment = self._generations.get(generation_id)
        return alignment.played_samples if alignment is not None else 0

    def is_fully_played(self, generation_id: str) -> bool:
        alignment = self._generations.get(generation_id)
        return bool(
            alignment is not None
            and alignment.total_samples > 0
            and alignment.played_samples >= alignment.total_samples
        )

    def spoken_text(
        self, generation_id: str, played_samples: int | None = None
    ) -> str:
        alignment = self._generations.get(generation_id)
        if alignment is None:
            return ""
        boundary = alignment.played_samples if played_samples is None else played_samples
        boundary = min(max(0, boundary), alignment.total_samples)
        spoken: list[str] = []

        for segment in alignment.segments:
            if boundary >= segment.end_sample:
                spoken.append(segment.text)
                continue
            if boundary <= segment.start_sample or segment.samples == 0:
                break
            break

        return "".join(spoken).strip()

    def discard(self, generation_id: str) -> None:
        self._generations.pop(generation_id, None)
