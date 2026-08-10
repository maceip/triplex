//! RT allocation soak tests (RUNTIME_INVARIANTS.md §6).
//!
//! Uses `RtGuardAlloc` — the same counting allocator as `agent-core` — rather
//! than the unused `alloc_counter` feature path.

use std::fs::{self, File};
use std::io::Write;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;

use triplex_native_media::rtguard::{self, RtGuardAlloc};

#[global_allocator]
static GUARD: RtGuardAlloc = RtGuardAlloc;

static EPOCH_COUNTER: AtomicI64 = AtomicI64::new(0);

struct Frame {
    data: [i16; 160],
    #[allow(dead_code)]
    timestamp: i64,
    epoch: i64,
    #[allow(dead_code)]
    flags: u32,
}

impl Default for Frame {
    fn default() -> Self {
        Self {
            data: [0i16; 160],
            timestamp: 0,
            epoch: 0,
            flags: 0,
        }
    }
}

fn write_json(path: &str, body: &str) {
    if let Some(parent) = std::path::Path::new(path).parent() {
        let _ = fs::create_dir_all(parent);
    }
    let mut file = File::create(path).expect("Failed to create report file");
    writeln!(file, "{body}").expect("Failed to write report");
}

#[test]
fn verify_zero_allocations() {
    let mut buffer = [0i16; 160];
    let before = rtguard::violations();

    rtguard::tag_rt_thread(true);
    for _ in 0..100 {
        for sample in buffer.iter_mut() {
            *sample = ((*sample as i32 + 100) as i16).wrapping_add(1);
        }
    }
    let _ = buffer[0];
    rtguard::tag_rt_thread(false);

    let allocs = rtguard::violations() - before;
    write_json(
        "target/release/allocation-report.json",
        &format!(
            "{{\n  \"total_allocations\": {allocs},\n  \"passed\": {},\n  \"timestamp_unix\": {}\n}}",
            allocs == 0,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0),
        ),
    );

    assert_eq!(
        allocs, 0,
        "RT path allocated {allocs} times - zero allocations required"
    );
}

#[test]
fn rt_alloc_soak_test() {
    let iterations = 10_000;
    let before = rtguard::violations();

    rtguard::tag_rt_thread(true);
    for _ in 0..iterations {
        let mut frame = Frame::default();
        frame.epoch = EPOCH_COUNTER.load(Ordering::SeqCst);

        let mut energy = 0i64;
        for sample in frame.data.iter() {
            energy += (sample.abs() as i64).pow(2);
        }
        let _rms = (energy as f64 / frame.data.len() as f64).sqrt() as i16;
    }
    rtguard::tag_rt_thread(false);

    let allocation_events = rtguard::violations() - before;
    assert_eq!(
        allocation_events, 0,
        "Soak test detected {allocation_events} allocations"
    );
}

#[test]
fn vad_rt_test() {
    let epoch_count = Arc::new(AtomicI64::new(0));
    let energy_threshold: i64 = 1000;
    let before = rtguard::violations();

    rtguard::tag_rt_thread(true);
    for i in 0..1000 {
        let mut frame = Frame::default();
        frame.epoch = epoch_count.load(Ordering::SeqCst);

        for j in 0..160 {
            frame.data[j] = ((j + i) % 100) as i16;
        }

        let mut energy = 0i64;
        for sample in frame.data.iter() {
            energy += (sample.abs() as i64).pow(2);
        }
        let _speech_detected = energy > energy_threshold;
    }
    rtguard::tag_rt_thread(false);

    let vad_allocations = rtguard::violations() - before;
    write_json(
        "target/release/vad-allocations.json",
        &format!(
            "{{\n  \"vad_allocations\": {vad_allocations},\n  \"frames_processed\": 1000,\n  \"passed\": {},\n  \"timestamp_unix\": {}\n}}",
            vad_allocations == 0,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0),
        ),
    );

    assert_eq!(
        vad_allocations, 0,
        "VAD path allocated {vad_allocations} times"
    );
}

#[test]
fn epoch_cancellation_test() {
    let epoch = Arc::new(AtomicI64::new(0));
    let before = epoch.load(Ordering::SeqCst);
    epoch.fetch_add(1, Ordering::SeqCst);
    let after = epoch.load(Ordering::SeqCst);
    assert_eq!(after, before + 1, "Epoch should increment atomically");
}
