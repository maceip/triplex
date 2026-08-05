use core::sync::atomic::{AtomicI64, AtomicU32, AtomicU64, Ordering};

pub const DIRECTION_RX: u8 = 0;
pub const DIRECTION_TX: u8 = 1;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RtpDirectionSnapshot {
    pub packets: u64,
    pub bytes: u64,
    pub sequence_gap_packets: u64,
    pub reordered_packets: u64,
    pub duplicate_packets: u64,
    pub last_arrival_mono_ns: i64,
    pub last_rtp_timestamp: u32,
    pub rtp_clock_rate_hz: u32,
    pub jitter_us: u64,
    pub last_extended_sequence: u64,
}

pub struct RtpDirectionDiagnostics {
    packets: AtomicU64,
    bytes: AtomicU64,
    sequence_gap_packets: AtomicU64,
    reordered_packets: AtomicU64,
    duplicate_packets: AtomicU64,
    last_arrival_mono_ns: AtomicI64,
    last_rtp_timestamp: AtomicU32,
    rtp_clock_rate_hz: AtomicU32,
    // Zero means uninitialized; otherwise stored value is extended sequence + 1.
    last_extended_sequence_plus_one: AtomicU64,
    // Single RTP producer updates these relaxed atomics.
    last_transit: AtomicI64,
    // RFC 3550 jitter in RTP timestamp units, scaled by 16.
    jitter_q4: AtomicU64,
}

impl RtpDirectionDiagnostics {
    pub const fn new() -> Self {
        Self {
            packets: AtomicU64::new(0),
            bytes: AtomicU64::new(0),
            sequence_gap_packets: AtomicU64::new(0),
            reordered_packets: AtomicU64::new(0),
            duplicate_packets: AtomicU64::new(0),
            last_arrival_mono_ns: AtomicI64::new(0),
            last_rtp_timestamp: AtomicU32::new(0),
            rtp_clock_rate_hz: AtomicU32::new(8_000),
            last_extended_sequence_plus_one: AtomicU64::new(0),
            last_transit: AtomicI64::new(i64::MIN),
            jitter_q4: AtomicU64::new(0),
        }
    }

    /// Observe an RTP header. This is single-producer and RT-safe.
    #[inline]
    pub fn observe(
        &self,
        sequence: u16,
        timestamp: u32,
        packet_bytes: u32,
        arrival_mono_ns: i64,
        clock_rate_hz: u32,
    ) {
        let clock_rate_hz = clock_rate_hz.max(1);
        self.packets.fetch_add(1, Ordering::Relaxed);
        self.bytes
            .fetch_add(u64::from(packet_bytes), Ordering::Relaxed);
        self.last_arrival_mono_ns
            .store(arrival_mono_ns, Ordering::Relaxed);
        self.last_rtp_timestamp.store(timestamp, Ordering::Relaxed);
        self.rtp_clock_rate_hz
            .store(clock_rate_hz, Ordering::Relaxed);

        let encoded_last = self.last_extended_sequence_plus_one.load(Ordering::Relaxed);
        let extended = if encoded_last == 0 {
            u64::from(sequence)
        } else {
            extend_sequence(sequence, encoded_last - 1)
        };

        if encoded_last == 0 {
            self.last_extended_sequence_plus_one
                .store(extended + 1, Ordering::Relaxed);
        } else {
            let last = encoded_last - 1;
            if extended > last {
                let missing = extended - last - 1;
                if missing > 0 {
                    self.sequence_gap_packets
                        .fetch_add(missing, Ordering::Relaxed);
                }
                self.last_extended_sequence_plus_one
                    .store(extended + 1, Ordering::Relaxed);
            } else if extended == last {
                self.duplicate_packets.fetch_add(1, Ordering::Relaxed);
            } else {
                self.reordered_packets.fetch_add(1, Ordering::Relaxed);
            }
        }

        let arrival_ticks =
            ((arrival_mono_ns as i128 * i128::from(clock_rate_hz)) / 1_000_000_000_i128) as i64;
        let transit = arrival_ticks.wrapping_sub(i64::from(timestamp));
        let previous = self.last_transit.swap(transit, Ordering::Relaxed);
        if previous != i64::MIN {
            let delta = transit.abs_diff(previous);
            let jitter_q4 = self.jitter_q4.load(Ordering::Relaxed);
            let target_q4 = delta.saturating_mul(16);
            let updated = if target_q4 >= jitter_q4 {
                jitter_q4 + (target_q4 - jitter_q4 + 8) / 16
            } else {
                jitter_q4 - (jitter_q4 - target_q4 + 8) / 16
            };
            self.jitter_q4.store(updated, Ordering::Relaxed);
        }
    }

