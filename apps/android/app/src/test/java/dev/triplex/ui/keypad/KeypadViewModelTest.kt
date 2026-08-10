package dev.triplex.ui.keypad

import dev.triplex.data.telecom.DialerPhoneAccount
import dev.triplex.data.telecom.DialerSetupRepository
import dev.triplex.data.telecom.DialerSetupStatus
import dev.triplex.data.telecom.PlaceCallOutcome
import dev.triplex.data.telecom.SetupRequest
import dev.triplex.data.telecom.TelecomAccountsRepository
import dev.triplex.dialer.DialerDirectoryRepository
import dev.triplex.dialer.FakeDirectoryDataSource
import dev.triplex.dialer.RecentCallType
import dev.triplex.dialer.testContact
import dev.triplex.dialer.testRecent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The keypad's brain, without Compose and without a device.
 *
 * Every dependency is an interface over a platform API, so the view model is
 * constructed directly here — the same reason `CallSessionRepository` is
 * testable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeypadViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeDirectoryDataSource()
    private val telecom = FakeTelecomAccountsRepository()
    private val setup = FakeDialerSetupRepository()

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typing digits offers T9 matches above the keys`() = runTest(dispatcher) {
        source.contacts = listOf(
            testContact(1, "Jane Kowalski", "+13185550100"),
            testContact(2, "Bruno Vega", "+15045557788"),
        )
        source.notifyContacts()
        val viewModel = createViewModel(backgroundScope)
        advanceUntilIdle()

        viewModel.dispatch(KeypadIntent.SetDialString("526"))
        advanceUntilIdle()

        assertEquals(listOf("Jane Kowalski"), viewModel.state.value.dialMatches.map { it.title })
        // The pad drives smart dial, not the search field.
        assertTrue(viewModel.state.value.searchResults.isEmpty())
    }

    @Test
    fun `the search field searches contacts and history at once`() = runTest(dispatcher) {
        source.contacts = listOf(testContact(1, "Jane Kowalski", "+13185550100"))
        source.recents = listOf(testRecent(9, number = "+15045557788", name = "Bruno Vega"))
        source.notifyContacts()
        source.notifyCallLog()
        val viewModel = createViewModel(backgroundScope)
        advanceUntilIdle()

        viewModel.dispatch(KeypadIntent.SetQuery("555"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.searching)
        assertEquals(
            listOf("Jane Kowalski", "Bruno Vega"),
            state.searchResults.map { it.title },
        )
    }

    @Test
    fun `a dual SIM device with no default asks before dialling`() = runTest(dispatcher) {
        telecom.accountsFlow.value = listOf(
            DialerPhoneAccount("sim1", "SIM 1", isDefault = false),
            DialerPhoneAccount("sim2", "SIM 2", isDefault = false),
        )
        telecom.requiresChoiceFlow.value = true
        val viewModel = createViewModel(backgroundScope)
        advanceUntilIdle()

        viewModel.dispatch(KeypadIntent.PlaceCall("3185550100"))
        advanceUntilIdle()

        // The old code dialled here and silently dropped the account handle.
        assertEquals("3185550100", viewModel.state.value.accountChoiceRequiredFor)
        assertTrue(telecom.placed.isEmpty())

        viewModel.dispatch(KeypadIntent.SelectAccountAndCall("sim2"))
        advanceUntilIdle()

        assertEquals(listOf("3185550100" to "sim2"), telecom.placed)
        assertNull(viewModel.state.value.accountChoiceRequiredFor)
    }

    @Test
    fun `a single account dials straight through`() = runTest(dispatcher) {
        telecom.accountsFlow.value = listOf(DialerPhoneAccount("sim1", "SIM 1", isDefault = true))
        telecom.selectedFlow.value = "sim1"
        val viewModel = createViewModel(backgroundScope)
        advanceUntilIdle()

        viewModel.dispatch(KeypadIntent.PlaceCall("3185550100"))
        advanceUntilIdle()

        assertEquals(listOf("3185550100" to "sim1"), telecom.placed)
        assertNull(viewModel.state.value.accountChoiceRequiredFor)
    }

    @Test
    fun `a missing dialer role becomes a setup request carrying the number`() =
        runTest(dispatcher) {
            telecom.outcome = PlaceCallOutcome.NeedsDefaultDialerRole
            val viewModel = createViewModel(backgroundScope)
            val effects = collectEffects(viewModel, backgroundScope)
            advanceUntilIdle()

            viewModel.dispatch(KeypadIntent.PlaceCall("3185550100"))
            advanceUntilIdle()

            assertEquals(
                listOf(KeypadEffect.RequestSetup(SetupRequest.DEFAULT_DIALER, "3185550100")),
                effects,
            )
        }

    @Test
    fun `scrolling to the end asks the repository for another page`() = runTest(dispatcher) {
        val page = DialerDirectoryRepository.RECENTS_PAGE
        source.recents = (1L..(page + 5L)).map { testRecent(it) }
        val viewModel = createViewModel(backgroundScope)
        advanceUntilIdle()
        assertEquals(page, source.lastRecentsLimit)

        viewModel.dispatch(KeypadIntent.LoadMoreRecents)
        advanceUntilIdle()

        assertEquals(page * 2, source.lastRecentsLimit)
    }

    @Test
    fun `expanding a recents row loads that number's history and collapses on a second tap`() =
        runTest(dispatcher) {
            source.recents = listOf(testRecent(1, number = "+13185550100"))
            source.history = listOf(testRecent(1), testRecent(2))
            val viewModel = createViewModel(backgroundScope)
            advanceUntilIdle()

            viewModel.dispatch(KeypadIntent.ExpandRecent("+13185550100"))
            advanceUntilIdle()

            assertEquals("+13185550100", viewModel.state.value.expandedNumber)
            assertEquals(2, viewModel.state.value.expandedHistory.size)

            viewModel.dispatch(KeypadIntent.ExpandRecent("+13185550100"))
            advanceUntilIdle()

            assertNull(viewModel.state.value.expandedNumber)
            assertTrue(viewModel.state.value.expandedHistory.isEmpty())
        }

    @Test
    fun `marking missed calls read writes back exactly the unread ids`() = runTest(dispatcher) {
        source.recents = listOf(
            testRecent(1, type = RecentCallType.MISSED, unread = true),
            testRecent(2, type = RecentCallType.OUTGOING),
        )
        val viewModel = createViewModel(backgroundScope)
        advanceUntilIdle()

        viewModel.dispatch(KeypadIntent.MarkMissedCallsRead)
        advanceUntilIdle()

        assertEquals(listOf(1L), source.markedRead)
    }

    @Test
    fun `refresh re-evaluates the directory, the accounts and the setup state`() =
        runTest(dispatcher) {
            source.callLogAccess = false
            source.recents = listOf(testRecent(1))
            val viewModel = createViewModel(backgroundScope)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.recentGroups.isEmpty())

            source.callLogAccess = true
            viewModel.dispatch(KeypadIntent.Refresh)
            advanceUntilIdle()

            assertEquals(1, telecom.refreshes)
            assertEquals(1, setup.refreshes)
            assertEquals(1, viewModel.state.value.recentGroups.sumOf { it.calls.size })
        }

    @Test
    fun `voicemail without a configured number reports it instead of dialling`() =
        runTest(dispatcher) {
            telecom.voicemail = null
            val viewModel = createViewModel(backgroundScope)
            val effects = collectEffects(viewModel, backgroundScope)
            advanceUntilIdle()

            viewModel.dispatch(KeypadIntent.CallVoicemail)
            advanceUntilIdle()

            assertTrue(effects.single() is KeypadEffect.ShowMessage)
            assertTrue(telecom.placed.isEmpty())
        }

    private fun createViewModel(scope: CoroutineScope) = KeypadViewModel(
        directory = DialerDirectoryRepository(source, scope),
        telecom = telecom,
        setup = setup,
    )

    private fun collectEffects(
        viewModel: KeypadViewModel,
        scope: CoroutineScope,
    ): List<KeypadEffect> {
        val received = mutableListOf<KeypadEffect>()
        scope.launch { viewModel.effects.toList(received) }
        return received
    }
}

