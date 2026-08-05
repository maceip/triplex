//! Generation-epoch domain and validity tokens (RUNTIME_INVARIANTS.md §2.1).
//!
//! The epoch atomic lives inside `NativeMediaRuntime`: the RT mixer's O(1)
//! per-frame gate and this domain must read the same word, so there is
//! exactly one. `ASR_REV` lives here. Single-writer discipline (§2.1):
//! the TurnController alone bumps the epoch, the ASR host alone bumps the
//! revision; everyone else Acquire-reads.

use core::sync::atomic::{AtomicU32, Ordering};
use triplex_native_media::NativeMediaRuntime;

/// Sentinel revision for authoritative post-commit work: validity depends on
/// the epoch alone.
pub const REV_FINAL: u32 = u32::MAX;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Token {
    pub epoch: u32,
    pub rev: u32,
}

pub struct EpochDomain<'m> {
    media: &'m NativeMediaRuntime,
    asr_rev: AtomicU32,
}

impl<'m> EpochDomain<'m> {
    pub fn new(media: &'m NativeMediaRuntime) -> Self {
        Self {
            media,
            asr_rev: AtomicU32::new(0),
        }
    }

    #[inline]
    pub fn current_epoch(&self) -> u32 {
        self.media.epoch()
    }

    #[inline]
    pub fn current_rev(&self) -> u32 {
        self.asr_rev.load(Ordering::Acquire)
    }

    /// §3.4 step 1 — the cancellation broadcast, which also arms the mixer
    /// flush (step 3 travels inside `NativeMediaRuntime::interrupt`).
    /// Sole caller: the TurnController.
    #[inline]
    pub fn bump_epoch(&self) -> u32 {
        self.media.interrupt()
    }

    /// Sole caller: the ASR host, once per emitted partial revision.
    /// Monotonic for the whole call; never reset (§2.1) — within-turn
    /// speculation self-invalidates without touching the epoch.
    #[inline]
    pub fn bump_rev(&self) -> u32 {
        self.asr_rev.fetch_add(1, Ordering::AcqRel) + 1
    }

    #[inline]
    pub fn snapshot(&self) -> Token {
        Token {
            epoch: self.current_epoch(),
            rev: self.current_rev(),
        }
    }

    #[inline]
    pub fn token_final(&self) -> Token {
        Token {
            epoch: self.current_epoch(),
            rev: REV_FINAL,
        }
    }

    /// Exact-equality validity (§2.1). The two loads are not one atomic
    /// snapshot; a torn pair can only produce a spurious invalidation, which
    /// is the safe direction.
    #[inline]
    pub fn valid(&self, token: Token) -> bool {
        token.epoch == self.current_epoch()
            && (token.rev == REV_FINAL || token.rev == self.current_rev())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use triplex_native_media::ROUTE_DIRECT_PJSIP;

    #[test]
    fn tokens_invalidate_on_rev_and_epoch_changes() {
        let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let domain = EpochDomain::new(&media);

        let speculative = domain.snapshot();
        assert!(domain.valid(speculative));
        domain.bump_rev();
        assert!(!domain.valid(speculative), "rev change invalidates speculation");

        let authoritative = domain.token_final();
        assert!(domain.valid(authoritative));
        domain.bump_rev();
        assert!(domain.valid(authoritative), "REV_FINAL ignores rev churn");
        domain.bump_epoch();
        assert!(!domain.valid(authoritative), "epoch bump invalidates everything");
        assert!(domain.valid(domain.token_final()));
    }
}
