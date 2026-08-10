package dev.triplex.data.call

import dev.triplex.dialer.DialerContact
import dev.triplex.domain.call.AgentUtterance
import dev.triplex.domain.call.CallDirection
import dev.triplex.domain.call.CallIntent
import dev.triplex.domain.call.CallLifecycleEvent
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallStack
import dev.triplex.domain.call.CallerUtterance
import dev.triplex.domain.call.UserReply
import dev.triplex.telephony.sip.CallConversationTurn
import dev.triplex.telephony.sip.ConversationSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the whole two-stack merge on the JVM.
 *
 * Both sources are Android-free by construction and the repository takes its
 * scope as a parameter, so `runTest`'s scheduler is the only clock involved —
 * no Robolectric, no instrumentation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionRepositoryTest {

    private val telecomSource = TelecomCallSource()
    private val sipSource = SipCallSource()
    private val telecomController = FakeTelecomCallController()
    private val sipController = FakeSipCallController()
    private val contacts = MutableStateFlow<List<DialerContact>>(emptyList())
    private val identityResolver = FakeCallerIdentityResolver(contacts)

    init {
        telecomSource.registerController(telecomController)
        sipSource.registerController(sipController)
        // Deterministic, strictly increasing timeline timestamps.
        var tick = 0L
        sipSource.clock = { ++tick }
    }

    @Test
    fun `telecom takes the primary slot when both stacks are live`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        telecomSource.publish(ringingTelecomCall())
        sipSource.publishScreening(screeningState())
        advanceUntilIdle()

        assertEquals(2, repository.sessions.value.size)
        val primary = assertSession(repository.session.value)
        assertEquals(CallStack.TELECOM, primary.stack)
        assertEquals(CallStack.SIP, repository.sessions.value[1].stack)
    }

    @Test
    fun `the sip session is primary when no sim call exists`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        sipSource.publishScreening(screeningState())
        advanceUntilIdle()

        val primary = assertSession(repository.session.value)
        assertEquals(CallStack.SIP, primary.stack)
        assertEquals(CallPhase.SCREENING, primary.phase)
        assertEquals(CallDirection.INCOMING, primary.direction)
        assertTrue(primary.capabilities.agentHasMedia)
    }

    @Test
    fun `a contact name resolves the caller on both stacks`() = runTest(UnconfinedTestDispatcher()) {
        contacts.value = listOf(
            DialerContact(id = 7L, name = "Ada Lovelace", number = "+1 318-555-0100", starred = false)
        )
        val repository = createRepository(backgroundScope)
        telecomSource.publish(ringingTelecomCall())
        advanceUntilIdle()
        assertEquals("Ada Lovelace", assertSession(repository.session.value).party.displayName)

        telecomSource.clear()
        sipSource.publishScreening(screeningState())
        advanceUntilIdle()
        val sip = assertSession(repository.session.value)
        assertEquals("Ada Lovelace", sip.party.displayName)
        assertEquals(7L, sip.party.contactId)
    }

    @Test
    fun `intents reach the stack that owns the session`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        telecomSource.publish(activeTelecomCall())
        sipSource.publishScreening(screeningState())
        advanceUntilIdle()

        // No session id: the primary (Telecom) session takes the intent.
        repository.dispatch(CallIntent.ToggleMute)
        repository.dispatch(CallIntent.SendDtmf('7'))
        repository.dispatch(CallIntent.HangUp)
        // An explicit id addresses the background SIP session instead.
        repository.dispatch(CallIntent.HandOffToAgent("book-a-meeting"), sessionId = SIP_ID)
        repository.dispatch(CallIntent.Answer, sessionId = SIP_ID)

        assertEquals(listOf("toggleMute", "sendDtmf:7", "disconnect"), telecomController.calls)
        assertEquals(listOf("handOffToAgent:book-a-meeting", "answer"), sipController.calls)
    }

    @Test
    fun `intents without the matching capability are dropped`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        telecomSource.publish(ringingTelecomCall())
        advanceUntilIdle()

        // Ringing: no mute, no DTMF, no conference, and no text reply because
        // the platform did not offer one on this call.
        repository.dispatch(CallIntent.ToggleMute)
        repository.dispatch(CallIntent.SendDtmf('1'))
        repository.dispatch(CallIntent.MergeCalls)
        repository.dispatch(CallIntent.SwitchCall)
        repository.dispatch(CallIntent.DeclineWithMessage("Can't talk right now."))
        repository.dispatch(CallIntent.SelectAudioEndpoint("bluetooth"))
        // The agent never gets media on a SIM leg.
        repository.dispatch(CallIntent.SpeakReply("Who is this?"))
        assertEquals(emptyList<String>(), telecomController.calls)

        // What ringing does allow.
        repository.dispatch(CallIntent.Answer)
        repository.dispatch(CallIntent.Decline)
        assertEquals(listOf("answer", "decline"), telecomController.calls)
    }

    @Test
    fun `a decided screening session stops accepting decisions`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        sipSource.publishScreening(screeningState(decision = "book-a-meeting"))
        advanceUntilIdle()

        assertTrue(!assertSession(repository.session.value).capabilities.canAnswer)
        repository.dispatch(CallIntent.Answer)
        repository.dispatch(CallIntent.Decline)
        assertEquals(emptyList<String>(), sipController.calls)
        // Hanging up is always available.
        repository.dispatch(CallIntent.HangUp)
        assertEquals(listOf("hangUp"), sipController.calls)
    }

    @Test
    fun `a partial caller turn is revised in place`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        sipSource.publishScreening(screeningState())
        sipSource.publishConversation(listOf(turn(ConversationSpeaker.CALLER, "hi this")))
        advanceUntilIdle()

        val partial = assertSession(repository.session.value).timeline
        assertEquals(1, partial.size)
        assertEquals(CallerUtterance("hi this", 1L, final = false), partial[0])

        sipSource.publishConversation(listOf(turn(ConversationSpeaker.CALLER, "hi this is Ada")))
        advanceUntilIdle()
        val revised = assertSession(repository.session.value).timeline
        assertEquals(1, revised.size)
        // Same slot, same timestamp: revised, not appended.
        assertEquals(CallerUtterance("hi this is Ada", 1L, final = false), revised[0])

        sipSource.publishConversation(
            listOf(
                turn(ConversationSpeaker.CALLER, "hi this is Ada"),
                turn(ConversationSpeaker.TRIPLEX, "One moment"),
            )
        )
        advanceUntilIdle()
        val closed = assertSession(repository.session.value).timeline
        assertEquals(2, closed.size)
        assertEquals(CallerUtterance("hi this is Ada", 1L, final = true), closed[0])
        assertEquals(AgentUtterance("One moment", 2L, complete = false), closed[1])
    }

    @Test
    fun `a spoken reply is attributed to the user, not the agent`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        sipSource.publishScreening(screeningState())
        sipSource.publishConversation(listOf(turn(ConversationSpeaker.CALLER, "Is Ada there?")))
        advanceUntilIdle()

        repository.dispatch(CallIntent.SpeakReply("Tell them I will call back"))
        assertEquals(listOf("speakReply:Tell them I will call back"), sipController.calls)

        // The controller echoes what it spoke back as a TRIPLEX turn.
        sipSource.publishConversation(
            listOf(
                turn(ConversationSpeaker.CALLER, "Is Ada there?"),
                turn(ConversationSpeaker.TRIPLEX, "Tell them I will call back"),
                turn(ConversationSpeaker.TRIPLEX, "She is not available"),
            )
        )
        advanceUntilIdle()

        val timeline = assertSession(repository.session.value).timeline
        assertEquals(3, timeline.size)
        assertEquals(UserReply("Tell them I will call back", 2L), timeline[1])
        assertEquals(AgentUtterance("She is not available", 3L, complete = false), timeline[2])
    }

    @Test
    fun `a call that rings out and disappears is reported as missed`() = runTest(UnconfinedTestDispatcher()) {
        val repository = createRepository(backgroundScope)
        val events = mutableListOf<CallLifecycleEvent>()
        backgroundScope.launch { repository.events.collect(events::add) }
        advanceUntilIdle()

        val ringing = ringingTelecomCall()
        telecomSource.publish(ringing)
        advanceUntilIdle()
        assertEquals(1, events.size)
        assertTrue(events[0] is CallLifecycleEvent.Started)

        telecomSource.reportMissed(ringing)
        telecomSource.clear()
        advanceUntilIdle()

        assertNull(repository.session.value)
        val ended = events.last()
        assertTrue(ended is CallLifecycleEvent.Ended && ended.missed)
        assertEquals(1, identityResolver.refreshCount)
    }

    private fun createRepository(scope: CoroutineScope) = CallSessionRepositoryImpl(
        telecomSource = telecomSource,
        sipSource = sipSource,
        identityResolver = identityResolver,
        phoneNumberFormatter = { it },
        scope = scope,
    )

    private fun assertSession(session: CallSession?): CallSession {
        assertNotNull("expected a primary session", session)
        return session!!
    }

    private fun ringingTelecomCall() = TelecomCallSnapshot(
        id = TELECOM_ID,
        number = "+13185550100",
        callerDisplayName = "",
        isIncoming = true,
        phase = CallPhase.RINGING,
        statusLabel = "Ringing",
    )

    private fun activeTelecomCall() = TelecomCallSnapshot(
        id = TELECOM_ID,
        number = "+13185550100",
        callerDisplayName = "Triplex test caller",
        isIncoming = true,
        phase = CallPhase.ACTIVE,
        statusLabel = "Active",
        connectedAtMs = 1_700_000_000_000L,
    )

    private fun screeningState(decision: String? = null) = SipScreeningState(
        id = SIP_ID,
        callerNumber = "+13185550100",
        status = "asking",
        transcript = "",
        decision = decision,
        message = "",
    )

    private fun turn(speaker: ConversationSpeaker, text: String) =
        CallConversationTurn(speaker = speaker, text = text)

    private companion object {
        const val TELECOM_ID = "telecom:+13185550100:0"
        const val SIP_ID = "sip-+13185550100-1"
    }
}

