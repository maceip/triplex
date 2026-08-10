package dev.triplex.ui.keypad

import dev.triplex.data.telecom.DialerPhoneAccount
import dev.triplex.data.telecom.DialerSetupStatus
import dev.triplex.data.telecom.SetupRequest
import dev.triplex.dialer.DialerContact
import dev.triplex.dialer.RecentCall

/**
 * Everything the keypad renders, in one immutable value.
 *
 * The screen reads only this; it owns no directory state of its own, which is
 * what makes the search, paging and SIM-choice behaviour testable without
 * Compose.
 */
data class KeypadUiState(
    /** The unified search field (reskin.md §3.1): one query over contacts and history. */
    val query: String = "",
    /** The dialpad overlay's number. Separate from [query] — the pad dials, the field searches. */
    val dialString: String = "",
    val dialpadVisible: Boolean = false,
    val searchResults: List<KeypadSearchResult> = emptyList(),
    /** T9 hits for the digits currently on the pad, shown above the keys. */
    val dialMatches: List<KeypadSearchResult> = emptyList(),
    val favorites: List<DialerContact> = emptyList(),
    val recentGroups: List<RecentDayGroup> = emptyList(),
    val hasMoreRecents: Boolean = false,
    /** The recents row expanded into its per-number history, if any. */
    val expandedNumber: String? = null,
    val expandedHistory: List<RecentCall> = emptyList(),
    val setup: DialerSetupStatus = DialerSetupStatus(),
    val accounts: List<DialerPhoneAccount> = emptyList(),
    val selectedAccountId: String? = null,
    /**
     * The number waiting on a SIM choice.
     *
     * Non-null means the picker is up: more than one callable account exists,
     * the platform has no default, and the user has not chosen — so dialling
     * now would silently drop the phone-account handle.
     */
    val accountChoiceRequiredFor: String? = null,
) {
    val searching: Boolean get() = query.isNotBlank()

    val canPlaceCall: Boolean get() = dialString.isNotBlank()
}

/** Everything the keypad can be asked to do. */
sealed interface KeypadIntent {
    data class SetQuery(val value: String) : KeypadIntent
    data class SetDialString(val value: String) : KeypadIntent
    data class AppendDigit(val digit: Char) : KeypadIntent
    data object DeleteDigit : KeypadIntent
    data object ClearDialString : KeypadIntent
    data class ShowDialpad(val visible: Boolean) : KeypadIntent
    data class PlaceCall(val number: String) : KeypadIntent
    data class SelectAccount(val accountId: String) : KeypadIntent
    data class SelectAccountAndCall(val accountId: String) : KeypadIntent
    data object DismissAccountPicker : KeypadIntent
    data object CallVoicemail : KeypadIntent
    data class RequestSetup(val request: SetupRequest) : KeypadIntent
    data class ExpandRecent(val number: String) : KeypadIntent
    data object LoadMoreRecents : KeypadIntent
    data object Refresh : KeypadIntent
    data object MarkMissedCallsRead : KeypadIntent
}

/** One-shot outcomes the screen has to hand back to the activity. */
sealed interface KeypadEffect {
    /**
     * A role or runtime permission is missing. Only an `Activity` can ask, so
     * the screen forwards this up; [retryNumber] is the call to resume once the
     * user consents.
     */
    data class RequestSetup(
        val request: SetupRequest,
        val retryNumber: String? = null,
    ) : KeypadEffect

    data class ShowMessage(val text: String) : KeypadEffect
}
