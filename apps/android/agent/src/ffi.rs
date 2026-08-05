//! C FFI for the agent session, exported from the same cdylib that carries
//! the `triplex_media_*` surface (this crate links triplex-native-media, so
//! its `#[no_mangle]` exports ride along).

use std::sync::atomic::Ordering;

use triplex_native_media::NativeMediaRuntime;

use crate::session::{self, AgentSessionHandle, SessionConfig};

#[repr(C)]
pub struct TriplexAgentStatus {
    pub abi_version: u16,
    pub struct_size: u16,
    pub state: u32,
    pub epoch: u32,
    pub reserved: u32,
    pub vad_onsets: u64,
    pub turns_committed: u64,
    pub utterances_completed: u64,
    pub barge_ins: u64,
    pub egressed_samples: u64,
    pub voiced_samples: u64,
}

/// # Safety
/// `media` must be a live pointer from `triplex_media_create`, and it must
/// outlive the returned session (destroy the session first). The session
/// becomes the sole consumer of the runtime's capture and synth rings; do
/// not run it concurrently with a live call's media port.
#[no_mangle]
pub unsafe extern "C" fn triplex_agent_create(
    media: *const NativeMediaRuntime,
) -> *mut AgentSessionHandle {
    if media.is_null() {
        return std::ptr::null_mut();
    }
    // Safety: caller guarantees liveness and ordering per the contract above.
    let media: &'static NativeMediaRuntime = unsafe { &*media };
    let handle = session::spawn(media, SessionConfig::default());
    Box::into_raw(Box::new(handle))
}

/// # Safety
/// `handle` must be null or a live pointer from `triplex_agent_create`; no
/// other call may use it afterward.
#[no_mangle]
pub unsafe extern "C" fn triplex_agent_destroy(handle: *mut AgentSessionHandle) {
    if !handle.is_null() {
        // Safety: exclusive ownership transferred back; drop stops threads.
        drop(unsafe { Box::from_raw(handle) });
    }
}

/// # Safety
/// `handle` must be a live pointer from `triplex_agent_create` and `out`
/// must point to writable storage for one `TriplexAgentStatus`.
#[no_mangle]
pub unsafe extern "C" fn triplex_agent_status(
    handle: *const AgentSessionHandle,
    out: *mut TriplexAgentStatus,
) -> bool {
    let (Some(handle), Some(out)) = (unsafe { handle.as_ref() }, unsafe { out.as_mut() }) else {
        return false;
    };
    let status = handle.status();
    *out = TriplexAgentStatus {
        abi_version: 1,
        struct_size: core::mem::size_of::<TriplexAgentStatus>() as u16,
        state: status.state.load(Ordering::Acquire),
        epoch: status.epoch.load(Ordering::Relaxed),
        reserved: 0,
        vad_onsets: status.vad_onsets.load(Ordering::Relaxed),
        turns_committed: status.turns_committed.load(Ordering::Relaxed),
        utterances_completed: status.utterances_completed.load(Ordering::Relaxed),
        barge_ins: status.barge_ins.load(Ordering::Relaxed),
        egressed_samples: status.egressed_samples.load(Ordering::Relaxed),
        voiced_samples: status.voiced_samples.load(Ordering::Relaxed),
    };
    true
}
