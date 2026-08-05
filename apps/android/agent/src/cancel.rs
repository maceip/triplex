//! Cancellation doorbells (RUNTIME_INVARIANTS.md §2.3, §3.4 step 2).
//!
//! Doorbells bound cancel latency inside long kernels; they never carry
//! correctness — every worker also lazily validates its token, and a lost or
//! stale ring is therefore harmless.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

#[derive(Clone, Default)]
pub struct Doorbell(Arc<AtomicBool>);

impl Doorbell {
    pub fn new() -> Self {
        Self(Arc::new(AtomicBool::new(false)))
    }

    #[inline]
    pub fn ring(&self) {
        self.0.store(true, Ordering::Release);
    }

    #[inline]
    pub fn is_rung(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }

    /// Re-arm at the start of a new task. Any ring this erases belonged to a
    /// previous epoch whose work the token check already kills.
    #[inline]
    pub fn reset(&self) {
        self.0.store(false, Ordering::Release);
    }
}

/// Fixed-capacity registry the TurnController rings on every barge-in.
/// Non-RT by design (§5: the TurnController is high-priority, not RT-tagged);
/// the lock is brief and bounded.
pub struct DoorbellRegistry {
    slots: Mutex<Vec<Option<Doorbell>>>,
}

impl DoorbellRegistry {
    pub fn new(capacity: usize) -> Self {
        Self {
            slots: Mutex::new(vec![None; capacity]),
        }
    }

    /// Returns the slot id, or `None` when the registry is full.
    pub fn register(&self, bell: &Doorbell) -> Option<usize> {
        let mut slots = self.slots.lock().expect("doorbell registry poisoned");
        let free = slots.iter().position(Option::is_none)?;
        slots[free] = Some(bell.clone());
        Some(free)
    }

    pub fn unregister(&self, id: usize) {
        let mut slots = self.slots.lock().expect("doorbell registry poisoned");
        if let Some(slot) = slots.get_mut(id) {
            *slot = None;
        }
    }

    /// §3.4 step 2: ring every registered doorbell.
    pub fn ring_all(&self) {
        let slots = self.slots.lock().expect("doorbell registry poisoned");
        for bell in slots.iter().flatten() {
            bell.ring();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_all_reaches_registered_bells_only() {
        let registry = DoorbellRegistry::new(2);
        let kept = Doorbell::new();
        let removed = Doorbell::new();
        let kept_id = registry.register(&kept).unwrap();
        let removed_id = registry.register(&removed).unwrap();
        assert!(registry.register(&Doorbell::new()).is_none());

        registry.unregister(removed_id);
        registry.ring_all();
        assert!(kept.is_rung());
        assert!(!removed.is_rung());

        kept.reset();
        assert!(!kept.is_rung());
        registry.unregister(kept_id);
    }
}
