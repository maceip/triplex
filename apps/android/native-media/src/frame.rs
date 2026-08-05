use core::mem::{align_of, size_of};

pub const SAMPLE_RATE_HZ: u32 = 16_000;
pub const FRAME_DURATION_MS: u32 = 10;
pub const FRAME_SAMPLES: usize = 160;
pub const FRAME_BYTES: usize = FRAME_SAMPLES * size_of::<i16>();

pub const FLAG_DISCONT: u16 = 1 << 0;
pub const FLAG_SILENCE: u16 = 1 << 1;
pub const FLAG_EOU: u16 = 1 << 2;
pub const FLAG_FINAL_CHUNK: u16 = 1 << 3;

#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Stream {
    Capture = 0,
    Synth = 1,
    Reflex = 2,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct FrameHeader {
    pub seq: u64,
    pub mono_ns: i64,
    pub rtp_ts: u32,
    pub epoch: u32,
    pub len: u16,
    pub flags: u16,
    pub stream: u8,
    pub reserved: [u8; 7],
}

#[repr(C, align(64))]
#[derive(Clone, Copy)]
pub struct FrameSlot {
    pub header: FrameHeader,
    pub samples: [i16; FRAME_SAMPLES],
}

impl Default for FrameSlot {
    fn default() -> Self {
        Self {
            header: FrameHeader::default(),
            samples: [0; FRAME_SAMPLES],
        }
    }
}

const _: () = assert!(size_of::<FrameSlot>() == 384);
const _: () = assert!(align_of::<FrameSlot>() == 64);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_frame_layout_is_stable() {
        assert_eq!(SAMPLE_RATE_HZ, 16_000);
        assert_eq!(FRAME_DURATION_MS, 10);
        assert_eq!(FRAME_SAMPLES, 160);
        assert_eq!(FRAME_BYTES, 320);
        assert_eq!(size_of::<FrameSlot>(), 384);
        assert_eq!(align_of::<FrameSlot>(), 64);
    }
}
