pub mod agent;
mod diagnostics;
mod frame;
mod ring;
pub mod rtguard;
mod runtime;

pub use diagnostics::{DIRECTION_RX, DIRECTION_TX};
pub use frame::{
    FrameHeader, FrameSlot, Stream, FLAG_DISCONT, FLAG_EOU, FLAG_FINAL_CHUNK, FLAG_SILENCE,
    FRAME_BYTES, FRAME_DURATION_MS, FRAME_SAMPLES, SAMPLE_RATE_HZ,
};
pub use ring::{Full, SpscRing};
pub use runtime::{MediaMetrics, NativeMediaRuntime, ROUTE_DIRECT_PJSIP, ROUTE_RELAY_WEBSOCKET};

use core::ptr;

#[no_mangle]
pub extern "C" fn triplex_media_create(route: u8) -> *mut NativeMediaRuntime {
    if !matches!(route, ROUTE_DIRECT_PJSIP | ROUTE_RELAY_WEBSOCKET) {
        return ptr::null_mut();
    }
    Box::into_raw(Box::new(NativeMediaRuntime::new(route)))
}

#[no_mangle]
/// # Safety
///
/// `runtime` must be null or a live pointer returned by `triplex_media_create`
/// that has not already been destroyed. No other call may use it afterward.
pub unsafe extern "C" fn triplex_media_destroy(runtime: *mut NativeMediaRuntime) {
    if !runtime.is_null() {
        // Safety: callers must pass a live pointer returned by create exactly once.
        drop(unsafe { Box::from_raw(runtime) });
    }
}

#[no_mangle]
/// # Safety
///
/// `runtime` must be null or point to a live runtime for this call's duration.
pub unsafe extern "C" fn triplex_media_epoch(runtime: *const NativeMediaRuntime) -> u32 {
    // Safety: checked before dereference; lifetime is an FFI contract.
    unsafe { runtime.as_ref() }.map_or(0, NativeMediaRuntime::epoch)
}

#[no_mangle]
/// # Safety
///
/// `runtime` must point to a live runtime and `samples` must expose at least
/// `sample_count` readable `i16` values for this call's duration.
pub unsafe extern "C" fn triplex_media_capture_push(
    runtime: *const NativeMediaRuntime,
    samples: *const i16,
    sample_count: usize,
    mono_ns: i64,
    rtp_timestamp: u32,
) -> bool {
    let (Some(runtime), true) = (unsafe { runtime.as_ref() }, !samples.is_null()) else {
        return false;
    };
    if sample_count > FRAME_SAMPLES {
        return false;
    }
    // Safety: caller provides `sample_count` readable samples for this call.
    let samples = unsafe { core::slice::from_raw_parts(samples, sample_count) };
    runtime.push_capture(samples, mono_ns, rtp_timestamp)
}

#[no_mangle]
/// # Safety
///
/// `runtime` must point to a live runtime. `samples_out` must expose at least
/// `sample_capacity` writable `i16` values; `header_out` may be null or writable.
pub unsafe extern "C" fn triplex_media_capture_pop(
    runtime: *const NativeMediaRuntime,
    samples_out: *mut i16,
    sample_capacity: usize,
    header_out: *mut FrameHeader,
) -> usize {
    let Some(runtime) = (unsafe { runtime.as_ref() }) else {
        return 0;
    };
    if samples_out.is_null() || sample_capacity < FRAME_SAMPLES {
        return 0;
    }
    let Some(slot) = runtime.pop_capture() else {
        return 0;
    };
    let count = usize::from(slot.header.len).min(FRAME_SAMPLES);
    // Safety: output has been validated for at least FRAME_SAMPLES entries.
    unsafe { ptr::copy_nonoverlapping(slot.samples.as_ptr(), samples_out, count) };
    if let Some(header) = unsafe { header_out.as_mut() } {
        *header = slot.header;
    }
    count
}

#[no_mangle]
/// # Safety
///
/// `runtime` must point to a live runtime and `samples` must expose at least
/// `sample_count` readable `i16` values for this call's duration.
pub unsafe extern "C" fn triplex_media_synth_push(
    runtime: *const NativeMediaRuntime,
    samples: *const i16,
    sample_count: usize,
    mono_ns: i64,
    epoch: u32,
    flags: u16,
    stream: u8,
) -> bool {
    let (Some(runtime), true) = (unsafe { runtime.as_ref() }, !samples.is_null()) else {
        return false;
    };
    if sample_count > FRAME_SAMPLES {
        return false;
    }
    let stream = match stream {
        value if value == Stream::Synth as u8 => Stream::Synth,
        value if value == Stream::Reflex as u8 => Stream::Reflex,
        _ => return false,
    };
    // Safety: caller provides `sample_count` readable samples for this call.
    let samples = unsafe { core::slice::from_raw_parts(samples, sample_count) };
    runtime.push_synth(samples, mono_ns, epoch, flags, stream)
}

#[no_mangle]
/// # Safety
///
/// `runtime` must point to a live runtime. `samples_out` must expose at least
/// `sample_capacity` writable `i16` values; `header_out` may be null or writable.
pub unsafe extern "C" fn triplex_media_synth_pop(
    runtime: *const NativeMediaRuntime,
    samples_out: *mut i16,
    sample_capacity: usize,
    header_out: *mut FrameHeader,
    mono_ns: i64,
) -> usize {
    let Some(runtime) = (unsafe { runtime.as_ref() }) else {
        return 0;
    };
    if samples_out.is_null() || sample_capacity < FRAME_SAMPLES {
        return 0;
    }
    let slot = runtime.pop_synth_or_silence(mono_ns);
    // Safety: output has been validated for at least FRAME_SAMPLES entries.
    unsafe {
        ptr::copy_nonoverlapping(slot.samples.as_ptr(), samples_out, FRAME_SAMPLES);
    }
    if let Some(header) = unsafe { header_out.as_mut() } {
        *header = slot.header;
    }
    FRAME_SAMPLES
}

#[no_mangle]
/// # Safety
///
/// `runtime` must be null or point to a live runtime for this call's duration.
pub unsafe extern "C" fn triplex_media_interrupt(runtime: *const NativeMediaRuntime) -> u32 {
    unsafe { runtime.as_ref() }.map_or(0, NativeMediaRuntime::interrupt)
}

#[no_mangle]
/// # Safety
///
/// `runtime` must be null or point to a live runtime for this call's duration.
pub unsafe extern "C" fn triplex_media_observe_rtp(
    runtime: *const NativeMediaRuntime,
    direction: u8,
    sequence: u16,
    timestamp: u32,
    packet_bytes: u32,
    arrival_mono_ns: i64,
    clock_rate_hz: u32,
) -> bool {
    unsafe { runtime.as_ref() }.is_some_and(|runtime| {
        runtime.observe_rtp(
            direction,
            sequence,
            timestamp,
            packet_bytes,
            arrival_mono_ns,
            clock_rate_hz,
        )
    })
}

#[no_mangle]
/// # Safety
///
/// `runtime` must point to a live runtime and `output` must point to writable
/// storage for one `MediaMetrics` value.
pub unsafe extern "C" fn triplex_media_metrics_snapshot(
    runtime: *const NativeMediaRuntime,
    output: *mut MediaMetrics,
) -> bool {
    let (Some(runtime), Some(output)) = (unsafe { runtime.as_ref() }, unsafe { output.as_mut() })
    else {
        return false;
    };
    *output = runtime.snapshot();
    true
}