private class FakeTelecomCallController : TelecomCallController {
    val calls = mutableListOf<String>()

    override fun answer() { calls += "answer" }

    override fun decline() { calls += "decline" }

    override fun declineWithMessage(message: String) { calls += "declineWithMessage:$message" }

    override fun disconnect() { calls += "disconnect" }

    override fun deflectToAgent() { calls += "deflectToAgent" }

    override fun conferenceInAgent() { calls += "conferenceInAgent" }

    override fun toggleMute() { calls += "toggleMute" }

    override fun toggleSpeaker() { calls += "toggleSpeaker" }

    override fun selectAudioEndpoint(endpointId: String) { calls += "selectAudioEndpoint:$endpointId" }

    override fun toggleHold() { calls += "toggleHold" }

    override fun holdForAddCall() { calls += "holdForAddCall" }

    override fun switchCall() { calls += "switchCall" }

    override fun mergeCalls() { calls += "mergeCalls" }

    override fun swapConference() { calls += "swapConference" }

    override fun sendDtmf(digit: Char) { calls += "sendDtmf:$digit" }
}

private class FakeSipCallController : SipCallController {
    val calls = mutableListOf<String>()

    override fun answer() { calls += "answer" }

    override fun decline() { calls += "decline" }

    override fun hangUp() { calls += "hangUp" }

    override fun handOffToAgent(automationId: String?) { calls += "handOffToAgent:$automationId" }

    override fun takeBack() { calls += "takeBack" }

    override fun speakReply(text: String) { calls += "speakReply:$text" }
}

private class FakeCallerIdentityResolver(
    override val contacts: StateFlow<List<DialerContact>>,
) : CallerIdentityResolver {
    var refreshCount = 0
        private set

    override fun refresh() { refreshCount++ }
}
