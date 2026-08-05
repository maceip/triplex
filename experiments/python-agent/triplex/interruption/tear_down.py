# SUPERSEDED-BY: RUNTIME_INVARIANTS.md §7.1, §7.2
# RETIRED: this module does not ship and is not maintained.
# Its contract was ported; see DISPOSITION_LEDGER.md. Read for
# reference only — do not extend, and do not treat as a fallback.

"""Sub-50 ms barge-in tear-down orchestration."""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class InterruptionResult:
    generation_id: str
    played_samples: int
    latency_ms: float


class TearDownCoordinator:
    """Run local flush, remote playout flush, and model abort concurrently."""

    def __init__(
        self,
        *,
        flush_local: Callable[[str], None],
        abort_generation: Callable[[], Awaitable[None]],
        flush_playout: Callable[[str], Awaitable[int]] | None = None,
        deadline_ms: float = 50.0,
    ) -> None:
        self.flush_local = flush_local
        self.abort_generation = abort_generation
        self.flush_playout = flush_playout
        self.deadline_ms = deadline_ms

    async def trigger(
        self, generation_id: str, known_played_samples: int = 0
    ) -> InterruptionResult:
        start = time.perf_counter()
        self.flush_local(generation_id)

        abort_task: asyncio.Future[None] = asyncio.ensure_future(
            self.abort_generation()
        )
        flush_task: asyncio.Future[int] | None = (
            asyncio.ensure_future(self.flush_playout(generation_id))
            if self.flush_playout is not None
            else None
        )
        tasks = [abort_task] + ([flush_task] if flush_task is not None else [])
        played_samples = known_played_samples
        done, pending = await asyncio.wait(
            tasks, timeout=self.deadline_ms / 1000
        )
        for task in pending:
            # A deadline bounds caller-facing teardown latency, but must not
            # cancel an already-issued model abort or remote playout flush.
            task.add_done_callback(_consume_background_result)
        if flush_task is not None and flush_task in done and not flush_task.cancelled():
            flush_result = flush_task.exception()
            if flush_result is None:
                played_samples = max(played_samples, flush_task.result())

        latency_ms = (time.perf_counter() - start) * 1000
        return InterruptionResult(generation_id, played_samples, latency_ms)


def _consume_background_result(task: asyncio.Future[Any]) -> None:
    """Consume late task failures after the caller-facing deadline expires."""
    if not task.cancelled():
        task.exception()
