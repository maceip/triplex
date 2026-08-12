package dev.triplex.ui.call.incall

import dev.triplex.data.local.AgentConfigDefaults
import dev.triplex.data.local.AgentConfigRepository
import dev.triplex.data.local.AgentInboundConfig
import dev.triplex.data.local.FakeVoiceProfileReadiness
import dev.triplex.data.local.InMemoryAgentConfigStore
import dev.triplex.data.repository.FakeCallSessionRepository
import dev.triplex.domain.call.AgentMode
import dev.triplex.domain.call.AudioEndpointUi
import dev.triplex.domain.call.CallCapabilities
import dev.triplex.domain.call.CallDirection
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallParty
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallStack
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.ui.call.shared.CallActionId
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
 * The in-call surface without Compose.
 *
 * [inCallUiState] is pure, so the whole surface — which tiles exist, which agent
 * controls are dead and the sentence that explains each one — is asserted here.
 * The view model is exercised for the one thing it adds: turning a control into
 * a [CallIntent] on the right session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InCallViewModelTest {

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
    fun `no call means no in-call surface`() {
        assertFalse(inCallUiState(null, AgentInboundConfig()).visible)
    }

    @Test
    fun `a ringing call belongs to the incoming sheet, not this surface`() {
        val state = inCallUiState(
            telecomActiveCall().copy(phase = CallPhase.RINGING),
            AgentInboundConfig(),
        )

        assertFalse(state.visible)
    }

    @Test
    fun `a screening call belongs to the incoming sheet, not this surface`() {
        val state = inCallUiState(
            sipActiveCall().copy(phase = CallPhase.SCREENING),
            AgentInboundConfig(),
        )

        assertFalse(state.visible)
    }

    @Test
    fun `only the tiles the platform actually offers are drawn`() {
        val state = inCallUiState(telecomActiveCall(), AgentInboundConfig())

        assertTrue(state.visible)
        assertEquals(
            listOf(
                CallActionId.MUTE,
                CallActionId.SPEAKER,
                CallActionId.HOLD,
                CallActionId.ADD_CALL,
                CallActionId.KEYPAD,
            ),
            state.actions.map { it.id },
        )
        // Absent, not disabled: there is no second call to merge into or swap with.
        assertNull(state.tile(CallActionId.MERGE))
        assertNull(state.tile(CallActionId.SWAP))
        assertNull(state.tile(CallActionId.SWITCH))
        assertEquals(CONNECTED_AT, state.connectedAtMs)
    }

    @Test
    fun `a latched control says so in its label and its state`() {
        val state = inCallUiState(
            telecomActiveCall().copy(phase = CallPhase.HOLDING, isMuted = true),
            AgentInboundConfig(),
        )

        val mute = state.tile(CallActionId.MUTE)!!
        assertEquals("Unmute", mute.label)
        assertTrue(mute.active)

        val hold = state.tile(CallActionId.HOLD)!!
        assertEquals("Resume", hold.label)
        assertTrue(hold.active)

        assertFalse(state.tile(CallActionId.SPEAKER)!!.active)
    }

    @Test
    fun `a SIM call says out loud that Triplex is not on it`() {
        val state = inCallUiState(telecomActiveCall(), AgentInboundConfig())
        val agent = state.agent

        assertFalse(agent.agentHasMedia)
        assertFalse(agent.showTranscript)
        // No agent voice on this leg, so there is nothing for a reply chip to say.
        assertEquals(emptyList<String>(), agent.quickReplies)
        assertEquals(
            "Triplex is not on this call — your carrier owns its audio",
            agent.status,
        )
        assertNull(agent.takeoverNotice)

        // Deflection is ringing-only, so a connected SIM call is handed over by
        // dialling Triplex as a second leg — which needs canAddCall, not canMerge.
        val handOff = agent.action(AgentActionId.HAND_OFF)!!
        assertEquals("Add Triplex to this call", handOff.label)
        assertTrue(handOff.enabled)
        assertNull(handOff.reason)

        // The carrier owns this leg once it has been handed over, so there is no
        // recall to offer and no control pretending otherwise.
        assertNull(agent.action(AgentActionId.TAKE_BACK))
    }

    @Test
    fun `a SIM call that cannot take a second line explains the dead hand-off`() {
        val session = telecomActiveCall(
            capabilities = telecomCapabilities().copy(canAddCall = false),
        )

        val handOff = inCallUiState(session, AgentInboundConfig())
            .agent
            .action(AgentActionId.HAND_OFF)!!

        // A passing condition, not a missing feature: the agent number is there,
        // this call just cannot hold a second line at the moment.
        assertFalse(handOff.enabled)
        assertEquals("This call cannot take a second line right now.", handOff.reason)
    }

    @Test
    fun `a build with no agent number does not offer to add Triplex to a call`() {
        val session = telecomActiveCall(
            capabilities = telecomCapabilities().copy(canConferenceAgent = false),
        )

        val agent = inCallUiState(session, AgentInboundConfig()).agent

        // There is no second leg to place, so there is nothing to draw. The
        // panel still says Triplex is not on this call.
        assertNull(agent.action(AgentActionId.HAND_OFF))
        assertEquals(
            "Triplex is not on this call — your carrier owns its audio",
            agent.status,
        )
    }

    @Test
    fun `a SIP call Triplex answered says the phone is not on the audio`() {
        val state = inCallUiState(sipActiveCall(), AgentInboundConfig())
        val agent = state.agent

        assertTrue(state.visible)
        assertTrue(agent.agentHasMedia)
        assertTrue(agent.showTranscript)
        assertEquals(AgentConfigDefaults.QUICK_REPLIES, agent.quickReplies)
        assertNotNull(agent.takeoverNotice)
        assertTrue(agent.takeoverNotice!!.contains("microphone"))
        assertEquals(
            "You took this call — Triplex is still the only voice on it",
            agent.status,
        )
        // A SIP leg never reports a connect time, and inventing one would put a
        // duration on screen the call never had.
        assertNull(state.connectedAtMs)

        val takeBack = agent.action(AgentActionId.TAKE_BACK)!!
        assertTrue(takeBack.enabled)
        assertNull(takeBack.reason)
    }

    @Test
    fun `handing a live SIP call over needs an automation switched on first`() {
        // The catalog ships enabled, so switching everything off is the only way
        // into this state — and with nothing to hand the call to, there is no
        // control. "Hand to an automation" over an empty picker is a promise the
        // call cannot keep.
        val without = inCallUiState(
            sipActiveCall(),
            AgentInboundConfig(enabledAutomationIds = emptySet()),
        ).agent

        assertNull(without.action(AgentActionId.HAND_OFF))
        assertEquals(emptyList<AgentAutomationOption>(), without.automations)

        val state = inCallUiState(
            sipActiveCall(),
            AgentInboundConfig(enabledAutomationIds = setOf(AutomationCatalog.BookZoomMeeting.id)),
        )

        val handOff = state.agent.action(AgentActionId.HAND_OFF)!!
        assertEquals("Hand to an automation", handOff.label)
        assertTrue(handOff.enabled)
        assertEquals(
            listOf(AutomationCatalog.BookZoomMeeting.id),
            state.agent.automations.map { it.id },
        )
    }

    // ---- intents ----------------------------------------------------------

    @Test
    fun `every tile routes one intent on this session`() = runTest {
        calls.emitSessions(telecomActiveCall())
        val viewModel = collectedViewModel()

        viewModel.onAction(CallActionId.MUTE)
        viewModel.onAction(CallActionId.SPEAKER)
        viewModel.onAction(CallActionId.HOLD)
        viewModel.onAction(CallActionId.ADD_CALL)
        viewModel.onDtmf('5')
        viewModel.onHangUp()

        assertEquals(
            listOf<Pair<CallIntent, String?>>(
                CallIntent.ToggleMute to TELECOM_ID,
                CallIntent.ToggleSpeaker to TELECOM_ID,
                CallIntent.ToggleHold to TELECOM_ID,
                CallIntent.AddCall to TELECOM_ID,
                CallIntent.SendDtmf('5') to TELECOM_ID,
                CallIntent.HangUp to TELECOM_ID,
            ),
            calls.dispatched,
        )
    }

    @Test
    fun `a tile the call does not offer dispatches nothing`() = runTest {
        calls.emitSessions(telecomActiveCall())
        val viewModel = collectedViewModel()

        viewModel.onAction(CallActionId.MERGE)
        viewModel.onAction(CallActionId.SWAP)
        viewModel.onAction(CallActionId.SWITCH)
        // The keypad is a disclosure, not a call operation.
        viewModel.onAction(CallActionId.KEYPAD)

        assertEquals(emptyList<Pair<CallIntent, String?>>(), calls.dispatched)
    }

    @Test
    fun `an endpoint the platform never offered is not selected`() = runTest {
        calls.emitSessions(telecomActiveCall())
        val viewModel = collectedViewModel()

        viewModel.onSelectAudioEndpoint("bluetooth")
        viewModel.onSelectAudioEndpoint("not_an_endpoint")

        assertEquals(
            listOf<Pair<CallIntent, String?>>(
                CallIntent.SelectAudioEndpoint("bluetooth") to TELECOM_ID,
            ),
            calls.dispatched,
        )
    }

    @Test
    fun `handing a SIM call over carries no automation because none can ride along`() = runTest {
        calls.emitSessions(telecomActiveCall())
        val viewModel = collectedViewModel()

        viewModel.onAgentAction(AgentActionId.HAND_OFF)
        // The carrier owns this leg's audio, so there is nothing to pull back.
        viewModel.onAgentAction(AgentActionId.TAKE_BACK)

        assertEquals(
            listOf<Pair<CallIntent, String?>>(CallIntent.HandOffToAgent(null) to TELECOM_ID),
            calls.dispatched,
        )
    }

    @Test
    fun `taking a SIP call back from the agent is dispatched`() = runTest {
        calls.emitSessions(sipActiveCall())
        val viewModel = collectedViewModel()

        viewModel.onAgentAction(AgentActionId.TAKE_BACK)

        assertEquals(
            listOf<Pair<CallIntent, String?>>(CallIntent.TakeBackFromAgent to SIP_ID),
            calls.dispatched,
        )
    }

    @Test
    fun `a live SIP hand-off carries the automation the user picked`() = runTest {
        calls.emitSessions(sipActiveCall())
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
    fun `a quick reply the user never configured is not spoken`() = runTest {
        calls.emitSessions(sipActiveCall())
        val viewModel = collectedViewModel()

        viewModel.onQuickReply(AgentConfigDefaults.QUICK_REPLIES.first())
        viewModel.onQuickReply("Tell them I said something else")

        assertEquals(
            listOf<Pair<CallIntent, String?>>(
                CallIntent.SpeakReply(AgentConfigDefaults.QUICK_REPLIES.first()) to SIP_ID,
            ),
            calls.dispatched,
        )
    }

    // ---- the call clock ---------------------------------------------------

    @Test
    fun `the call clock grows an hours field only once there is one`() {
        assertEquals("00:00", formatElapsed(0L))
        assertEquals("01:05", formatElapsed(65L))
        assertEquals("59:59", formatElapsed(3_599L))
        assertEquals("1:01:01", formatElapsed(3_661L))
        // A clock skew backwards reads zero rather than a negative duration.
        assertEquals("00:00", formatElapsed(-5L))
    }

    /**
     * The state is a `WhileSubscribed` flow, so it stays on its initial value
     * until something collects it — exactly as in the app, where the screen is
     * the collector.
     */
    private fun TestScope.collectedViewModel(): InCallViewModel {
        val viewModel = InCallViewModel(calls, agentConfig)
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private fun telecomCapabilities() = CallCapabilities(
        canHold = true,
        canMute = true,
        canSendDtmf = true,
        canAddCall = true,
        // This build has an agent number, so the conference hand-off exists.
        canConferenceAgent = true,
        agentHasMedia = false,
        audioEndpoints = listOf(
            AudioEndpointUi(id = "earpiece", name = "Phone", selected = true),
            AudioEndpointUi(id = "bluetooth", name = "Headset", selected = false),
        ),
    )

    private fun sipCapabilities() = CallCapabilities(
        canMute = true,
        agentHasMedia = true,
    )

    private fun telecomActiveCall(
        capabilities: CallCapabilities = telecomCapabilities(),
    ) = CallSession(
        id = TELECOM_ID,
        stack = CallStack.TELECOM,
        direction = CallDirection.INCOMING,
        party = CallParty(number = "+13185550100", displayName = "Triplex test caller"),
        phase = CallPhase.ACTIVE,
        agentMode = AgentMode.NONE,
        connectedAtMs = CONNECTED_AT,
        capabilities = capabilities,
        timeline = emptyList(),
        otherCallCount = 0,
        statusLabel = "Connected",
        statusMessage = "",
    )

    private fun sipActiveCall() = CallSession(
        id = SIP_ID,
        stack = CallStack.SIP,
        direction = CallDirection.INCOMING,
        party = CallParty(number = "+13185550142", displayName = "+13185550142"),
        phase = CallPhase.ACTIVE,
        agentMode = AgentMode.HANDOFF,
        connectedAtMs = null,
        capabilities = sipCapabilities(),
        timeline = emptyList(),
        otherCallCount = 0,
        statusLabel = "Connected",
        statusMessage = "",
    )

    private companion object {
        const val SIP_ID = "sip-1"
        const val TELECOM_ID = "telecom:+13185550100:0"
        const val CONNECTED_AT = 1_700_000_000_000L
    }
}
