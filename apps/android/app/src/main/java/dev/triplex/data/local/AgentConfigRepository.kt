package dev.triplex.data.local

import dev.triplex.data.repository.Result
import dev.triplex.domain.model.AutomationVoicePolicy
import dev.triplex.voice.VoiceProfileReadiness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the agent's call behavior (reskin.md §2.4).
 *
 * ## Fail-closed cloned voice
 *
 * `RUNTIME_INVARIANTS.md` §7.6: a missing clone profile **fails closed and
 * never silently downgrades to the branded voice**, because a silent
 * substitution is a consent bug, not a cosmetic one. That invariant is enforced
 * here, in the one place both the setup screens and the call paths go through:
 *
 * * [setInboundVoicePolicy] / [setOutboundDefaultVoicePolicy] refuse to *store*
 *   `CLONED` unless the gateway reports `synthesis_ready`, so an unusable
 *   selection cannot be persisted in the first place;
 * * [resolveInboundVoicePolicy] / [resolveOutboundVoicePolicy] re-check at call
 *   time and return [Result.Error]. They never return `PRESET` as a
 *   consolation — a caller that wanted the user's voice and cannot have it must
 *   stop, not substitute.
 *
 * There is no fallback path in this file, and adding one would be the bug.
 */
@Singleton
class AgentConfigRepository @Inject constructor(
    private val store: AgentConfigStore,
    private val voiceReadiness: VoiceProfileReadiness,
) {

    val config: Flow<AgentConfig> = store.values.map(AgentConfigCodec::decode)

    val inbound: Flow<AgentInboundConfig> =
        config.map { it.inbound }.distinctUntilChanged()

    val outbound: Flow<AgentOutboundConfig> =
        config.map { it.outbound }.distinctUntilChanged()

    /** One-shot read for callers already inside a coroutine (the SIP engine). */
    suspend fun inboundConfig(): AgentInboundConfig = inbound.first()

    suspend fun outboundConfig(): AgentOutboundConfig = outbound.first()

    suspend fun setAutoAnswerAll(enabled: Boolean) = put(KEY_AUTO_ANSWER, enabled.toString())

    /** A blank greeting clears the override and restores the shipped text. */
    suspend fun setGreetingText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) remove(KEY_GREETING) else put(KEY_GREETING, trimmed)
    }

    suspend fun setAutomationEnabled(automationId: String, enabled: Boolean) {
        val current = inboundConfig().enabledAutomationIds
        val next = if (enabled) current + automationId else current - automationId
        put(KEY_ENABLED_AUTOMATIONS, AgentConfigCodec.encodeList(next.toList()))
    }

    suspend fun setQuickReplies(replies: List<String>) {
        val cleaned = replies.map(String::trim).filter(String::isNotEmpty)
        put(KEY_QUICK_REPLIES, AgentConfigCodec.encodeList(cleaned))
    }

    /**
     * Turns the media handoff on. See [AgentInboundConfig.bridgeCallsToPhone]
     * for why this is not on by default.
     */
    suspend fun setBridgeCallsToPhone(enabled: Boolean) =
        put(KEY_BRIDGE_CALLS, enabled.toString())

    suspend fun setConfirmBeforeDial(enabled: Boolean) =
        put(KEY_CONFIRM_BEFORE_DIAL, enabled.toString())

    /** @return [Result.Error] when `CLONED` is asked for without a ready profile. */
    suspend fun setInboundVoicePolicy(policy: AutomationVoicePolicy): Result<Unit> =
        requireSelectable(policy) { put(KEY_INBOUND_VOICE_POLICY, policy.name) }

    suspend fun setOutboundDefaultVoicePolicy(policy: AutomationVoicePolicy): Result<Unit> =
        requireSelectable(policy) { put(KEY_OUTBOUND_VOICE_POLICY, policy.name) }

    /**
     * The voice an inbound agent call must use, or an error explaining why the
     * call cannot proceed as configured.
     */
    suspend fun resolveInboundVoicePolicy(): Result<AutomationVoicePolicy> =
        resolve(inboundConfig().voicePolicy)

    suspend fun resolveOutboundVoicePolicy(): Result<AutomationVoicePolicy> =
        resolve(outboundConfig().defaultVoicePolicy)

    /** Whether the cloned option may be offered at all. */
    suspend fun clonedVoiceAvailable(): Boolean = voiceReadiness.synthesisReady()

    private suspend fun resolve(policy: AutomationVoicePolicy): Result<AutomationVoicePolicy> =
        when {
            policy != AutomationVoicePolicy.CLONED -> Result.Success(policy)
            voiceReadiness.synthesisReady() -> Result.Success(AutomationVoicePolicy.CLONED)
            else -> Result.Error(CLONED_UNAVAILABLE)
        }

    private suspend fun requireSelectable(
        policy: AutomationVoicePolicy,
        write: suspend () -> Unit,
    ): Result<Unit> {
        if (policy == AutomationVoicePolicy.CLONED && !voiceReadiness.synthesisReady()) {
            return Result.Error(CLONED_UNAVAILABLE)
        }
        write()
        return Result.Success(Unit)
    }

    private suspend fun put(key: String, value: String) = store.edit { it + (key to value) }

    private suspend fun remove(key: String) = store.edit { it - key }

    internal companion object {
        const val CLONED_UNAVAILABLE =
            "Your cloned voice is not ready. Finish voice setup before selecting it — " +
                "Triplex will not substitute another voice on your behalf."

        const val KEY_AUTO_ANSWER = "inbound.autoAnswerAll"
        const val KEY_GREETING = "inbound.greetingText"
        const val KEY_ENABLED_AUTOMATIONS = "inbound.enabledAutomationIds"
        const val KEY_INBOUND_VOICE_POLICY = "inbound.voicePolicy"
        const val KEY_QUICK_REPLIES = "inbound.quickReplies"
        const val KEY_BRIDGE_CALLS = "inbound.bridgeCallsToPhone"
        const val KEY_OUTBOUND_VOICE_POLICY = "outbound.defaultVoicePolicy"
        const val KEY_CONFIRM_BEFORE_DIAL = "outbound.confirmBeforeDial"
    }
}

