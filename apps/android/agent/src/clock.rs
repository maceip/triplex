//! Monotonic time source (§0: one clock domain, `mono_ns`).

use core::sync::atomic::{AtomicI64, Ordering};
use std::time::Instant;

pub trait Clock: Sync {
    fn now_ns(&self) -> i64;
}

/// CLOCK_MONOTONIC in raw nanoseconds — the same time domain as Android's
/// `System.nanoTime()`, so frame timestamps stamped in Kotlin/JNI and
/// deadlines computed here compare directly (§0: one clock domain).
pub struct RawMonotonicClock;

impl Clock for RawMonotonicClock {
    fn now_ns(&self) -> i64 {
        let mut ts = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        // Safety: clock_gettime with a valid pointer; CLOCK_MONOTONIC always exists.
        unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
        ts.tv_sec as i64 * 1_000_000_000 + ts.tv_nsec as i64
    }
}

/// Process-relative monotonic clock for production hosts.
pub struct MonotonicClock {
    base: Instant,
}

impl MonotonicClock {
    pub fn new() -> Self {
        Self { base: Instant::now() }
    }
}

impl Default for MonotonicClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for MonotonicClock {
    fn now_ns(&self) -> i64 {
        i64::try_from(self.base.elapsed().as_nanos()).unwrap_or(i64::MAX)
    }
}

/// Deterministic clock for tests; shared across threads.
#[derive(Default)]
pub struct TestClock(AtomicI64);

impl TestClock {
    pub fn new() -> Self {
        Self(AtomicI64::new(0))
    }

    pub fn set(&self, now_ns: i64) {
        self.0.store(now_ns, Ordering::Release);
    }

    pub fn advance(&self, delta_ns: i64) {
        self.0.fetch_add(delta_ns, Ordering::AcqRel);
    }
}

impl Clock for TestClock {
    fn now_ns(&self) -> i64 {
        self.0.load(Ordering::Acquire)
    }
}
