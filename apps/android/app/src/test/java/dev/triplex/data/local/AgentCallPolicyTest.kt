package dev.triplex.data.local

import dev.triplex.dialogue.CallDirection
import dev.triplex.dialogue.FallbackPolicy
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.TaskDefinition
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
        assertTrue(template.goal.isNotBlank())
        assertTrue(template.collect.isNotEmpty())
    }

    @Test
    fun `a screening plan carries the user's greeting and screening limits`() = runTest {
        repository.setGreetingText("Hi, this is Ava's assistant.")

        val plan = requireNotNull(AgentCallPolicy.screeningPlan(repository.inboundConfig()))

        assertEquals(CallDirection.INBOUND, plan.direction)
        assertEquals("Hi, this is Ava's assistant.", plan.opening)
        assertEquals(6, plan.budget.maxTurns)
        // Screening buys a turn on a model hiccup rather than hanging up on
        // a live caller mid-sentence.
        assertTrue(plan.fallback is FallbackPolicy.FailSoft)
    }

    @Test
    fun `an automation plan opens with the automation and briefs its goal`() = runTest {
        val plan = requireNotNull(
            AgentCallPolicy.screeningPlan(
                repository.inboundConfig(),
                AutomationCatalog.BookZoomMeeting.id,
            )
        )

        assertEquals(AutomationCatalog.BookZoomMeeting.opening, plan.opening)
        assertTrue(plan.systemInstructions.contains(AutomationCatalog.BookZoomMeeting.goal))
        assertTrue(plan.systemInstructions.contains("email address"))
    }

    @Test
    fun `a disabled automation yields no plan rather than the generic agent`() = runTest {
        val id = AutomationCatalog.BookZoomMeeting.id
        repository.setAutomationEnabled(id, false)

        // Silently falling back to generic screening would give the caller a
        // different conversation than the user asked for.
        assertNull(AgentCallPolicy.screeningPlan(repository.inboundConfig(), id))
    }

    @Test
    fun `an outbound plan states only the task parameters that have values`() {
        val plan = AgentCallPolicy.outboundPlan(
            TaskDefinition(
                id = "task-1",
                user_id = "user-1",
                task_type = "item_return",
                destination_number = "+14155550188",
                task_params = mapOf(
                    "product" to "a 32-inch monitor",
                    "desired_outcome" to "a refund",
                    "order_number" to "SG-4471-2290",
                    "return_reason" to "",
                ),
                status = "active",
                created_at = "2026-08-10T00:00:00Z",
            )
        )

        assertEquals(CallDirection.OUTBOUND, plan.direction)
        assertTrue(plan.opening.contains("a 32-inch monitor"))
        assertTrue(plan.opening.contains("The order number is SG-4471-2290."))
        assertFalse(plan.opening.contains("The reason is"))
        assertTrue(plan.systemInstructions.contains("order number: SG-4471-2290"))
        // Outbound the agent is transacting for the user, so a reasoner failure
        // ends the call rather than stalling for another turn.
        assertEquals(FallbackPolicy.FailClosed, plan.fallback)
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
