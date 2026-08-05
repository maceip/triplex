use crate::diagnostics::{RtpDirectionDiagnostics, RtpDirectionSnapshot};
use crate::frame::{FrameHeader, FrameSlot, Stream, FLAG_DISCONT, FLAG_SILENCE, FRAME_SAMPLES};
use crate::ring::SpscRing;
use core::cell::UnsafeCell;
use core::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicU8, Ordering};

const CAPTURE_POOL_SLOTS: usize = 32;
const SYNTH_POOL_SLOTS: usize = 32;
const CAPTURE_RING_SLOTS: usize = 16;
const SYNTH_RING_SLOTS: usize = 8;

const OWNER_FREE: u8 = 0;
const OWNER_ACQUIRED: u8 = 1;
const OWNER_QUEUED: u8 = 2;

pub const ROUTE_DIRECT_PJSIP: u8 = 1;
pub const ROUTE_RELAY_WEBSOCKET: u8 = 2;

struct FramePool<const N: usize> {
    slots: Box<[UnsafeCell<FrameSlot>; N]>,
    owners: Box<[AtomicU8; N]>,
    free: SpscRing<u16, N>,
}

// Safety: ownership of each slot is transferred through SPSC rings. Slot data
// is only accessed by the current owner; owner atomics catch misuse in tests.
unsafe impl<const N: usize> Sync for FramePool<N> {}
unsafe impl<const N: usize> Send for FramePool<N> {}

impl<const N: usize> FramePool<N> {
    fn new() -> Self {
        assert!(N <= usize::from(u16::MAX));
        let pool = Self {
            slots: Box::new(core::array::from_fn(|_| {
                UnsafeCell::new(FrameSlot::default())
            })),
            owners: Box::new(core::array::from_fn(|_| AtomicU8::new(OWNER_FREE))),
            free: SpscRing::new(),
        };
        for handle in 0..N {
            pool.free
                .push(handle as u16)
                .expect("fresh freelist capacity");
        }
        pool
    }

    #[inline]
    fn try_acquire(&self) -> Option<u16> {
        let handle = self.free.pop()?;
        let owner = &self.owners[usize::from(handle)];
        // The transition must run in every profile; only the check is
        // debug-gated. Never bury side effects inside debug_assert!.
        let transition = owner.compare_exchange(
            OWNER_FREE,
            OWNER_ACQUIRED,
            Ordering::Acquire,
            Ordering::Relaxed,
        );
        debug_assert_eq!(transition, Ok(OWNER_FREE));
        Some(handle)
    }

    #[inline]
    fn write(&self, handle: u16, slot: &FrameSlot) {
        let index = usize::from(handle);
        debug_assert_eq!(self.owners[index].load(Ordering::Relaxed), OWNER_ACQUIRED);
        // Safety: the producer exclusively owns an acquired slot.
        unsafe { self.slots[index].get().write(*slot) };
        self.owners[index].store(OWNER_QUEUED, Ordering::Release);
    }

    #[inline]
    fn read(&self, handle: u16) -> FrameSlot {
        let index = usize::from(handle);
        debug_assert_eq!(self.owners[index].load(Ordering::Acquire), OWNER_QUEUED);
        // Safety: the queue transferred exclusive ownership to the consumer.
        unsafe { *self.slots[index].get() }
    }

