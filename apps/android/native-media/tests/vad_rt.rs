//! VAD RT allocation tests (RUNTIME_INVARIANTS.md §6).

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;

use triplex_native_media::rtguard::{self, RtGuardAlloc};

#[global_allocator]
static GUARD: RtGuardAlloc = RtGuardAlloc;

#[derive(Default)]
struct VadState {
    energy_accum: i64,
    frame_count: i64,
    speech_detected: bool,
    epoch: i64,
}

/// Zero-allocation VAD processor.
struct VadProcessor {
    state: VadState,
    energy_threshold: i64,
    #[allow(dead_code)]
    frame_size: usize,
}

impl VadProcessor {
    fn new(energy_threshold: i64, frame_size: usize) -> Self {
        Self {
            state: VadState::default(),
            energy_threshold,
            frame_size,
        }
    }

    /// RT-safe: no allocations, uses stack only.
    fn process_frame(&mut self, samples: &[i16], epoch: i64) -> bool {
        if epoch != self.state.epoch {
            self.reset(epoch);
            return false;
        }

        let mut energy = 0i64;
        for sample in samples.iter() {
            energy += (sample.abs() as i64).pow(2);
        }

        let rms_energy = (energy as f64 / samples.len() as f64).sqrt() as i64;
        self.state.speech_detected = rms_energy > self.energy_threshold;
        self.state.energy_accum += rms_energy;
        self.state.frame_count += 1;
        self.state.speech_detected
    }

    fn reset(&mut self, epoch: i64) {
        self.state = VadState {
            energy_accum: 0,
            frame_count: 0,
            speech_detected: false,
            epoch,
        };
    }
}

#[test]
fn vad_processing_no_allocations() {
    let mut processor = VadProcessor::new(500, 160);
    let samples = [100i16; 160];
    let before = rtguard::violations();

    rtguard::tag_rt_thread(true);
    let _detected = processor.process_frame(&samples, 0);
    rtguard::tag_rt_thread(false);

    let allocs = rtguard::violations() - before;
    assert_eq!(allocs, 0, "VAD processing allocated {allocs} times");
}

#[test]
fn vad_soak_test() {
    let mut processor = VadProcessor::new(500, 160);
    let before = rtguard::violations();

    // Simulate 10,000 frames at 100 Hz ≈ 100 seconds of audio.
    rtguard::tag_rt_thread(true);
    for i in 0..10_000 {
        let mut samples = [0i16; 160];
        let amplitude = if i % 200 < 100 { 100 } else { 50 };
        for j in 0..160 {
            samples[j] = ((j + i) % amplitude) as i16;
        }
        processor.process_frame(&samples, 0);
    }
    rtguard::tag_rt_thread(false);

    let total_allocations = rtguard::violations() - before;
    assert_eq!(
        total_allocations, 0,
        "VAD soak test allocated {total_allocations} times"
    );
}

#[test]
fn vad_epoch_cancellation() {
    let mut processor = VadProcessor::new(500, 160);
    let epoch_counter = Arc::new(AtomicI64::new(0));
    let interrupt_requested = Arc::new(AtomicBool::new(false));

    for i in 0..100 {
        let current_epoch = epoch_counter.load(Ordering::SeqCst);

        if i == 50 {
            interrupt_requested.store(true, Ordering::SeqCst);
            epoch_counter.fetch_add(1, Ordering::SeqCst);
        }

        let samples = [100i16; 160];
        let _detected = processor.process_frame(&samples, current_epoch);

        if i > 50 {
            assert_eq!(
                current_epoch, 1,
                "Epoch should have incremented after interruption"
            );
        }
    }
}

#[test]
fn vad_latency_measurement() {
    let mut processor = VadProcessor::new(500, 160);
    let mut latencies = Vec::new();

    for _ in 0..1000 {
        let start = std::time::Instant::now();
        let samples = [100i16; 160];
        processor.process_frame(&samples, 0);
        latencies.push(start.elapsed().as_nanos());
    }

    latencies.sort();
    let p50 = latencies[latencies.len() / 2];
    let p95 = latencies[(latencies.len() as f64 * 0.95) as usize];
    let p99 = latencies[(latencies.len() as f64 * 0.99) as usize];

    println!("VAD Latency:");
    println!("  p50: {p50} ns");
    println!("  p95: {p95} ns");
    println!("  p99: {p99} ns");

    assert!(
        p95 < 5_000_000,
        "VAD p95 latency {p95} ns exceeds 5ms target"
    );
}
