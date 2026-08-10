package dev.triplex.speech.reasoner

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device call reasoner backed by Gemini Nano 4 Full via AICore / ML Kit
 * GenAI Prompt API (`ModelPreference.FULL`).
 */
@Singleton
class AiCoreCallReasoner @Inject constructor() {
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

    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (val status = client.checkStatus()) {
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
        }.onFailure {
            Timber.w(it, "AICore Nano Full status check failed")
        }.getOrDefault(false)
    }

    /**
     * Produces a short spoken reply for the live call. Fail-closed: throws if
     * Nano is unavailable (this SKU is expected to have Gemini Nano 4 Full).
     */
    suspend fun reply(
        systemInstructions: String,
        callerText: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        check(available()) {
            "Gemini Nano 4 Full is not available on this device via AICore"
        }
        val historyBlock = history.takeLast(6).joinToString("\n") { (role, text) ->
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
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        check(text.isNotBlank()) { "Nano returned an empty reply" }
        text.lineSequence().first { it.isNotBlank() }.take(280)
    }
}
