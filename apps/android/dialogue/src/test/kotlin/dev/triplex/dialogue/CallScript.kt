package dev.triplex.dialogue

import kotlinx.coroutines.delay

/**
 * A phone call, played back.
 *
 * The conversation tests in this module are end-to-end over the real
 * [CallDialogue]: the real budget arithmetic, the real fallback policy, the
 * real [SpokenReply] sanitizer, the real history window. Two things cannot be
 * real on a build machine — the SIP media path and Gemini Nano — so both are
 * replayed here from material captured on a device:
 *
 * * [ScriptedCaller] plays the other party's side of a conversation that
 *   actually happens on phones, one utterance per turn, and can be told to
 *   barge in on a specific agent turn.
 * * [ReplayReasoner] returns model output recorded from Gemini Nano on a Pixel,
 *   keyed by what the caller said — including the untidy ones, with the
 *   markdown and the preambles left in, because those are what the sanitizer
 *   exists for.
 *
 * What that buys: every assertion below is about code that ships. When a turn
 * is skipped, a budget is off by one, a fallback repeats itself, or a model
 * reply reaches the wire with an asterisk in it, these tests fail. What it does
 * not buy: proof that AICore returns anything at all on a given handset. That
 * is what `AiCoreReasonerDeviceTest` in `:app` is for, and it needs a phone.
 */
class ScriptedCaller(
    private val utterances: List<String>,
    /**
     * Agent turn numbers the caller talks over. Turn 0 is the opening; turn N
     * is the Nth reasoned reply.
     */
    private val bargeInOnTurns: Set<Int> = emptySet(),
    /** Turns after which the caller says nothing at all. */
    private val silentAfterTurn: Int = Int.MAX_VALUE,
    /** Simulated model-independent transport latency per utterance. */
    private val speakLatencyMs: Long = 0L,
    /**
     * Called once per completed exchange, so a test that cares about the
     * wall-clock budget can move [TestClock] by the length of a real turn
     * instead of sleeping for it.
     */
    private val onExchange: (() -> Unit)? = null,
) : DialogueTransport {

    private var next = 0
    private var agentTurnsSpoken = 0
    private var active = true

    /** Everything the agent actually put on the wire, in order. */
    val spoken = mutableListOf<String>()

    /** Utterances the caller cut off, as the agent tried to say them. */
    val interrupted = mutableListOf<String>()

    /** Ends the call, as a caller hanging up would. */
    fun hangUp() {
        active = false
    }

    override fun isActive(): Boolean = active

    override suspend fun speak(text: String): SpeechResult {
        if (!active) return SpeechResult.CALL_ENDED
        if (speakLatencyMs > 0) delay(speakLatencyMs)
        spoken += text
        val turn = agentTurnsSpoken
        agentTurnsSpoken += 1
        return if (turn in bargeInOnTurns) {
            interrupted += text
            SpeechResult.INTERRUPTED
        } else {
            SpeechResult.DELIVERED
        }
    }

    override suspend fun awaitCallerReply(): String? {
        if (!active) return null
        onExchange?.invoke()
        if (next >= utterances.size || next >= silentAfterTurn) return null
        return utterances[next++]
    }
}

/**
 * A transport whose synthesis is broken — the media path is up, but nothing
 * the agent says reaches the caller after [failFromTurn].
 */
class FailingSpeechTransport(
    private val failFromTurn: Int,
    private val utterances: List<String>,
) : DialogueTransport {
    private var agentTurns = 0
    private var next = 0

    override fun isActive(): Boolean = true

    override suspend fun speak(text: String): SpeechResult {
        val turn = agentTurns
        agentTurns += 1
        return if (turn >= failFromTurn) SpeechResult.FAILED else SpeechResult.DELIVERED
    }

    override suspend fun awaitCallerReply(): String? =
        if (next < utterances.size) utterances[next++] else null
}

/**
 * Replays recorded Gemini Nano output.
 *
 * @param responses raw model output keyed by a distinctive fragment of the
 *   caller utterance it answered. Recorded verbatim, formatting warts included.
 * @param failOnTurns reasoned turns where AICore raised instead of answering —
 *   the transient `IllegalStateException` the real client throws when the model
 *   is busy or the request is dropped.
 */
class ReplayReasoner(
    private val responses: Map<String, String>,
    private val failOnTurns: Set<Int> = emptySet(),
    private val availableResult: Boolean = true,
    private val availabilityThrows: Boolean = false,
    /** Recorded first-token latency, so turn timing is exercised. */
    private val latencyMs: Long = 0L,
    /**
     * How long [available] blocks before answering. Models the production
     * path that starts a multi-hundred-megabyte Nano download when the
     * feature is only DOWNLOADABLE.
     */
    private val availabilityLatencyMs: Long = 0L,
) : CallReasoner {

    /** Every (instructions, callerText, history) the loop actually sent. */
    val requests = mutableListOf<Request>()

    data class Request(
        val instructions: String,
        val callerText: String,
        val history: List<Pair<String, String>>,
    )

    private var turn = 0
    var availabilityChecks = 0
        private set

    override suspend fun available(): Boolean {
        availabilityChecks += 1
        if (availabilityLatencyMs > 0) delay(availabilityLatencyMs)
        if (availabilityThrows) error("AICore feature status unavailable")
        return availableResult
    }

    override suspend fun reply(
        systemInstructions: String,
        callerText: String,
        history: List<Pair<String, String>>,
    ): String {
        turn += 1
        requests += Request(systemInstructions, callerText, history)
        if (latencyMs > 0) delay(latencyMs)
        if (turn in failOnTurns) {
            error("AICore inference failed: model busy")
        }
        val key = responses.keys.firstOrNull { callerText.contains(it, ignoreCase = true) }
            ?: error("No recorded Nano response for: $callerText")
        return responses.getValue(key)
    }
}

/** Collects [DialogueEvent]s so a test can assert on what the call reported. */
class RecordingObserver : DialogueObserver {
    val events = mutableListOf<DialogueEvent>()

    override fun onEvent(event: DialogueEvent) {
        events += event
    }

    inline fun <reified T : DialogueEvent> ofType(): List<T> = events.filterIsInstance<T>()
}

/**
 * A clock the test drives, so a three-minute budget is exercised in a
 * millisecond and the wall clock of the build machine never decides an
 * assertion.
 */
class TestClock(private var nowMs: Long = 0L) : () -> Long {
    override fun invoke(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}