/**
 * Turns the stored string map into [AgentConfig] and back.
 *
 * Pure and separate from storage so the defaults are exercised by the same code
 * on a fresh install and in a JVM test. An absent key means "never set" and
 * yields the seeded default; an empty encoded list means "set to nothing",
 * which is why the two are not collapsed.
 */
internal object AgentConfigCodec {

    private const val SEPARATOR = "\u001F"

    fun decode(values: Map<String, String>): AgentConfig {
        val defaults = AgentInboundConfig()
        val outboundDefaults = AgentOutboundConfig()
        return AgentConfig(
            inbound = AgentInboundConfig(
                autoAnswerAll = values.boolean(
                    AgentConfigRepository.KEY_AUTO_ANSWER,
                    defaults.autoAnswerAll,
                ),
                greetingText = values[AgentConfigRepository.KEY_GREETING]
                    ?.takeIf(String::isNotBlank)
                    ?: defaults.greetingText,
                enabledAutomationIds = values[AgentConfigRepository.KEY_ENABLED_AUTOMATIONS]
                    ?.let { decodeList(it).toSet() }
                    ?: defaults.enabledAutomationIds,
                voicePolicy = values.voicePolicy(
                    AgentConfigRepository.KEY_INBOUND_VOICE_POLICY,
                    defaults.voicePolicy,
                ),
                quickReplies = values[AgentConfigRepository.KEY_QUICK_REPLIES]
                    ?.let(::decodeList)
                    ?: defaults.quickReplies,
            ),
            outbound = AgentOutboundConfig(
                defaultVoicePolicy = values.voicePolicy(
                    AgentConfigRepository.KEY_OUTBOUND_VOICE_POLICY,
                    outboundDefaults.defaultVoicePolicy,
                ),
                confirmBeforeDial = values.boolean(
                    AgentConfigRepository.KEY_CONFIRM_BEFORE_DIAL,
                    outboundDefaults.confirmBeforeDial,
                ),
            ),
        )
    }

    fun encodeList(values: List<String>): String = values.joinToString(SEPARATOR)

    fun decodeList(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split(SEPARATOR).filter(String::isNotEmpty)

    private fun Map<String, String>.boolean(key: String, fallback: Boolean): Boolean =
        this[key]?.toBooleanStrictOrNull() ?: fallback

    private fun Map<String, String>.voicePolicy(
        key: String,
        fallback: AutomationVoicePolicy,
    ): AutomationVoicePolicy = this[key]
        ?.let { name -> AutomationVoicePolicy.entries.firstOrNull { it.name == name } }
        ?: fallback
}
