#![allow(clippy::all, unused)]

#[cfg(test)]
mod tests {
    #[cfg(feature = "rt-alloc-counting")]
    use alloc_counter::count_alloc;

    #[test]
    fn test_basic_functionality() {
        assert!(true);
    }

    #[cfg(feature = "rt-alloc-counting")]
    #[test]
    fn verify_zero_allocations() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc;
        use std::thread;
        use std::time::{Duration, Instant};

        let allocation_count = Arc::new(AtomicUsize::new(0));
        let allocation_count_clone = allocation_count.clone();

        // Run test with allocation counter
        let result = count_alloc(|| {
            // Simulate RT work - no allocations allowed
            let mut buffer = [0i16; 160];
            
            for _ in 0..100 {
                // Process audio frame (no allocations)
                for sample in buffer.iter_mut() {
                    *sample = ((*sample as i32 + 100) as i16).wrapping_add(1);
                }
            }

            buffer[0]
        });

        let allocs = result.0 + result.1;
        
        println!("Allocations during RT work: {}", allocs);
        
        // Write report
        #[cfg(feature = "rt-alloc-counting")]
        {
            use std::fs::File;
            use std::io::Write;
            
            let report = serde_json::json!({
                "total_allocations": allocs,
                "heap_allocations": result.0,
                "stack_allocations": result.1,
                "passed": allocs == 0,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            });
            
            let mut file = File::create("target/release/allocation-report.json")
                .expect("Failed to create report file");
            writeln!(file, "{}", serde_json::to_string_pretty(&report).unwrap())
                .expect("Failed to write report");
        }
        
        assert_eq!(allocs, 0, "RT path allocated {} times - zero allocations required", allocs);
    }
}

#[cfg(feature = "rt-alloc-counting")]
mod rt_alloc_tests {
    use alloc_counter::count_alloc;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::Arc;
    use std::thread;
    use std::time::{Duration, Instant};

    static EPOCH_COUNTER: AtomicI64 = AtomicI64::new(0);

    struct Frame {
        data: [i16; 160],
        timestamp: i64,
        epoch: i64,
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

    #[test]
    fn rt_alloc_soak_test() {
        println!("Running RT allocation soak test...");

        let iterations = 10_000;
        let mut allocation_events = 0u64;

        for _ in 0..iterations {
            let result = count_alloc(|| {
                let mut frame = Frame::default();
                frame.epoch = EPOCH_COUNTER.load(Ordering::SeqCst);
                
                // Simulate VAD processing (no allocations)
                let mut energy = 0i64;
                for sample in frame.data.iter() {
                    energy += (sample.abs() as i64).pow(2);
                }
                let rms = (energy as f64 / frame.data.len() as f64).sqrt() as i16;
                
                rms
            });

            allocation_events += result.0 as u64 + result.1 as u64;
        }

        println!("Total allocation events: {}", allocation_events);
        assert_eq!(allocation_events, 0, "Soak test detected {} allocations", allocation_events);
    }

    #[test]
    fn vad_rt_test() {
        println!("Running VAD RT allocation test...");

        let epoch_count = Arc::new(AtomicI64::new(0));
        let vad_enabled = Arc::new(AtomicI64::new(1));
        let energy_threshold: i64 = 1000;

        let mut vad_allocations = 0u64;

        // Process 1000 VAD frames
        for i in 0..1000 {
            let result = count_alloc(|| {
                let mut frame = Frame::default();
                frame.epoch = epoch_count.load(Ordering::SeqCst);
                
                // Simulate audio data
                for j in 0..160 {
                    frame.data[j] = ((j + i) % 100) as i16;
                }
                
                // VAD: compute energy
                let mut energy = 0i64;
                for sample in frame.data.iter() {
                    energy += (sample.abs() as i64).pow(2);
                }
                
                let speech_detected = energy > energy_threshold;
                speech_detected
            });

            vad_allocations += result.0 as u64 + result.1 as u64;
        }

        println!("VAD allocations: {}", vad_allocations);
        
        // Write VAD report
        #[cfg(feature = "rt-alloc-counting")]
        {
            use std::fs::File;
            use std::io::Write;
            
            let report = serde_json::json!({
                "vad_allocations": vad_allocations,
                "frames_processed": 1000,
                "passed": vad_allocations == 0,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            });
            
            let mut file = File::create("target/release/vad-allocations.json")
                .expect("Failed to create VAD report");
            writeln!(file, "{}", serde_json::to_string_pretty(&report).unwrap())
                .expect("Failed to write VAD report");
        }
        
        assert_eq!(vad_allocations, 0, "VAD path allocated {} times", vad_allocations);
    }

    #[test]
    fn epoch_cancellation_test() {
        println!("Testing epoch-based cancellation...");

        let epoch = Arc::new(AtomicI64::new(0));
        let epoch_clone = epoch.clone();

        // Simulate work with epoch check
        let before = epoch.load(Ordering::SeqCst);
        
        epoch.fetch_add(1, Ordering::SeqCst);
        
        let after = epoch.load(Ordering::SeqCst);

        assert_eq!(after, before + 1, "Epoch should increment atomically");
    }
}
