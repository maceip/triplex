package dev.triplex.ui.call.shared

/**
 * The sentence a surface must show once the user has taken a SIP leg.
 *
 * Kept here rather than in either surface because both the incoming sheet and
 * the in-call agent panel show the same call in the same state — one before the
 * user acts and one after — and two copies of this wording would eventually
 * disagree about what the phone is doing. `RUNTIME_INVARIANTS.md` §7.6's rule
 * against silent substitution has a presentation twin: never let the UI imply an
 * audio path that does not exist.
 */
const val AGENT_ONLY_AUDIO_NOTICE: String =
    "This phone's microphone and earpiece are not attached to this call. " +
        "Triplex remains the only voice on it — you can keep replying through " +
        "the agent, or hang up."
