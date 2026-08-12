package dev.triplex.ui.call.incoming

import dev.triplex.data.local.AgentConfigDefaults
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.data.local.FakeVoiceProfileReadiness
import dev.triplex.data.local.InMemoryAgentConfigStore
import dev.triplex.data.repository.FakeCallSessionRepository
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallCapabilities
import dev.triplex.domain.call.CallDirection
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallParty
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallStack
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.TimelineEntry
import dev.triplex.domain.model.AutomationCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The incoming sheet without Compose.
 *
 * [incomingCallUiState] is a pure function on purpose, so what the sheet draws —
 * including which controls are dead and the sentence that explains each one — is
 * asserted here rather than in an instrumented test. The view model is exercised
 * separately for the only thing it adds: turning a control into a [CallIntent].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val calls = FakeCallSessionRepository()
    private val agentConfig = AgentConfigRepository(
        InMemoryAgentConfigStore(),
        FakeVoiceProfileReadiness(ready = false),
    )

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- projection -------------------------------------------------------

    @Test
    fun `no call means no sheet`() {
        assertFalse(incomingCallUiState(null, AgentInboundConfig()).visible)
    }

    @Test
    fun `a connected call belongs to the in-call surface, not the sheet`() {
        val state = incomingCallUiState(
            sipScreeningCall().copy(phase = CallPhase.ACTIVE),
            AgentInboundConfig(),
        )

        assertFalse(state.visible)
    }

    @Test
    fun `a screened SIP call shows the transcript it was given`() {
        val timeline = listOf<TimelineEntry>(
            AgentUtterance("Who is calling?", atMs = 0L, playbackDurationMs = 1_200L),
            CallerUtterance("Dana from the clinic", atMs = 2_000L, final = false),
        )

        val state = incomingCallUiState(
            sipScreeningCall(timeline = timeline),
            AgentInboundConfig(),
        )

        assertTrue(state.visible)
        assertTrue(state.showTranscript)
        // Passed through, not re-derived: the sheet renders the same entries the
        // agent panel does, through the same TranscriptTimeline.
        assertEquals(timeline, state.timeline)
        assertEquals(AgentConfigDefaults.QUICK_REPLIES, state.quickReplies)
        assertNull(state.takeoverNotice)
    }

    @Test
    fun `a ringing SIM call trades the transcript for gated actions`() {
        val state = incomingCallUiState(telecomRingingCall(), AgentInboundConfig())

        assertTrue(state.visible)
        assertFalse(state.agentHasMedia)
        assertFalse(state.showTranscript)
        // No agent voice on this leg, so there is nothing for a reply chip to say.
        assertEquals(emptyList<String>(), state.quickReplies)
        assertEquals(
            "Triplex is not on this call — your carrier owns its audio",
            state.agentStatus,
        )

        val textReply = state.action(IncomingCallActionId.TEXT_REPLY)!!
        assertFalse(textReply.enabled)
        assertEquals(
            "This call does not offer a text reply on your carrier or SIM.",
            textReply.reason,
        )

        // Nothing on this call can turn the hand-off on — it needs carrier
        // deflection and an agent number, neither settable while it rings — so
        // the control is not drawn at all rather than drawn dead.
        assertNull(state.action(IncomingCallActionId.SEND_TO_AGENT))

        // Answering a SIM call is the one thing the platform always allows.
        assertTrue(state.action(IncomingCallActionId.ANSWER)!!.enabled)
        assertTrue(state.action(IncomingCallActionId.HANG_UP)!!.enabled)
    }

    @Test
    fun `a carrier that supports deflection is the only one offered the hand-off`() {
        val session = telecomRingingCall(
            capabilities = telecomCapabilities().copy(
                canTextReply = true,
                canDeflectToAgent = true,
            ),
        )

        val state = incomingCallUiState(session, AgentInboundConfig())

        assertTrue(state.action(IncomingCallActionId.TEXT_REPLY)!!.enabled)
        assertNull(state.action(IncomingCallActionId.TEXT_REPLY)!!.reason)

        val toAgent = state.action(IncomingCallActionId.SEND_TO_AGENT)!!
        assertTrue(toAgent.enabled)
        assertNull(toAgent.reason)
    }

    @Test
    fun `a SIM call is offered no automations because none could speak on it`() {
        val state = incomingCallUiState(
            telecomRingingCall(),
            AgentInboundConfig(
                enabledAutomationIds = setOf(AutomationCatalog.BookZoomMeeting.id),
            ),
        )

        // The automation would have to talk on a leg Triplex holds no media for.
        assertEquals(emptyList<IncomingAutomationOption>(), state.automations)
    }

    @Test
    fun `answering a SIP leg says out loud that the phone is not on the call`() {
        val session = sipScreeningCall().copy(
            agentMode = AgentMode.HANDOFF,
            capabilities = sipCapabilities().copy(canAnswer = false),
        )

        val state = incomingCallUiState(session, AgentInboundConfig())

        val notice = state.takeoverNotice
        assertNotNull(notice)
        assertTrue(notice!!.contains("microphone"))
        assertEquals("You answered — Triplex still holds the line", state.agentStatus)

        val answer = state.action(IncomingCallActionId.ANSWER)!!
        assertFalse(answer.enabled)
        assertEquals("You already took this call.", answer.reason)
        // Hanging up is never gated: it is the honest way out of this state.
        assertTrue(state.action(IncomingCallActionId.HANG_UP)!!.enabled)
    }

    @Test
    fun `only the automations the user enabled are offered`() {
        val state = incomingCallUiState(
            sipScreeningCall(),
            AgentInboundConfig(
                enabledAutomationIds = setOf(AutomationCatalog.BookZoomMeeting.id),
            ),
        )

        assertEquals(
            listOf(AutomationCatalog.BookZoomMeeting.id),
            state.automations.map { it.id },
        )
        assertEquals(AutomationCatalog.BookZoomMeeting.title, state.automations.single().title)
    }

    // ---- intents ----------------------------------------------------------

    @Test
    fun `a quick reply is spoken through the agent on this session`() = runTest {
        calls.emitSessions(sipScreeningCall())
        val viewModel = collectedViewModel()

        viewModel.onQuickReply(AgentConfigDefaults.QUICK_REPLIES.first())

        assertEquals(
            listOf<Pair<CallIntent, String?>>(
                CallIntent.SpeakReply(AgentConfigDefaults.QUICK_REPLIES.first()) to SIP_ID,
            ),
            calls.dispatched,
        )
    }

    @Test
    fun `a reply the user never configured is not spoken`() = runTest {
        calls.emitSessions(sipScreeningCall())
        val viewModel = collectedViewModel()

        viewModel.onQuickReply("Tell them I said something else")

        assertEquals(emptyList<Pair<CallIntent, String?>>(), calls.dispatched)
    }

    @Test
    fun `an automation hand-off carries the automation id`() = runTest {
        calls.emitSessions(sipScreeningCall())
        val viewModel = collectedViewModel()

        viewModel.onAutomation(AutomationCatalog.ExplainDelay.id)
        viewModel.onAutomation("not_in_the_catalog")

        assertEquals(
            listOf<Pair<CallIntent, String?>>(
                CallIntent.HandOffToAgent(AutomationCatalog.ExplainDelay.id) to SIP_ID,
            ),
            calls.dispatched,
        )
    }

    @Test
    fun `every control routes one intent`() = runTest {
        calls.emitSessions(telecomRingingCall(capabilities = telecomCapabilities()))
        val viewModel = collectedViewModel()

        viewModel.onAction(IncomingCallActionId.ANSWER)
        viewModel.onAction(IncomingCallActionId.DECLINE)
        viewModel.onAction(IncomingCallActionId.HANG_UP)

        assertEquals(
            listOf<Pair<CallIntent, String?>>(
                CallIntent.Answer to TELECOM_ID,
                CallIntent.Decline to TELECOM_ID,
                CallIntent.HangUp to TELECOM_ID,
            ),
            calls.dispatched,
        )
    }

    @Test
    fun `a control that is disabled or absent dispatches nothing`() = runTest {
        calls.emitSessions(telecomRingingCall())
        val viewModel = collectedViewModel()

        // Disabled and explained.
        viewModel.onAction(IncomingCallActionId.TEXT_REPLY)
        // Removed from the sheet entirely; asking for it by id is still refused.
        viewModel.onAction(IncomingCallActionId.SEND_TO_AGENT)

        assertEquals(emptyList<Pair<CallIntent, String?>>(), calls.dispatched)
    }

    /**
     * The state is a `WhileSubscribed` flow, so it stays on its initial value
     * until something collects it — exactly as in the app, where the sheet is
     * the collector.
     */
    private fun TestScope.collectedViewModel(): IncomingCallViewModel {
        val viewModel = IncomingCallViewModel(calls, agentConfig)
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private fun sipCapabilities() = CallCapabilities(
        canAnswer = true,
        canDecline = true,
        canMute = true,
        agentHasMedia = true,
    )

    private fun telecomCapabilities() = CallCapabilities(
        canAnswer = true,
        canDecline = true,
        canMute = true,
        agentHasMedia = false,
    )

    private fun sipScreeningCall(
        timeline: List<TimelineEntry> = emptyList(),
    ) = CallSession(
        id = SIP_ID,
        stack = CallStack.SIP,
        direction = CallDirection.INCOMING,
        party = CallParty(number = "+13185550142", displayName = "+13185550142"),
        phase = CallPhase.SCREENING,
        agentMode = AgentMode.SCREENING,
        connectedAtMs = null,
        capabilities = sipCapabilities(),
        timeline = timeline,
        otherCallCount = 0,
        statusLabel = "Screening",
        statusMessage = "",
    )

    private fun telecomRingingCall(
        capabilities: CallCapabilities = telecomCapabilities().copy(canDeflectToAgent = false),
    ) = CallSession(
        id = TELECOM_ID,
        stack = CallStack.TELECOM,
        direction = CallDirection.INCOMING,
        party = CallParty(number = "+13185550100", displayName = "Triplex test caller"),
        phase = CallPhase.RINGING,
        agentMode = AgentMode.NONE,
        connectedAtMs = null,
        capabilities = capabilities,
        timeline = emptyList(),
        otherCallCount = 0,
        statusLabel = "Ringing",
        statusMessage = "",
    )

    private companion object {
        const val SIP_ID = "sip-1"
        const val TELECOM_ID = "telecom:+13185550100:0"
    }
}
