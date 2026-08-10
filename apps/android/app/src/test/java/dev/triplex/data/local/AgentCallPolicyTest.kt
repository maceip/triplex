package dev.triplex.data.local

import dev.triplex.domain.model.AutomationCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions `TelephonyController` delegates instead of hard-coding.
 *
 * Each case goes through a real repository round-trip rather than a
 * hand-built config, so a setting the setup screen writes is provably the
 * setting the SIP engine reads.
 */
class AgentCallPolicyTest {

    private val store = InMemoryAgentConfigStore()
    private val repository = AgentConfigRepository(store, FakeVoiceProfileReadiness(ready = false))

    @Test
    fun `auto-answer is on by default and can be turned off`() = runTest {
        assertTrue(AgentCallPolicy.shouldAutoAnswer(repository.inboundConfig()))

        repository.setAutoAnswerAll(false)
        assertFalse(AgentCallPolicy.shouldAutoAnswer(repository.inboundConfig()))

        repository.setAutoAnswerAll(true)
        assertTrue(AgentCallPolicy.shouldAutoAnswer(repository.inboundConfig()))
    }

    @Test
    fun `the greeting is the shipped one until the user replaces it`() = runTest {
        assertEquals(
            AgentConfigDefaults.GREETING_TEXT,
            AgentCallPolicy.greeting(repository.inboundConfig()),
        )

        repository.setGreetingText("Hi, this is Ava's assistant.")

        assertEquals(
            "Hi, this is Ava's assistant.",
            AgentCallPolicy.greeting(repository.inboundConfig()),
        )
    }

    @Test
    fun `a blanked greeting never leaves the agent silent`() = runTest {
        repository.setGreetingText("Something")
        repository.setGreetingText("")

        assertEquals(
            AgentConfigDefaults.GREETING_TEXT,
            AgentCallPolicy.greeting(repository.inboundConfig()),
        )
    }

    @Test
    fun `an enabled automation resolves to its template and opening`() = runTest {
        val template = AgentCallPolicy.automationFor(
            AutomationCatalog.BookZoomMeeting.id,
            repository.inboundConfig(),
        )

        assertEquals(AutomationCatalog.BookZoomMeeting, template)
        assertTrue(requireNotNull(template).opening.isNotBlank())
        assertEquals(
            "Thank you. I recorded your proposed meeting details: 9am Tuesday. " +
                "The account owner will confirm availability before an invitation is sent.",
            template.acknowledgement("9am Tuesday"),
        )
    }

    @Test
    fun `a disabled automation is refused`() = runTest {
        val id = AutomationCatalog.ExplainDelay.id
        repository.setAutomationEnabled(id, false)

        assertNull(AgentCallPolicy.automationFor(id, repository.inboundConfig()))
    }

    @Test
    fun `an unknown automation id is refused`() = runTest {
        assertNull(AgentCallPolicy.automationFor("not_a_real_automation", repository.inboundConfig()))
    }

    @Test
    fun `an outbound template is not reachable from an inbound call`() = runTest {
        // Seeded from the inbound catalog only, so the outbound id is never enabled.
        assertNull(
            AgentCallPolicy.automationFor(
                AutomationCatalog.ReturnSamsungItem.id,
                repository.inboundConfig(),
            ),
        )
    }
}
