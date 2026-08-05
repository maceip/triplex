//! Allocation guard over the VAD evaluation loop (RUNTIME_INVARIANTS.md §6).
//! The VAD thread is not RT-tagged in the priority inventory (§5) but is held
//! to the same discipline: classification, the echo-gate correlation, and the
//! ASR forward path must not touch the heap.

use triplex_agent_core::vad::{AsrFrameRing, EnergySpeechModel, VadConfig, VadEventRing, VadLoop};
use triplex_native_media::agent::echo_ref::EchoRefBuffer;
use triplex_native_media::rtguard::{self, RtGuardAlloc};
use triplex_native_media::{NativeMediaRuntime, FRAME_SAMPLES, ROUTE_DIRECT_PJSIP};

#[global_allocator]
static GUARD: RtGuardAlloc = RtGuardAlloc;

#[test]
fn vad_classification_and_forwarding_do_not_allocate() {
    let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
    let asr_ring = AsrFrameRing::new();
    let events = VadEventRing::new();
    let echo = EchoRefBuffer::new(16_384);
    let mut vad = VadLoop::new(
        VadConfig::default(),
        EnergySpeechModel { pivot_rms: 200.0 },
        &media,
        &asr_ring,
        &events,
        &echo,
    );

    // Populate the echo reference so the correlation path actually runs.
    let reference = [1_500_i16; 8_000];
    echo.write(&reference);

    let mut speech = [0_i16; FRAME_SAMPLES];
    for (i, s) in speech.iter_mut().enumerate() {
        *s = if (i / 8) % 2 == 0 { 3_000 } else { -3_000 };
    }
    let silence = [0_i16; FRAME_SAMPLES];

    rtguard::tag_rt_thread(true);
    let mut mono = 0_i64;
    for tick in 0..300_u32 {
        let frame = if (tick / 30) % 2 == 0 { &speech } else { &silence };
        assert!(media.push_capture(frame, mono, tick * 160));
        vad.poll();
        while asr_ring.pop().is_some() {}
        while events.pop().is_some() {}
        mono += 10_000_000;
    }
    rtguard::tag_rt_thread(false);

    assert_eq!(
        rtguard::violations(),
        0,
        "heap allocation on the VAD evaluation path"
    );
    let counters = vad.counters();
    assert_eq!(counters.frames, 300);
    assert_eq!(counters.asr_forward_drops, 0);
    assert!(counters.onsets >= 1);
}
