package dev.triplex.speech.reasoner

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.triplex.dialogue.CallReasoner
import dev.triplex.dialogue.SpokenReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device call reasoner backed by Gemini Nano 4 Full via AICore / ML Kit
 * GenAI Prompt API (`ModelPreference.FULL`).
 *
 * The conversation this feeds lives in `dev.triplex.dialogue.CallDialogue`.
 * This class does exactly two things: get one reply out of the model, and be
 * honest when it cannot.
 *
 * AICore today is batch `generateContent`. Perceived TTFB is cut on the TTS
 * side (Inflect clause streaming). If Nano becomes the bottleneck, the next
 * harness is LiteRT-LM — not a separate llama.cpp stack — with token streaming
 * into the same [SpokenReply] → [CallVoice] path.
 */
@Singleton
class AiCoreCallReasoner @Inject constructor() : CallReasoner {
    private val client: GenerativeModel by lazy {
        Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.PREVIEW
                    preference = ModelPreference.FULL
                }
            },
        )
    }

    private val availabilityLock = Mutex()

    /**
     * Cached once the model is confirmed present.
     *
     * Only the positive result is cached, and only for the process. A status
     * round-trip costs tens of milliseconds, which on a live call is dead air
     * between the caller finishing a sentence and the agent starting one; a
     * model that is present does not become absent mid-call, so asking twice
     * buys nothing. A *negative* result is not cached — a download that
     * finishes between two calls should be picked up.
     */
    @Volatile
    private var confirmedAvailable = false

    override suspend fun available(): Boolean {
        if (confirmedAvailable) return true
        return withContext(Dispatchers.IO) {
            // Serialized so two calls arriving together do not both start the
            // same multi-hundred-megabyte download.
            availabilityLock.withLock {
                if (confirmedAvailable) return@withLock true
                val available = runCatching { resolveStatus() }
                    .onFailure { Timber.w(it, "AICore Nano Full status check failed") }
                    .getOrDefault(false)
                confirmedAvailable = available
                available
            }
        }
    }

    private suspend fun resolveStatus(): Boolean = when (val status = client.checkStatus()) {
        FeatureStatus.AVAILABLE -> true
        FeatureStatus.DOWNLOADABLE -> {
            Timber.i("Nano Full downloadable; starting download")
            client.download().collect { /* progress logged by AICore */ }
            client.checkStatus() == FeatureStatus.AVAILABLE
        }
        FeatureStatus.DOWNLOADING -> {
            Timber.i("Nano Full still downloading")
            false
        }
        else -> {
            Timber.w("Nano Full unavailable: %s", status)
            false
        }
    }

    /**
     * Produces one spoken reply for the live call.
     *
     * Fail-closed by contract: this throws rather than returning filler, and
     * what a call should do about that — ask the caller to repeat, or close
     * honestly — is [dev.triplex.dialogue.FallbackPolicy]'s decision, not this
     * class's. Model output goes through [SpokenReply] before it leaves here,
     * so the markdown and stage directions Nano occasionally emits cannot reach
     * the speech engine.
     */
    override suspend fun reply(
        systemInstructions: String,
        callerText: String,
        history: List<Pair<String, String>>,
    ): String = withContext(Dispatchers.IO) {
        check(available()) {
            "Gemini Nano 4 Full is not available on this device via AICore"
        }
        val historyBlock = history.takeLast(HISTORY_TURNS).joinToString("\n") { (role, text) ->
            "$role: $text"
        }
        val prompt = buildString {
            appendLine(systemInstructions.trim())
            appendLine()
            appendLine("Rules:")
            appendLine("- Reply in 1-2 short spoken sentences.")
            appendLine("- No markdown, bullets, or stage directions.")
            appendLine("- Stay in character as the user's phone agent.")
            appendLine()
            if (historyBlock.isNotBlank()) {
                appendLine("Recent turns:")
                appendLine(historyBlock)
                appendLine()
            }
            appendLine("Caller just said: $callerText")
            append("Your spoken reply:")
        }
        val response = client.generateContent(prompt)
        val raw = response.candidates.firstOrNull()?.text.orEmpty()
        checkNotNull(SpokenReply.sanitize(raw)) {
            "Nano returned nothing speakable"
        }
    }

    private companion object {
        /** Matches the window `CallDialogue` keeps, so the prompt is not padded. */
        const val HISTORY_TURNS = 6
    }
}
