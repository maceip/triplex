//! Test-support allocation guard for RT paths (RUNTIME_INVARIANTS.md §6).
//!
//! Install `RtGuardAlloc` with `#[global_allocator]` in a test binary, tag
//! the thread that exercises RT code, and assert `violations() == 0` after.
//! The guard counts rather than aborts so failures surface as ordinary test
//! assertions with context.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::sync::atomic::{AtomicU64, Ordering};

static VIOLATIONS: AtomicU64 = AtomicU64::new(0);

thread_local! {
    static RT_TAGGED: Cell<bool> = const { Cell::new(false) };
}

/// Mark the calling thread as an RT context; allocations while tagged count
/// as violations.
pub fn tag_rt_thread(tagged: bool) {
    RT_TAGGED.with(|cell| cell.set(tagged));
}

pub fn violations() -> u64 {
    VIOLATIONS.load(Ordering::Relaxed)
}

/// Counting wrapper around the system allocator.
pub struct RtGuardAlloc;

fn note() {
    let tagged = RT_TAGGED.try_with(Cell::get).unwrap_or(false);
    if tagged {
        VIOLATIONS.fetch_add(1, Ordering::Relaxed);
    }
}

// Safety: delegates directly to `System`; only adds wait-free bookkeeping.
unsafe impl GlobalAlloc for RtGuardAlloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        note();
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        note();
        unsafe { System.dealloc(ptr, layout) }
    }
}
