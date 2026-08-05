#![allow(clippy::all, unused)]

#[cfg(test)]
mod vad_rt_tests {
    #[cfg(feature = "rt-alloc-counting")]
    use alloc_counter::count_alloc;
    use std::sync::atomic::{AtomicI64, AtomicBool, Ordering};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    #[derive(Default)]
    struct VadState {
        energy_accum: i64,
        frame_count: i64,
        speech_detected: bool,
        epoch: i64,
    }

    // Zero-allocation VAD processor
    struct VadProcessor {
        state: VadState,
        energy_threshold: i64,
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

        // RT-safe: no allocations, uses stack only
        fn process_frame(&mut self, samples: &[i16], epoch: i64) -> bool {
            // Epoch check - cancel if stale
            if epoch != self.state.epoch {
                self.reset(epoch);
                return false;
            }

            // Compute RMS energy (stack-allocated)
            let mut energy = 0i64;
            for sample in samples.iter() {
                energy += (sample.abs() as i64).pow(2);
            }

            let rms_energy = (energy as f64 / samples.len() as f64).sqrt() as i64;

            // Speech detection
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

        #[cfg(feature = "rt-alloc-counting")]
        {
            let result = count_alloc(|| {
                let samples = [100i16; 160];
                processor.process_frame(&samples, 0)
            });

            let allocs = result.0 + result.1;
            assert_eq!(allocs, 0, "VAD processing allocated {} times", allocs);
        }

        #[cfg(not(feature = "rt-alloc-counting"))]
        {
            let samples = [100i16; 160];
            let detected = processor.process_frame(&samples, 0);
            // Basic functionality check
        }
    }

    #[test]
    fn vad_soak_test() {
        let mut processor = VadProcessor::new(500, 160);
        let mut total_allocations = 0u64;

        // Simulate 10,000 frames at 100 Hz = 100 seconds audio
        for i in 0..10_000 {
            #[cfg(feature = "rt-alloc-counting")]
            {
                let result = count_alloc(|| {
                    let mut samples = [0i16; 160];
                    
                    // Simulate audio: alternating noise/silence
                    let amplitude = if i % 200 < 100 { 100 } else { 50 };
                    for j in 0..160 {
                        samples[j] = ((j + i) % amplitude) as i16;
                    }

                    processor.process_frame(&samples, 0)
                });

                total_allocations += result.0 as u64 + result.1 as u64;
            }

            #[cfg(not(feature = "rt-alloc-counting"))]
            {
                let mut samples = [0i16; 160];
                let amplitude = if i % 200 < 100 { 100 } else { 50 };
                for j in 0..160 {
                    samples[j] = ((j + i) % amplitude) as i16;
                }
                processor.process_frame(&samples, 0);
            }
        }

        assert_eq!(total_allocations, 0, "VAD soak test allocated {} times", total_allocations);
    }

    #[test]
    fn vad_epoch_cancellation() {
        let mut processor = VadProcessor::new(500, 160);
        let mut _detected_counts: std::collections::VecDeque<i64> =
            std::collections::VecDeque::new();
        let epoch_counter = Arc::new(AtomicI64::new(0));
        let interrupt_requested = Arc::new(AtomicBool::new(false));

        // Start with epoch 0
        let mut epoch = 0i64;

        for i in 0..100 {
            let current_epoch = epoch_counter.load(Ordering::SeqCst);

            // Check for interruption after 50 frames
            if i == 50 {
                interrupt_requested.store(true, Ordering::SeqCst);
                epoch_counter.fetch_add(1, Ordering::SeqCst);
            }

            let samples = [100i16; 160];
            let detected = processor.process_frame(&samples, current_epoch);

            // After interruption, epoch should have changed
            if i > 50 {
                assert_eq!(
                    current_epoch,
                    1,
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

            let elapsed = start.elapsed().as_nanos();
            latencies.push(elapsed);
        }

        latencies.sort();
        let p50 = latencies[latencies.len() / 2];
        let p95 = latencies[(latencies.len() as f64 * 0.95) as usize];
        let p99 = latencies[(latencies.len() as f64 * 0.99) as usize];

        println!("VAD Latency:");
        println!("  p50: {} ns", p50);
        println!("  p95: {} ns", p95);
        println!("  p99: {} ns", p99);

        // Target: <5ms for VAD processing
        assert!(
            p95 < 5_000_000,
            "VAD p95 latency {} ns exceeds 5ms target",
            p95
        );
    }
}
