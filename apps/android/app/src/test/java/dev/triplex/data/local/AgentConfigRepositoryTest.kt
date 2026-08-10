package dev.triplex.data.local

import dev.triplex.data.repository.Result
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationVoicePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigRepositoryTest {

    private val store = InMemoryAgentConfigStore()
    private val readiness = FakeVoiceProfileReadiness(ready = false)
    private val repository = AgentConfigRepository(store, readiness)

    @Test
    fun `a fresh install reads the shipped defaults`() = runTest {
        val inbound = repository.inboundConfig()

        assertTrue(inbound.autoAnswerAll)
        assertEquals(AgentConfigDefaults.GREETING_TEXT, inbound.greetingText)
        assertEquals(
            AutomationCatalog.inbound.map { it.id }.toSet(),
            inbound.enabledAutomationIds,
        )
        assertEquals(AgentConfigDefaults.QUICK_REPLIES, inbound.quickReplies)
        assertEquals(AutomationVoicePolicy.PRESET, inbound.voicePolicy)

        val outbound = repository.outboundConfig()
        assertEquals(AutomationVoicePolicy.PRESET, outbound.defaultVoicePolicy)
        assertTrue(outbound.confirmBeforeDial)
    }

    @Test
    fun `writes round-trip`() = runTest {
        repository.setAutoAnswerAll(false)
        repository.setGreetingText("  Triplex here. Who is this?  ")
        repository.setQuickReplies(listOf(" Call back ", "", "Text me"))
        repository.setConfirmBeforeDial(false)

        val inbound = repository.inboundConfig()
        assertFalse(inbound.autoAnswerAll)
        assertEquals("Triplex here. Who is this?", inbound.greetingText)
        assertEquals(listOf("Call back", "Text me"), inbound.quickReplies)
        assertFalse(repository.outboundConfig().confirmBeforeDial)
    }

    @Test
    fun `an emptied list is not an absent list`() = runTest {
        repository.setQuickReplies(emptyList())
        assertEquals(emptyList<String>(), repository.inboundConfig().quickReplies)

        AutomationCatalog.inbound.forEach { repository.setAutomationEnabled(it.id, false) }
        assertEquals(emptySet<String>(), repository.inboundConfig().enabledAutomationIds)
    }

    @Test
    fun `blanking the greeting restores the shipped text`() = runTest {
        repository.setGreetingText("Custom opening")
        assertEquals("Custom opening", repository.inboundConfig().greetingText)

        repository.setGreetingText("   ")

        assertEquals(AgentConfigDefaults.GREETING_TEXT, repository.inboundConfig().greetingText)
        assertFalse(AgentConfigRepository.KEY_GREETING in store.snapshot())
    }

    @Test
    fun `an automation can be turned back on`() = runTest {
        val id = AutomationCatalog.BookZoomMeeting.id
        repository.setAutomationEnabled(id, false)
        assertFalse(id in repository.inboundConfig().enabledAutomationIds)

        repository.setAutomationEnabled(id, true)

        assertTrue(id in repository.inboundConfig().enabledAutomationIds)
    }

    @Test
    fun `the cloned voice cannot be stored without a ready profile`() = runTest {
        val result = repository.setInboundVoicePolicy(AutomationVoicePolicy.CLONED)

        assertTrue(result is Result.Error)
        assertEquals(AgentConfigRepository.CLONED_UNAVAILABLE, (result as Result.Error).message)
        // Refused means nothing was written, not "written and ignored later".
        assertFalse(AgentConfigRepository.KEY_INBOUND_VOICE_POLICY in store.snapshot())
        assertEquals(AutomationVoicePolicy.PRESET, repository.inboundConfig().voicePolicy)
    }

    @Test
    fun `the outbound default is gated the same way`() = runTest {
        val refused = repository.setOutboundDefaultVoicePolicy(AutomationVoicePolicy.CLONED)
        assertTrue(refused is Result.Error)
        assertEquals(
            AutomationVoicePolicy.PRESET,
            repository.outboundConfig().defaultVoicePolicy,
        )

        readiness.ready = true
        val accepted = repository.setOutboundDefaultVoicePolicy(AutomationVoicePolicy.CLONED)

        assertTrue(accepted is Result.Success)
        assertEquals(
            AutomationVoicePolicy.CLONED,
            repository.outboundConfig().defaultVoicePolicy,
        )
    }

    @Test
    fun `the preset voice is always selectable`() = runTest {
        assertTrue(repository.setInboundVoicePolicy(AutomationVoicePolicy.PRESET) is Result.Success)
        assertFalse(repository.clonedVoiceAvailable())
    }

    @Test
    fun `resolving a cloned voice that went away fails instead of substituting`() = runTest {
        readiness.ready = true
        repository.setInboundVoicePolicy(AutomationVoicePolicy.CLONED)
        repository.setOutboundDefaultVoicePolicy(AutomationVoicePolicy.CLONED)

        // The profile is revoked, deleted, or the gateway is unreachable.
        readiness.ready = false

        val inbound = repository.resolveInboundVoicePolicy()
        val outbound = repository.resolveOutboundVoicePolicy()

        assertTrue(inbound is Result.Error)
        assertTrue(outbound is Result.Error)
        assertEquals(AgentConfigRepository.CLONED_UNAVAILABLE, (outbound as Result.Error).message)
        // Explicitly not Success(PRESET): a silent downgrade is the consent bug.
        assertFalse(inbound is Result.Success)
    }

    @Test
    fun `resolving succeeds when the profile is ready`() = runTest {
        readiness.ready = true
        repository.setInboundVoicePolicy(AutomationVoicePolicy.CLONED)

        val resolved = repository.resolveInboundVoicePolicy()

        assertTrue(resolved is Result.Success)
        assertEquals(
            AutomationVoicePolicy.CLONED,
            (resolved as Result.Success<AutomationVoicePolicy>).data,
        )
    }

    @Test
    fun `resolving a preset voice never consults the gateway`() = runTest {
        val resolved = repository.resolveOutboundVoicePolicy()

        assertTrue(resolved is Result.Success)
        assertEquals(
            AutomationVoicePolicy.PRESET,
            (resolved as Result.Success<AutomationVoicePolicy>).data,
        )
    }
}
