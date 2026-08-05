//! Far-echo PCM reference (RUNTIME_INVARIANTS.md §3.2).
//!
//! Direct-media calls have no local acoustic loop; the barge-in false-trigger
//! risk is the far end reflecting agent audio back. The egress pump writes
//! every egressed sample (synthesis and silence fill alike, so lag mapping
//! stays aligned with egress realtime) into this circular buffer; the VAD
//! thread cross-correlates candidate speech against it over a bounded lag
//! window.
//!
//! Concurrency: single writer (egress pump), any readers. Samples are relaxed
//! `AtomicI16`s and the head cursor is published with Release; reads are
//! seqlock-validated — a read the writer lapped mid-copy reports failure and
//! the caller simply skips that lag for the current frame. Both paths are
//! wait-free and allocation-free.

use core::sync::atomic::{AtomicI16, AtomicU64, Ordering};

pub struct EchoRefBuffer {
    samples: Box<[AtomicI16]>,
    /// Absolute count of samples ever written; monotonic.
    head: AtomicU64,
    mask: u64,
}

impl EchoRefBuffer {
    /// `capacity` is in samples and must be a power of two. It bounds the
    /// usable lag window: reads deeper than `capacity` behind head fail.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity.is_power_of_two() && capacity > 0);
        let samples: Vec<AtomicI16> = (0..capacity).map(|_| AtomicI16::new(0)).collect();
        Self {
            samples: samples.into_boxed_slice(),
            head: AtomicU64::new(0),
            mask: capacity as u64 - 1,
        }
    }

    #[inline]
    pub fn head(&self) -> u64 {
        self.head.load(Ordering::Acquire)
    }

    /// Egress pump only (single writer). Wait-free; never blocks.
    pub fn write(&self, pcm: &[i16]) {
        let head = self.head.load(Ordering::Relaxed);
        for (i, sample) in pcm.iter().enumerate() {
            let index = ((head + i as u64) & self.mask) as usize;
            self.samples[index].store(*sample, Ordering::Relaxed);
        }
        self.head.store(head + pcm.len() as u64, Ordering::Release);
    }

    /// Copy `out.len()` samples ending `lag` samples behind the current head.
    /// Returns `false` when there is not enough history yet, or when the
    /// writer lapped the region mid-read (seqlock validation).
    pub fn read_lagged(&self, lag: u64, out: &mut [i16]) -> bool {
        let head = self.head.load(Ordering::Acquire);
        let span = lag + out.len() as u64;
        if head < span {
            return false;
        }
        let start = head - span;
        for (i, slot) in out.iter_mut().enumerate() {
            let index = ((start + i as u64) & self.mask) as usize;
            *slot = self.samples[index].load(Ordering::Relaxed);
        }
        // Valid iff the oldest sample read was not yet overwritten when the
        // copy finished: writer never got more than one full capacity ahead
        // of `start`.
        let head_after = self.head.load(Ordering::Acquire);
        head_after - start <= self.mask + 1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_reports_insufficient_history() {
        let buffer = EchoRefBuffer::new(256);
        buffer.write(&[1; 100]);
        let mut out = [0_i16; 32];
        assert!(!buffer.read_lagged(100, &mut out)); // span 132 > head 100
        assert!(buffer.read_lagged(60, &mut out));
        assert_eq!(out, [1; 32]);
    }

    #[test]
    fn lagged_reads_return_the_expected_window() {
        let buffer = EchoRefBuffer::new(256);
        let pattern: Vec<i16> = (0..200).map(|v| v as i16).collect();
        buffer.write(&pattern);
        let mut out = [0_i16; 10];
        // Ends 50 behind head=200: samples 140..150.
        assert!(buffer.read_lagged(50, &mut out));
        let expected: Vec<i16> = (140..150).collect();
        assert_eq!(out.to_vec(), expected);
    }

    #[test]
    fn overwritten_history_fails_validation() {
        let buffer = EchoRefBuffer::new(64);
        buffer.write(&[3; 64]);
        buffer.write(&[4; 64]); // laps the first write entirely
        let mut out = [0_i16; 16];
        // Lag 60 reaches into the first, overwritten, generation: head=128,
        // span=76, start=52; head - start = 76 <= 64 fails.
        assert!(!buffer.read_lagged(60, &mut out));
        assert!(buffer.read_lagged(0, &mut out));
        assert_eq!(out, [4; 16]);
    }
}
