package dev.triplex.ui.keypad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.triplex.data.telecom.DialerPhoneAccount
import dev.triplex.data.telecom.DialerSetupRepository
import dev.triplex.data.telecom.DialerSetupStatus
import dev.triplex.data.telecom.PlaceCallOutcome
import dev.triplex.data.telecom.SetupRequest
import dev.triplex.data.telecom.TelecomAccountsRepository
import dev.triplex.dialer.DialerContact
import dev.triplex.dialer.DialerDirectoryRepository
import dev.triplex.dialer.RecentCall
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * The keypad's whole brain.
 *
 * Every dependency is an interface over a platform API, so this class — and the
 * search, paging, read-marking and SIM-choice behaviour it owns — is
 * constructible and drivable from a plain `runTest`.
 *
 * The one thing it deliberately does *not* own is asking for roles and runtime
 * permissions: that needs an `Activity` result launcher, so it is reported as a
 * [KeypadEffect.RequestSetup] and handled by `DialerActivity`.
 */
@HiltViewModel
class KeypadViewModel @Inject constructor(
    private val directory: DialerDirectoryRepository,
    private val telecom: TelecomAccountsRepository,
    private val setup: DialerSetupRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val dialString = MutableStateFlow("")
    private val dialpadVisible = MutableStateFlow(false)
    private val expandedNumber = MutableStateFlow<String?>(null)
    private val expandedHistory = MutableStateFlow<List<RecentCall>>(emptyList())
    private val accountChoiceRequiredFor = MutableStateFlow<String?>(null)

    private val _effects = MutableSharedFlow<KeypadEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<KeypadEffect> = _effects.asSharedFlow()

    private val directorySnapshot = combine(
        directory.recents,
        directory.contacts,
        directory.favorites,
        directory.hasMoreRecents,
    ) { recents, contacts, favorites, hasMore ->
        DirectorySnapshot(recents, contacts, favorites, hasMore)
    }

    private val inputSnapshot = combine(
        query,
        dialString,
        dialpadVisible,
    ) { text, dial, visible -> InputSnapshot(text, dial, visible) }

    private val rowSnapshot = combine(
        expandedNumber,
        expandedHistory,
        accountChoiceRequiredFor,
    ) { number, history, choiceFor -> RowSnapshot(number, history, choiceFor) }

    private val platformSnapshot = combine(
        setup.status,
        telecom.accounts,
        telecom.selectedAccountId,
    ) { status, accounts, selected -> PlatformSnapshot(status, accounts, selected) }

    val state: StateFlow<KeypadUiState> = combine(
        directorySnapshot,
        inputSnapshot,
        rowSnapshot,
        platformSnapshot,
    ) { dir, input, row, platform ->
        KeypadUiState(
            query = input.query,
            dialString = input.dialString,
            dialpadVisible = input.dialpadVisible,
            searchResults = searchDirectory(input.query, dir.contacts, dir.recents),
            // The pad's own digits drive smart dial, which is why it is a
            // separate list from the search field's results.
            dialMatches = if (input.dialString.isBlank()) {
                emptyList()
            } else {
                searchDirectory(input.dialString, dir.contacts, dir.recents).take(SMART_DIAL_LIMIT)
            },
            favorites = dir.favorites,
            recentGroups = groupRecentsByDay(dir.recents, LocalDate.now(zone), zone),
            hasMoreRecents = dir.hasMore,
            expandedNumber = row.expandedNumber,
            expandedHistory = row.expandedHistory,
            setup = platform.setup,
            accounts = platform.accounts,
            selectedAccountId = platform.selectedAccountId,
            accountChoiceRequiredFor = row.accountChoiceRequiredFor,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, KeypadUiState())

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun dispatch(intent: KeypadIntent) {
        when (intent) {
            is KeypadIntent.SetQuery -> query.value = intent.value
            is KeypadIntent.SetDialString -> dialString.value = normalizeDialString(intent.value)
            is KeypadIntent.AppendDigit -> dialString.value += intent.digit
            KeypadIntent.DeleteDigit -> dialString.value = dialString.value.dropLast(1)
            KeypadIntent.ClearDialString -> dialString.value = ""
            is KeypadIntent.ShowDialpad -> dialpadVisible.value = intent.visible
            is KeypadIntent.PlaceCall -> placeCall(intent.number)
            is KeypadIntent.SelectAccount -> telecom.select(intent.accountId)
            is KeypadIntent.SelectAccountAndCall -> selectAccountAndCall(intent.accountId)
            KeypadIntent.DismissAccountPicker -> accountChoiceRequiredFor.value = null
            KeypadIntent.CallVoicemail -> callVoicemail()
            is KeypadIntent.RequestSetup -> emit(KeypadEffect.RequestSetup(intent.request))
            is KeypadIntent.ExpandRecent -> expandRecent(intent.number)
            KeypadIntent.LoadMoreRecents -> directory.loadMoreRecents()
            KeypadIntent.Refresh -> refresh()
            KeypadIntent.MarkMissedCallsRead -> directory.markMissedCallsRead()
        }
    }

    private fun refresh() {
        directory.refresh()
        telecom.refresh()
        setup.refresh()
    }

    /**
     * Dial, unless the device cannot say which SIM to dial with.
     *
     * The multi-SIM bug this fixes was silent: with two accounts and no platform
     * default the selection stayed null, the phone-account extra was omitted,
     * and the call left on an arbitrary SIM. Now that state raises the picker
     * instead of dialling.
     */
    private fun placeCall(rawNumber: String) {
        val number = normalizeDialString(rawNumber)
        if (number.isBlank()) {
            emit(KeypadEffect.ShowMessage("Enter a number to call"))
            return
        }
        if (telecom.requiresAccountChoice.value && telecom.selectedAccountId.value == null) {
            accountChoiceRequiredFor.value = number
            return
        }
        submit(number)
    }

    private fun selectAccountAndCall(accountId: String) {
        val pending = accountChoiceRequiredFor.value
        telecom.select(accountId)
        accountChoiceRequiredFor.value = null
        if (pending != null) submit(pending)
    }

    private fun submit(number: String) {
        when (val outcome = telecom.placeCall(number, telecom.selectedAccountId.value)) {
            PlaceCallOutcome.Placed -> {
                dialpadVisible.value = false
                dialString.value = ""
            }
            PlaceCallOutcome.NeedsDefaultDialerRole ->
                emit(KeypadEffect.RequestSetup(SetupRequest.DEFAULT_DIALER, number))
            PlaceCallOutcome.NeedsCallPermission ->
                emit(KeypadEffect.RequestSetup(SetupRequest.CALL_PERMISSION, number))
            is PlaceCallOutcome.Failed -> emit(KeypadEffect.ShowMessage(outcome.message))
        }
    }

    private fun callVoicemail() {
        val number = telecom.voicemailNumber(telecom.selectedAccountId.value)
        if (number.isNullOrBlank()) {
            emit(KeypadEffect.ShowMessage("No voicemail number is configured for this phone account"))
        } else {
            placeCall(number)
        }
    }

    /** Second tap on the same row collapses it, so the row is its own toggle. */
    private fun expandRecent(number: String) {
        if (expandedNumber.value == number) {
            expandedNumber.value = null
            expandedHistory.value = emptyList()
            return
        }
        expandedNumber.value = number
        expandedHistory.value = emptyList()
        viewModelScope.launch {
            val history = directory.historyFor(number)
            if (expandedNumber.value == number) expandedHistory.value = history
        }
    }

    private fun emit(effect: KeypadEffect) {
        _effects.tryEmit(effect)
    }

    private data class DirectorySnapshot(
        val recents: List<RecentCall>,
        val contacts: List<DialerContact>,
        val favorites: List<DialerContact>,
        val hasMore: Boolean,
    )

    private data class InputSnapshot(
        val query: String,
        val dialString: String,
        val dialpadVisible: Boolean,
    )

    private data class RowSnapshot(
        val expandedNumber: String?,
        val expandedHistory: List<RecentCall>,
        val accountChoiceRequiredFor: String?,
    )

    private data class PlatformSnapshot(
        val setup: DialerSetupStatus,
        val accounts: List<DialerPhoneAccount>,
        val selectedAccountId: String?,
    )

    private companion object {
        /** Smart dial is a shortcut, not a browser: a few hits, above the keys. */
        const val SMART_DIAL_LIMIT = 4
    }
}