    pub fn snapshot(&self) -> RtpDirectionSnapshot {
        let clock_rate_hz = self.rtp_clock_rate_hz.load(Ordering::Acquire).max(1);
        let jitter_q4 = self.jitter_q4.load(Ordering::Acquire);
        let extended_plus_one = self.last_extended_sequence_plus_one.load(Ordering::Acquire);
        RtpDirectionSnapshot {
            packets: self.packets.load(Ordering::Acquire),
            bytes: self.bytes.load(Ordering::Acquire),
            sequence_gap_packets: self.sequence_gap_packets.load(Ordering::Acquire),
            reordered_packets: self.reordered_packets.load(Ordering::Acquire),
            duplicate_packets: self.duplicate_packets.load(Ordering::Acquire),
            last_arrival_mono_ns: self.last_arrival_mono_ns.load(Ordering::Acquire),
            last_rtp_timestamp: self.last_rtp_timestamp.load(Ordering::Acquire),
            rtp_clock_rate_hz: clock_rate_hz,
            jitter_us: jitter_q4
                .saturating_mul(1_000_000)
                .saturating_div(u64::from(clock_rate_hz).saturating_mul(16)),
            last_extended_sequence: extended_plus_one.saturating_sub(1),
        }
    }
}

impl Default for RtpDirectionDiagnostics {
    fn default() -> Self {
        Self::new()
    }
}

fn extend_sequence(sequence: u16, last_extended: u64) -> u64 {
    let cycle = last_extended & !0xffff;
    let mut candidate = cycle | u64::from(sequence);
    if candidate.saturating_add(0x8000) < last_extended {
        candidate = candidate.saturating_add(0x1_0000);
    } else if candidate > last_extended.saturating_add(0x8000) && cycle >= 0x1_0000 {
        candidate -= 0x1_0000;
    }
    candidate
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tracks_gaps_reordering_duplicates_and_wrap() {
        let stats = RtpDirectionDiagnostics::new();
        stats.observe(65_534, 0, 160, 0, 8_000);
        stats.observe(65_535, 160, 160, 20_000_000, 8_000);
        stats.observe(1, 480, 160, 60_000_000, 8_000);
        stats.observe(1, 480, 160, 61_000_000, 8_000);
        stats.observe(0, 320, 160, 62_000_000, 8_000);

        let snapshot = stats.snapshot();
        assert_eq!(snapshot.packets, 5);
        assert_eq!(snapshot.sequence_gap_packets, 1);
        assert_eq!(snapshot.duplicate_packets, 1);
        assert_eq!(snapshot.reordered_packets, 1);
        assert_eq!(snapshot.last_extended_sequence, 65_537);
    }

    #[test]
    fn constant_packet_spacing_converges_to_zero_jitter() {
        let stats = RtpDirectionDiagnostics::new();
        for sequence in 0..100_u16 {
            stats.observe(
                sequence,
                u32::from(sequence) * 160,
                172,
                i64::from(sequence) * 20_000_000,
                8_000,
            );
        }
        assert_eq!(stats.snapshot().jitter_us, 0);
    }
}