    #[inline]
    fn release(&self, handle: u16) {
        let index = usize::from(handle);
        let transition = self.owners[index].compare_exchange(
            OWNER_QUEUED,
            OWNER_FREE,
            Ordering::AcqRel,
            Ordering::Relaxed,
        );
        debug_assert_eq!(transition, Ok(OWNER_QUEUED));
        let returned = self.free.push(handle);
        debug_assert!(returned.is_ok());
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct MediaMetrics {
    pub abi_version: u16,
    pub struct_size: u16,
    pub route: u8,
    pub reserved: [u8; 3],
    pub epoch: u32,
    pub flush_ack_epoch: u32,
    pub capture_frames: u64,
    pub capture_dropped_frames: u64,
    pub capture_discontinuities: u64,
    pub synth_enqueued_frames: u64,
    pub synth_played_frames: u64,
    pub synth_stale_frames: u64,
    pub synth_dropped_frames: u64,
    pub silence_frames: u64,
    pub synth_queue_depth: u32,
    pub capture_queue_depth: u32,
    pub rx: RtpDirectionSnapshot,
    pub tx: RtpDirectionSnapshot,
}

pub struct NativeMediaRuntime {
    route: u8,
    capture_pool: FramePool<CAPTURE_POOL_SLOTS>,
    capture_ring: SpscRing<u16, CAPTURE_RING_SLOTS>,
    synth_pool: FramePool<SYNTH_POOL_SLOTS>,
    synth_ring: SpscRing<u16, SYNTH_RING_SLOTS>,
    epoch: AtomicU32,
    next_capture_seq: AtomicU64,
    next_synth_seq: AtomicU64,
    capture_drop_pending: AtomicBool,
    pending_flush_epoch: AtomicU32,
    flush_ack_epoch: AtomicU32,
    capture_frames: AtomicU64,
    capture_dropped_frames: AtomicU64,
    capture_discontinuities: AtomicU64,
    synth_enqueued_frames: AtomicU64,
    synth_played_frames: AtomicU64,
    synth_stale_frames: AtomicU64,
    synth_dropped_frames: AtomicU64,
    silence_frames: AtomicU64,
    rx: RtpDirectionDiagnostics,
    tx: RtpDirectionDiagnostics,
}

impl NativeMediaRuntime {
    pub fn new(route: u8) -> Self {
        assert!(matches!(route, ROUTE_DIRECT_PJSIP | ROUTE_RELAY_WEBSOCKET));
        Self {
            route,
            capture_pool: FramePool::new(),
            capture_ring: SpscRing::new(),
            synth_pool: FramePool::new(),
            synth_ring: SpscRing::new(),
            epoch: AtomicU32::new(1),
            next_capture_seq: AtomicU64::new(0),
            next_synth_seq: AtomicU64::new(0),
            capture_drop_pending: AtomicBool::new(false),
            pending_flush_epoch: AtomicU32::new(0),
            flush_ack_epoch: AtomicU32::new(0),
            capture_frames: AtomicU64::new(0),
            capture_dropped_frames: AtomicU64::new(0),
            capture_discontinuities: AtomicU64::new(0),
            synth_enqueued_frames: AtomicU64::new(0),
            synth_played_frames: AtomicU64::new(0),
            synth_stale_frames: AtomicU64::new(0),
            synth_dropped_frames: AtomicU64::new(0),
            silence_frames: AtomicU64::new(0),
            rx: RtpDirectionDiagnostics::new(),
            tx: RtpDirectionDiagnostics::new(),
        }
    }

    #[inline]
    pub fn epoch(&self) -> u32 {
        self.epoch.load(Ordering::Acquire)
    }

    /// Latest acknowledged flush epoch (0 = none yet). Set by the mixer
    /// consumer once every synthesis frame queued at interrupt time has been
    /// discarded; the egress pump turns transitions into `FlushAck` events.
    #[inline]
    pub fn flush_ack_epoch(&self) -> u32 {
        self.flush_ack_epoch.load(Ordering::Acquire)
    }

    /// Producer-side backpressure probe for the synthesis queue. Only the
    /// single synth producer (the TTS host) may act on it.
    #[inline]
    pub fn synth_ring_full(&self) -> bool {
        self.synth_ring.is_full()
    }

    /// Transport adapter producer. Never blocks or allocates.
    #[inline]
    pub fn push_capture(&self, samples: &[i16], mono_ns: i64, rtp_timestamp: u32) -> bool {
        if samples.len() > FRAME_SAMPLES || self.capture_ring.is_full() {
            self.note_capture_drop();
            return false;
        }
        let Some(handle) = self.capture_pool.try_acquire() else {
            self.note_capture_drop();
            return false;
        };

        let mut slot = FrameSlot {
            header: FrameHeader {
                seq: self.next_capture_seq.fetch_add(1, Ordering::Relaxed),
                mono_ns,
                rtp_ts: rtp_timestamp,
                epoch: self.epoch(),
                len: samples.len() as u16,
                flags: 0,
                stream: Stream::Capture as u8,
                reserved: [0; 7],
            },
            ..FrameSlot::default()
        };
        slot.samples[..samples.len()].copy_from_slice(samples);
        if self.capture_drop_pending.swap(false, Ordering::AcqRel) {
            slot.header.flags |= FLAG_DISCONT;
            self.capture_discontinuities.fetch_add(1, Ordering::Relaxed);
        }

        self.capture_pool.write(handle, &slot);
        let queued = self.capture_ring.push(handle);
        debug_assert!(queued.is_ok());
        self.capture_frames.fetch_add(1, Ordering::Relaxed);
        true
    }

    #[inline]
    fn note_capture_drop(&self) {
        self.capture_dropped_frames.fetch_add(1, Ordering::Relaxed);
        self.capture_drop_pending.store(true, Ordering::Release);
    }

    /// ASR/probe consumer. Never blocks or allocates.
    #[inline]
    pub fn pop_capture(&self) -> Option<FrameSlot> {
        let handle = self.capture_ring.pop()?;
        let slot = self.capture_pool.read(handle);
        self.capture_pool.release(handle);
        Some(slot)
    }

    /// TTS/reflex producer. Rejects stale epochs and queue depths over 80 ms.
    #[inline]
    pub fn push_synth(
        &self,
        samples: &[i16],
        mono_ns: i64,
        epoch: u32,
        flags: u16,
        stream: Stream,
    ) -> bool {
        if samples.len() > FRAME_SAMPLES || epoch != self.epoch() || self.synth_ring.is_full() {
            self.synth_dropped_frames.fetch_add(1, Ordering::Relaxed);
            return false;
        }
        let Some(handle) = self.synth_pool.try_acquire() else {
            self.synth_dropped_frames.fetch_add(1, Ordering::Relaxed);
            return false;
        };

        let mut slot = FrameSlot {
            header: FrameHeader {
                seq: self.next_synth_seq.fetch_add(1, Ordering::Relaxed),
                mono_ns,
                rtp_ts: 0,
                epoch,
                len: samples.len() as u16,
                flags,
                stream: stream as u8,
                reserved: [0; 7],
            },
            ..FrameSlot::default()
        };
        slot.samples[..samples.len()].copy_from_slice(samples);
        self.synth_pool.write(handle, &slot);
        let queued = self.synth_ring.push(handle);
        debug_assert!(queued.is_ok());
        self.synth_enqueued_frames.fetch_add(1, Ordering::Relaxed);
        true
    }

    /// Mixer consumer. Stale frames are discarded lazily in a bounded loop;
    /// silence preserves RTP continuity when no current-epoch audio is ready.
    #[inline]
    pub fn pop_synth_or_silence(&self, mono_ns: i64) -> FrameSlot {
        let current_epoch = self.epoch();
        for _ in 0..SYNTH_RING_SLOTS {
            let Some(handle) = self.synth_ring.pop() else {
                break;
            };
            let mut slot = self.synth_pool.read(handle);
            self.synth_pool.release(handle);
            if slot.header.epoch != current_epoch {
                self.synth_stale_frames.fetch_add(1, Ordering::Relaxed);
                continue;
            }
            slot.header.rtp_ts = 0;
            self.synth_played_frames.fetch_add(1, Ordering::Relaxed);
            self.acknowledge_flush_if_pending();
            return slot;
        }

        self.acknowledge_flush_if_pending();
        self.silence_frames.fetch_add(1, Ordering::Relaxed);
        FrameSlot {
            header: FrameHeader {
                seq: self.next_synth_seq.fetch_add(1, Ordering::Relaxed),
                mono_ns,
                rtp_ts: 0,
                epoch: current_epoch,
                len: FRAME_SAMPLES as u16,
                flags: FLAG_SILENCE,
                stream: Stream::Synth as u8,
                reserved: [0; 7],
            },
            samples: [0; FRAME_SAMPLES],
        }
    }

    #[inline]
    fn acknowledge_flush_if_pending(&self) {
        let pending = self.pending_flush_epoch.swap(0, Ordering::AcqRel);
        if pending != 0 {
            self.flush_ack_epoch.store(pending, Ordering::Release);
        }
    }

    /// TurnController's cancellation publication. Capture is untouched.
    #[inline]
    pub fn interrupt(&self) -> u32 {
        let new_epoch = self.epoch.fetch_add(1, Ordering::AcqRel).wrapping_add(1);
        self.pending_flush_epoch.store(new_epoch, Ordering::Release);
        new_epoch
    }

    #[inline]
    pub fn observe_rtp(
        &self,
        direction: u8,
        sequence: u16,
        timestamp: u32,
        packet_bytes: u32,
        arrival_mono_ns: i64,
        clock_rate_hz: u32,
    ) -> bool {
        match direction {
            crate::diagnostics::DIRECTION_RX => &self.rx,
            crate::diagnostics::DIRECTION_TX => &self.tx,
            _ => return false,
        }
        .observe(
            sequence,
            timestamp,
            packet_bytes,
            arrival_mono_ns,
            clock_rate_hz,
        );
        true
    }

    pub fn snapshot(&self) -> MediaMetrics {
        MediaMetrics {
            abi_version: 1,
            struct_size: core::mem::size_of::<MediaMetrics>() as u16,
            route: self.route,
            reserved: [0; 3],
            epoch: self.epoch(),
            flush_ack_epoch: self.flush_ack_epoch.load(Ordering::Acquire),
            capture_frames: self.capture_frames.load(Ordering::Acquire),
            capture_dropped_frames: self.capture_dropped_frames.load(Ordering::Acquire),
            capture_discontinuities: self.capture_discontinuities.load(Ordering::Acquire),
            synth_enqueued_frames: self.synth_enqueued_frames.load(Ordering::Acquire),
            synth_played_frames: self.synth_played_frames.load(Ordering::Acquire),
            synth_stale_frames: self.synth_stale_frames.load(Ordering::Acquire),
            synth_dropped_frames: self.synth_dropped_frames.load(Ordering::Acquire),
            silence_frames: self.silence_frames.load(Ordering::Acquire),
            synth_queue_depth: self.synth_ring.len() as u32,
            capture_queue_depth: self.capture_ring.len() as u32,
            rx: self.rx.snapshot(),
            tx: self.tx.snapshot(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::{FLAG_FINAL_CHUNK, FRAME_BYTES};

    #[test]
    fn capture_drop_marks_next_delivered_frame_discontinuous() {
        let runtime = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let samples = [7_i16; FRAME_SAMPLES];
        for timestamp in 0..CAPTURE_RING_SLOTS {
            assert!(runtime.push_capture(&samples, timestamp as i64, timestamp as u32));
        }
        assert!(!runtime.push_capture(&samples, 99, 99));
        let first = runtime.pop_capture().unwrap();
        assert_eq!(first.header.flags & FLAG_DISCONT, 0);
        assert!(runtime.push_capture(&samples, 100, 100));
        for _ in 1..CAPTURE_RING_SLOTS {
            runtime.pop_capture().unwrap();
        }
        let after_drop = runtime.pop_capture().unwrap();
        assert_ne!(after_drop.header.flags & FLAG_DISCONT, 0);
        assert_eq!(runtime.snapshot().capture_dropped_frames, 1);
    }

    #[test]
    fn interruption_invalidates_synthesis_but_never_capture() {
        let runtime = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let samples = [42_i16; FRAME_SAMPLES];
        let epoch = runtime.epoch();
        assert!(runtime.push_capture(&samples, 1, 1));
        assert!(runtime.push_synth(&samples, 2, epoch, FLAG_FINAL_CHUNK, Stream::Synth,));

        let next_epoch = runtime.interrupt();
        assert_eq!(next_epoch, epoch + 1);
        let mixed = runtime.pop_synth_or_silence(3);
        assert_ne!(mixed.header.flags & FLAG_SILENCE, 0);
        assert_eq!(mixed.samples, [0; FRAME_SAMPLES]);
        assert_eq!(runtime.pop_capture().unwrap().samples, samples);

        let metrics = runtime.snapshot();
        assert_eq!(metrics.synth_stale_frames, 1);
        assert_eq!(metrics.flush_ack_epoch, next_epoch);
    }

    #[test]
    fn synthesis_queue_has_an_eighty_millisecond_hard_ceiling() {
        let runtime = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let samples = [1_i16; FRAME_SAMPLES];
        let epoch = runtime.epoch();
        for _ in 0..SYNTH_RING_SLOTS {
            assert!(runtime.push_synth(&samples, 0, epoch, 0, Stream::Synth));
        }
        assert!(!runtime.push_synth(&samples, 0, epoch, 0, Stream::Synth));
        assert_eq!(runtime.snapshot().synth_queue_depth, 8);
        assert_eq!(FRAME_BYTES, 320);
    }
}
