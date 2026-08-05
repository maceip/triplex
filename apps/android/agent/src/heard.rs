//! Conservative heard-state retention (RUNTIME_INVARIANTS.md §3.6).
//!
//! Conservative means: retain only content we are *sure* the caller heard.
//! A frame counts as heard only if, even after adding the transit floor plus
//! the uncertainty margin to its egress time, it had reached the caller
//! before speech onset. The watermark then maps to text through the TTS
//! word-boundary alignment marks: retain through the last fully emitted word.

use triplex_native_media::agent::ledger::PlayoutRecord;

use crate::tts::AlignmentMark;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct HeardState {
    pub epoch: u32,
    pub interrupted: bool,
    /// Conservative count of utterance samples the caller heard.
    pub heard_samples: u64,
    /// Byte offset (into the utterance text) just past the last fully
    /// emitted word at or before the watermark.
    pub heard_text_end: u32,
    /// Start of the machine-readable unspoken suffix; equals
    /// `heard_text_end` — the next-epoch reasoner sees what was said and
    /// what was cut.
    pub unspoken_from: u32,
    /// Nothing was heard: drop the utterance entirely and reopen the user
    /// segment so the merged user turn reads as one utterance (§3.6).
    pub reopen_segment: bool,
}

/// Greatest heard utterance-sample offset for `epoch`, given speech onset at
/// `onset_mono_ns`. `min_one_way_ns` is the transit floor (RTCP RTT floor);
/// `margin_ns` covers uncertainty above it.
pub fn heard_watermark(
    records: &[PlayoutRecord],
    epoch: u32,
    onset_mono_ns: i64,
    min_one_way_ns: i64,
    margin_ns: i64,
) -> u64 {
    let mut watermark = 0_u64;
    for record in records.iter().filter(|r| r.epoch == epoch) {
        if record.egress_mono_ns + min_one_way_ns + margin_ns <= onset_mono_ns {
            watermark = watermark.max(record.utt_sample_off + u64::from(record.samples));
        }
    }
    watermark
}

/// Byte offset just past the last fully emitted word at or before the
/// watermark; 0 when no word completed.
pub fn heard_text_boundary(marks: &[AlignmentMark], epoch: u32, watermark_samples: u64) -> u32 {
    marks
        .iter()
        .filter(|m| m.epoch == epoch && m.utt_sample_off <= watermark_samples)
        .map(|m| m.text_off)
        .max()
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: i64 = 1_000_000;

    fn records() -> Vec<PlayoutRecord> {
        (0..3)
            .map(|i| PlayoutRecord {
                epoch: 5,
                seq: i,
                egress_mono_ns: i as i64 * 10 * MS,
                samples: 160,
                utt_sample_off: i * 160,
            })
            .collect()
    }

    fn marks() -> Vec<AlignmentMark> {
        [(100, 5), (300, 11), (450, 17)]
            .into_iter()
            .map(|(sample, text)| AlignmentMark {
                epoch: 5,
                utt_sample_off: sample,
                text_off: text,
            })
            .collect()
    }

    #[test]
    fn watermark_is_conservative_and_epoch_filtered() {
        // Cutoff = onset − (20 + 60) ms = 15 ms: frames at 0 and 10 ms count.
        let wm = heard_watermark(&records(), 5, 95 * MS, 20 * MS, 60 * MS);
        assert_eq!(wm, 320);
        // Onset almost immediately: nothing surely heard.
        assert_eq!(heard_watermark(&records(), 5, 5 * MS, 20 * MS, 60 * MS), 0);
        // Wrong epoch: nothing counts.
        assert_eq!(heard_watermark(&records(), 6, 95 * MS, 20 * MS, 60 * MS), 0);
    }

    #[test]
    fn boundary_maps_to_last_fully_emitted_word() {
        assert_eq!(heard_text_boundary(&marks(), 5, 320), 11);
        assert_eq!(heard_text_boundary(&marks(), 5, 99), 0);
        assert_eq!(heard_text_boundary(&marks(), 5, 10_000), 17);
        assert_eq!(heard_text_boundary(&marks(), 4, 320), 0);
    }
}