private class FakeTelecomAccountsRepository : TelecomAccountsRepository {
    val accountsFlow = MutableStateFlow<List<DialerPhoneAccount>>(emptyList())
    val selectedFlow = MutableStateFlow<String?>(null)
    val requiresChoiceFlow = MutableStateFlow(false)

    var outcome: PlaceCallOutcome = PlaceCallOutcome.Placed
    var voicemail: String? = "+13185550199"
    var refreshes: Int = 0
        private set
    val placed = mutableListOf<Pair<String, String?>>()

    override val accounts: StateFlow<List<DialerPhoneAccount>> = accountsFlow.asStateFlow()
    override val selectedAccountId: StateFlow<String?> = selectedFlow.asStateFlow()
    override val requiresAccountChoice: StateFlow<Boolean> = requiresChoiceFlow.asStateFlow()

    override fun refresh() {
        refreshes++
    }

    override fun select(accountId: String) {
        selectedFlow.value = accountId
        requiresChoiceFlow.value = false
    }

    override fun voicemailNumber(accountId: String?): String? = voicemail

    override fun placeCall(number: String, accountId: String?): PlaceCallOutcome {
        if (outcome is PlaceCallOutcome.Placed) placed += number to accountId
        return outcome
    }
}

private class FakeDialerSetupRepository : DialerSetupRepository {
    private val statusFlow = MutableStateFlow(
        DialerSetupStatus(
            isDefaultDialer = true,
            isCallScreeningApp = true,
            notificationsEnabled = true,
            directoryAccessEnabled = true,
        )
    )
    var refreshes: Int = 0
        private set

    override val status: StateFlow<DialerSetupStatus> = statusFlow.asStateFlow()

    override fun refresh() {
        refreshes++
    }
}
