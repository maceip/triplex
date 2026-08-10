package dev.triplex.ui.keypad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.triplex.data.telecom.DialerPhoneAccount
import dev.triplex.data.telecom.SetupRequest
import dev.triplex.dialer.DialerContact
import dev.triplex.dialer.RecentCall
import dev.triplex.dialer.RecentCallType
import dev.triplex.ui.components.OutlinedTextInput
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexDialDisplay
import dev.triplex.ui.components.TriplexEmptyState
import dev.triplex.ui.components.TriplexIconButton
import dev.triplex.ui.components.TriplexListRow
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexSectionHeader
import dev.triplex.ui.components.TriplexSetupBanner
import dev.triplex.ui.components.TriplexStatusPill
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import zed.rainxch.rikkaicons.tokens.ChevronDown
import zed.rainxch.rikkaicons.tokens.Clock
import zed.rainxch.rikkaicons.tokens.Eraser
import zed.rainxch.rikkaicons.tokens.Mail
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaicons.tokens.User
import zed.rainxch.rikkaicons.tokens.Users
import zed.rainxch.rikkaicons.tokens.X
import zed.rainxch.rikkaui.components.ui.avatar.Avatar
import zed.rainxch.rikkaui.components.ui.avatar.AvatarAnimation
import zed.rainxch.rikkaui.components.ui.avatar.AvatarSize
import zed.rainxch.rikkaui.components.ui.call.CallDirection
import zed.rainxch.rikkaui.components.ui.call.CallHistoryItem
import zed.rainxch.rikkaui.components.ui.call.GlassDialpad
import zed.rainxch.rikkaui.components.ui.fab.Fab
import zed.rainxch.rikkaui.components.ui.fab.FabSize
import zed.rainxch.rikkaui.components.ui.fab.FabVariant
import zed.rainxch.rikkaui.components.ui.glass.GlassIconButton
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassPanel
import zed.rainxch.rikkaui.components.ui.glass.LocalGlassBackdrop
import zed.rainxch.rikkaui.components.ui.glass.glassBackdropSource
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassBackdrop
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassBackdrops
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * The keypad surface (reskin.md §3.1).
 *
 * This is a dialer first and a directory second. The pad is not an overlay you
 * raise: it is docked at the bottom of the screen permanently, because the one
 * thing this screen exists to do is place a call, and a phone that hides its
 * keys behind a button spends a tap proving it. What *is* raised on demand is
 * the directory — recents and contacts each sit behind a small FAB stacked above
 * the dock, and the search field lives inside the contacts panel rather than
 * eating a row of the main screen.
 *
 * Assembled entirely from `ui/components` wrappers and RikkaUI components: no
 * raw Material widgets, no hand-mixed colours, nothing this screen would have to
 * unlearn in Phase 6.
 */
@Composable
fun KeypadScreen(
    initialNumber: String,
    callInBackground: Boolean,
    retryNumber: String?,
    onRetryConsumed: () -> Unit,
    onReturnToCall: () -> Unit,
    onRequestSetup: (SetupRequest, String?) -> Unit,
    onMessage: (String) -> Unit,
    onOpenTriplex: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeypadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    // Recents live in their own panel now, so paging is driven by that list.
    val recentsState = rememberLazyListState()
    val density = LocalDensity.current

    var openPanel by remember { mutableStateOf<DirectoryPanel?>(null) }
    var accountPickerVisible by remember { mutableStateOf(false) }
    // The dock is measured rather than guessed: its height depends on the smart
    // dial hits and on whether the SIM affordance is there at all, and both the
    // list's bottom inset and the FAB stack have to clear it.
    var dockHeight by remember { mutableStateOf(0.dp) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is KeypadEffect.RequestSetup -> onRequestSetup(effect.request, effect.retryNumber)
                is KeypadEffect.ShowMessage -> onMessage(effect.text)
            }
        }
    }

    // A `tel:` intent lands on the pad with the number already typed. The pad is
    // always on screen now, so `ShowDialpad` no longer decides anything visual —
    // it is kept because the view model still tracks the flag and clears it when
    // a call is placed.
    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) {
            viewModel.dispatch(KeypadIntent.SetDialString(initialNumber))
            viewModel.dispatch(KeypadIntent.ShowDialpad(true))
        }
    }

    // The activity resumes a call that was interrupted by a consent prompt.
    LaunchedEffect(retryNumber) {
        retryNumber?.let {
            viewModel.dispatch(KeypadIntent.PlaceCall(it))
            onRetryConsumed()
        }
    }

    // Seeing the list is what makes a missed call "seen"; clear the platform's
    // new-missed-call flag so the system badge does not outlive the glance.
    val unreadCount = remember(state.recentGroups) {
        state.recentGroups.sumOf { group -> group.calls.count(RecentCall::unread) }
    }
    LaunchedEffect(unreadCount) {
        if (unreadCount > 0) viewModel.dispatch(KeypadIntent.MarkMissedCallsRead)
    }

    // Paging on scroll: ask for another page while the tail of the recents panel
    // is in view.
    LaunchedEffect(recentsState) {
        snapshotFlow {
            val info = recentsState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }
            .distinctUntilChanged()
            .collectLatest { nearEnd ->
                if (nearEnd) viewModel.dispatch(KeypadIntent.LoadMoreRecents)
            }
    }

    // The shell records only its atmospheric gradient, and a lens over a smooth
    // gradient returns a smooth gradient — which is why glass laid over this
    // screen used to read as flat tinted shapes. Recording the list as a second
    // source gives the dock and the panels edges and text to bend. Both are
    // siblings drawn after the list, never children of it, so they cannot sample
    // themselves.
    val listBackdrop = rememberGlassBackdrop()
    val scenery = rememberGlassBackdrops(LocalGlassBackdrop.current, listBackdrop)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().glassBackdropSource(listBackdrop),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 18.dp,
                bottom = dockHeight + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                TriplexScreenHeader(
                    title = "Phone",
                    eyebrow = "Triplex",
                    trailing = {
                        TriplexButton(
                            text = "Triplex app",
                            onClick = onOpenTriplex,
                            style = TriplexButtonStyle.GHOST,
                        )
                    },
                )
            }

            if (callInBackground) {
                item(key = "call-in-background") {
                    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Current call is on hold", modifier = Modifier.weight(1f))
                            TriplexButton(
                                text = "Return to call",
                                onClick = onReturnToCall,
                                style = TriplexButtonStyle.GHOST,
                            )
                        }
                    }
                }
            }

            items(state.setup.outstanding, key = { "setup-${it.name}" }) { request ->
                val copy = setupCopy(request)
                TriplexSetupBanner(
                    title = copy.title,
                    description = copy.description,
                    actionLabel = copy.action,
                    onAction = { viewModel.dispatch(KeypadIntent.RequestSetup(request)) },
                )
            }

            // Favourites stay on the main screen: they are one-tap dialling, which
            // belongs next to the pad rather than two taps deep in a panel — and
            // they are what gives the docked glass something to refract.
            favoritesBody(state, viewModel)
        }

        // Everything below refracts the atmosphere *and* the list it covers, so
        // the keys and the panels pick up the content behind them instead of a
        // flat tint.
        CompositionLocalProvider(LocalGlassBackdrop provides scenery) {
            DialDock(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size ->
                        dockHeight = with(density) { size.height.toDp() }
                    },
                onIntent = viewModel::dispatch,
                onPaste = {
                    clipboard.getText()?.text?.let {
                        viewModel.dispatch(KeypadIntent.SetDialString(it))
                    }
                },
                onOpenAccountPicker = { accountPickerVisible = true },
            )

            // Recents and contacts are secondary to dialling, so they are small
            // FABs stacked on the dock's shoulder rather than sections competing
            // with the pad for the screen. They step aside while a panel is open,
            // where they would only float over their own scrim.
            if (openPanel == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = dockHeight + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Fab(
                        icon = RikkaIcons.Clock,
                        label = "Recent calls",
                        onClick = { openPanel = DirectoryPanel.RECENTS },
                        variant = FabVariant.Secondary,
                        size = FabSize.Small,
                        shape = RikkaTheme.shapes.full,
                    )
                    Fab(
                        icon = RikkaIcons.Users,
                        label = "Contacts and search",
                        onClick = { openPanel = DirectoryPanel.CONTACTS },
                        variant = FabVariant.Secondary,
                        size = FabSize.Small,
                        shape = RikkaTheme.shapes.full,
                    )
                }
            }

            openPanel?.let { panel ->
                // A panel you cannot leave is a trap, so it gets the two exits
                // every sheet is expected to have: a tap on the scrim outside it,
                // and the close button in its header — plus the system back
                // gesture, wired below. The scrim is drawn before the panel so it
                // never steals the panel's own touches, and is light enough that
                // the panel still reads as glass over a visible list rather than
                // over a black rectangle.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RikkaTheme.colors.background.copy(alpha = 0.3f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Close the panel",
                        ) { openPanel = null },
                )

                DirectorySheet(
                    panel = panel,
                    state = state,
                    viewModel = viewModel,
                    recentsState = recentsState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onDismiss = { openPanel = null },
                )
            }
        }

        // One handler for both raised surfaces, topmost first.
        BackHandler(enabled = openPanel != null || accountPickerVisible) {
            if (accountPickerVisible) accountPickerVisible = false else openPanel = null
        }

        if (accountPickerVisible && state.accounts.size > 1) {
            AccountChoiceOverlay(
                number = null,
                accounts = state.accounts,
                modifier = Modifier.align(Alignment.BottomCenter),
                title = "Outgoing SIM",
                description = "New calls go out on the account you pick here.",
                selectedAccountId = state.selectedAccountId,
                onSelect = {
                    viewModel.dispatch(KeypadIntent.SelectAccount(it))
                    accountPickerVisible = false
                },
                onDismiss = { accountPickerVisible = false },
            )
        }

        state.accountChoiceRequiredFor?.let { pending ->
            AccountChoiceOverlay(
                number = pending,
                accounts = state.accounts,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSelect = { viewModel.dispatch(KeypadIntent.SelectAccountAndCall(it)) },
                onDismiss = { viewModel.dispatch(KeypadIntent.DismissAccountPicker) },
            )
        }
    }
}

/** The two directory surfaces the stacked FABs raise. */
private enum class DirectoryPanel { RECENTS, CONTACTS }

/**
 * Recents or contacts, raised over the dialer as a slab of glass.
 *
 * The search field is part of the contacts panel rather than a permanent row on
 * the main screen: searching is something you go and do, and the directory it
 * searches is right underneath it. Recents get the hoisted [recentsState] so the
 * screen's paging effect keeps loading older calls as the panel is scrolled.
 */
@Composable
private fun DirectorySheet(
    panel: DirectoryPanel,
    state: KeypadUiState,
    viewModel: KeypadViewModel,
    recentsState: LazyListState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contactsState = rememberLazyListState()
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.86f)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(RikkaTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.sm),
        ) {
            TriplexScreenHeader(
                title = when (panel) {
                    DirectoryPanel.RECENTS -> "Recents"
                    DirectoryPanel.CONTACTS -> "Contacts"
                },
                trailing = {
                    TriplexIconButton(
                        imageVector = RikkaIcons.X,
                        contentDescription = "Close the panel",
                        onClick = onDismiss,
                    )
                },
            )

            if (panel == DirectoryPanel.CONTACTS) {
                OutlinedTextInput(
                    label = "Search contacts and calls",
                    value = state.query,
                    onValueChange = { viewModel.dispatch(KeypadIntent.SetQuery(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Name or number",
                )
            }

            LazyColumn(
                state = if (panel == DirectoryPanel.RECENTS) recentsState else contactsState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (panel) {
                    DirectoryPanel.RECENTS -> recentsBody(state, viewModel)
                    DirectoryPanel.CONTACTS -> if (state.searching) {
                        searchResults(state, viewModel, onDismiss)
                    } else {
                        item(key = "search-prompt") {
                            TriplexEmptyState(
                                title = "Search the directory",
                                message = "Type a name or a number to find a contact or a past call.",
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    state: KeypadUiState,
    viewModel: KeypadViewModel,
    onDismiss: () -> Unit,
) {
    if (state.searchResults.isEmpty()) {
        item(key = "search-empty") {
            TriplexEmptyState(
                title = "No matches",
                message = "Nothing in your contacts or call history matches \"${state.query}\".",
                actionLabel = "Dial it anyway",
                onAction = {
                    // The pad is always on screen, so the query moves onto it and
                    // the panel gets out of the way rather than raising anything.
                    viewModel.dispatch(KeypadIntent.SetDialString(state.query))
                    onDismiss()
                },
            )
        }
        return
    }
    items(state.searchResults, key = { it.id }) { result ->
        TriplexListRow(
            title = if (result.starred) "★ ${result.title}" else result.title,
            subtitle = result.subtitle,
            leadingIcon = if (result.source == SearchSource.CONTACT) {
                RikkaIcons.User
            } else {
                RikkaIcons.Phone
            },
            actionIcon = RikkaIcons.Phone,
            actionContentDescription = "Call ${result.title}",
            onAction = { viewModel.dispatch(KeypadIntent.PlaceCall(result.number)) },
        )
    }
}

/**
 * The one-tap strip on the main screen.
 *
 * Without Phone access there is nothing to show but the consent that unblocks
 * it, so that stands in for the whole section.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.favoritesBody(
    state: KeypadUiState,
    viewModel: KeypadViewModel,
) {
    if (!state.setup.directoryAccessEnabled) {
        directoryBlocked(viewModel)
        return
    }

    if (state.favorites.isEmpty()) return

    item(key = "favorites-header") { TriplexSectionHeader("Favorites") }
    item(key = "favorites-strip") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.favorites, key = { "${it.id}:${it.number}" }) { contact ->
                FavoriteChip(contact) {
                    viewModel.dispatch(KeypadIntent.PlaceCall(contact.number))
                }
            }
        }
    }
}

/** The day-grouped call history, as shown inside the recents panel. */
private fun androidx.compose.foundation.lazy.LazyListScope.recentsBody(
    state: KeypadUiState,
    viewModel: KeypadViewModel,
) {
    if (!state.setup.directoryAccessEnabled) {
        directoryBlocked(viewModel)
        return
    }

    if (state.recentGroups.isEmpty()) {
        item(key = "recents-empty") {
            TriplexEmptyState(
                title = "No recent calls",
                message = "Calls you make and receive show up here, newest first.",
            )
        }
        return
    }

    state.recentGroups.forEach { group ->
        item(key = "day-${group.date}") { TriplexSectionHeader(group.label) }
        items(group.calls, key = { "recent-${it.id}" }) { call ->
            val title = call.name.ifBlank { call.number.ifBlank { UNKNOWN_CALLER } }
            Column {
                CallHistoryItem(
                    name = title,
                    detail = call.typeLabel,
                    timestamp = call.timeLabel,
                    direction = call.type.toCallDirection(),
                    // Unread rides the badge slot rather than a bullet glued to
                    // the name, so a screen reader announces it as its own thing.
                    agentBadge = if (call.unread) "NEW" else "",
                    // The call log is read-only here — there is no delete intent
                    // to wire, and an empty callback hides the swipe action.
                    callBackLabel = "Call $title",
                    onClick = { viewModel.dispatch(KeypadIntent.ExpandRecent(call.number)) },
                    onCallBack = { viewModel.dispatch(KeypadIntent.PlaceCall(call.number)) },
                )
                if (state.expandedNumber == call.number) {
                    ExpandedHistory(state.expandedHistory)
                }
            }
        }
    }

    if (state.hasMoreRecents) {
        item(key = "recents-more") {
            Text(
                "Loading older calls…",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The single consent that stands between this screen and the whole directory. */
private fun androidx.compose.foundation.lazy.LazyListScope.directoryBlocked(
    viewModel: KeypadViewModel,
) {
    item(key = "directory-blocked") {
        TriplexEmptyState(
            title = "Favorites, recents & contacts",
            message = "Allow Phone access to show the device directory here.",
            actionLabel = "Allow access",
            onAction = {
                viewModel.dispatch(KeypadIntent.RequestSetup(SetupRequest.DIRECTORY_ACCESS))
            },
        )
    }
}

/**
 * Collapses the platform's seven call-log types onto the three the design
 * system draws.
 *
 * Declined and blocked calls are missed calls from the caller's point of view —
 * they rang and were not taken — so they get the destructive arrow rather than
 * a neutral one. Voicemail arrived, so it is incoming.
 */
private fun RecentCallType.toCallDirection(): CallDirection = when (this) {
    RecentCallType.OUTGOING -> CallDirection.Outgoing
    RecentCallType.MISSED, RecentCallType.REJECTED, RecentCallType.BLOCKED -> CallDirection.Missed
    RecentCallType.INCOMING, RecentCallType.VOICEMAIL, RecentCallType.OTHER -> CallDirection.Incoming
}

@Composable
private fun ExpandedHistory(history: List<RecentCall>) {
    Column(
        modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (history.isEmpty()) {
            Text(
                "Loading call history…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        history.forEach { entry ->
            Text(
                "${entry.typeLabel} · ${entry.whenLabel} · ${formatDuration(entry.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One favourite in the horizontal strip.
 *
 * Fixed width and a single-line label on purpose: intrinsic sizing gave every
 * contact a differently shaped card, which made the row look ragged. The avatar
 * is the design system's, so a favourite and a recent call read as the same
 * kind of thing.
 */
@Composable
private fun FavoriteChip(contact: DialerContact, onCall: () -> Unit) {
    val display = contact.name.ifBlank { contact.number }
    TriplexCard(onClick = onCall) {
        Column(
            modifier = Modifier
                .width(FavoriteChipWidth)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(
                fallback = initialsOf(display),
                size = AvatarSize.Lg,
                animation = AvatarAnimation.None,
                label = "Call $display",
            )
            Text(
                display,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val FavoriteChipWidth = 96.dp

/** Up to two initials for the avatar; falls back to the first character. */
private fun initialsOf(display: String): String =
    display.split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifBlank { display.take(1).uppercase() }

/**
 * The permanent dialer, docked at the bottom of the screen.
 *
 * Read top to bottom it is the order the task is performed in: who the digits
 * might belong to, what has been typed, the keys, then the call. Smart-dial hits
 * sit directly above the display — typing `526` should offer "Jane Kowalski"
 * long before the full number is entered — but only the two strongest, because
 * the dock is fixed furniture and a list that grows freely would push the keys
 * off the bottom of the screen.
 *
 * The SIM switcher used to be the first thing in here, above the keys, which
 * gave the rarest control on the screen the best position on it. It is now a
 * quiet ghost button under the action row that names the active account and
 * opens the picker; it appears at all only on a device with more than one.
 */
@Composable
private fun DialDock(
    state: KeypadUiState,
    onIntent: (KeypadIntent) -> Unit,
    onPaste: () -> Unit,
    onOpenAccountPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(RikkaTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.md),
        ) {
            state.dialMatches.take(DockMatchLimit).forEach { match ->
                TriplexListRow(
                    title = match.title,
                    subtitle = match.subtitle,
                    actionIcon = RikkaIcons.Phone,
                    actionContentDescription = "Call ${match.title}",
                    onAction = { onIntent(KeypadIntent.PlaceCall(match.number)) },
                )
            }

            TriplexDialDisplay(
                value = state.dialString,
                onValueChange = { onIntent(KeypadIntent.SetDialString(it)) },
                onPaste = onPaste,
            )

            // The design system's own pad, not a local reimplementation: round
            // glass keys with a specular highlight, a gradient rim, shadowed
            // white glyphs and press physics. The long-press `+` on 0 is part of
            // its default key set, so it needs no wiring here.
            // Prominent, not the default Subtle: the keys sit one level deep
            // inside the panel, and `rememberGlassStyle` steps the level down
            // once per nesting level — so Prominent here is what actually lands
            // on Regular tokens (12dp blur, 26dp refraction) at the key itself.
            GlassDialpad(
                onKeyPress = { digit -> onIntent(KeypadIntent.AppendDigit(digit)) },
                level = GlassLevel.Prominent,
            )

            // One primary action, flanked rather than matched: Call is the
            // design system's Large FAB — a 72dp circle — and voicemail and
            // backspace are the same round glass the dial keys are made of, so
            // the row reads as a fourth rank of the pad rather than as a
            // separate toolbar. Three equal ghost buttons spread across the row
            // is what made the old dialer read as a settings screen; two dark
            // rounded squares beside a circle read as an accident.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    RikkaTheme.spacing.xl,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    onClick = { onIntent(KeypadIntent.CallVoicemail) },
                    size = DockSecondaryKeySize,
                    // Matches the keys: Prominent here is what survives the
                    // one-level step-down inside the panel and lands on Regular.
                    level = GlassLevel.Prominent,
                    label = "Call voicemail",
                ) {
                    Icon(imageVector = RikkaIcons.Mail, contentDescription = null, size = IconSize.Lg)
                }
                Fab(
                    icon = RikkaIcons.Phone,
                    label = "Call",
                    onClick = { onIntent(KeypadIntent.PlaceCall(state.dialString)) },
                    size = FabSize.Large,
                    enabled = state.canPlaceCall,
                    shape = RikkaTheme.shapes.full,
                )
                GlassIconButton(
                    onClick = { onIntent(KeypadIntent.DeleteDigit) },
                    enabled = state.dialString.isNotEmpty(),
                    size = DockSecondaryKeySize,
                    level = GlassLevel.Prominent,
                    // `X` renders as a filled tile in the Phosphor Fill weight,
                    // which reads as a blob at this size; the eraser is both
                    // clearer and a truer name for what the key does.
                    label = "Delete the last digit",
                ) {
                    Icon(imageVector = RikkaIcons.Eraser, contentDescription = null, size = IconSize.Lg)
                }
            }

            if (state.accounts.size > 1) {
                val active = state.accounts.firstOrNull { it.id == state.selectedAccountId }
                TriplexButton(
                    text = active?.label ?: "Choose a SIM",
                    onClick = onOpenAccountPicker,
                    style = TriplexButtonStyle.GHOST,
                    leadingIcon = RikkaIcons.ChevronDown,
                )
            }

            Text(
                "Hold 0 for +",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Smart dial in the dock is a hint, not a browser — the keys keep their room. */
/** Diameter of the voicemail and backspace keys flanking the Call button. */
private val DockSecondaryKeySize = 56.dp

private const val DockMatchLimit = 2

/**
 * The SIM picker, in both of the situations that need one.
 *
 * Its original job — and the defaults it still carries — is the forced choice
 * the old code silently skipped: several callable accounts, no platform default,
 * nothing chosen, which is the exact state in which dialling would have dropped
 * `EXTRA_PHONE_ACCOUNT_HANDLE`. Passing a null [number] and a
 * [selectedAccountId] turns the same card into the plain switcher behind the
 * dock's SIM button, so there is one account list on this screen rather than two.
 */
@Composable
private fun AccountChoiceOverlay(
    number: String?,
    accounts: List<DialerPhoneAccount>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Call using",
    description: String = "This device has more than one SIM and no default for outgoing calls.",
    selectedAccountId: String? = null,
) {
    TriplexCard(modifier = modifier.fillMaxWidth().padding(16.dp), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            number?.let { TriplexStatusPill(text = it) }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            accounts.forEach { account ->
                TriplexButton(
                    text = account.label,
                    onClick = { onSelect(account.id) },
                    modifier = Modifier.fillMaxWidth(),
                    // The active account is the filled one; there is no active
                    // account at all in the forced case, which is why it is asking.
                    style = if (account.id == selectedAccountId) {
                        TriplexButtonStyle.PRIMARY
                    } else {
                        TriplexButtonStyle.OUTLINE
                    },
                )
            }
            TriplexButton(
                text = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                style = TriplexButtonStyle.GHOST,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

private data class SetupCopy(val title: String, val description: String, val action: String)

private fun setupCopy(request: SetupRequest): SetupCopy = when (request) {
    SetupRequest.DEFAULT_DIALER -> SetupCopy(
        title = "Finish phone setup",
        description = "Triplex has to be the default phone app to place and answer calls.",
        action = "Set as default phone app",
    )
    SetupRequest.CALL_SCREENING -> SetupCopy(
        title = "Enable call screening",
        description = "Screening is how the agent picks up before you do.",
        action = "Enable call screening",
    )
    SetupRequest.NOTIFICATIONS -> SetupCopy(
        title = "Enable notifications",
        description = "Incoming calls need a notification to reach you when the app is closed.",
        action = "Enable incoming-call notifications",
    )
    SetupRequest.DIRECTORY_ACCESS -> SetupCopy(
        title = "Allow Phone access",
        description = "Contacts and call history stay on the device; they never leave it.",
        action = "Allow access",
    )
    SetupRequest.CALL_PERMISSION -> SetupCopy(
        title = "Allow calling",
        description = "Android needs the Phone permission before Triplex can dial.",
        action = "Allow calling",
    )
}

private fun formatDuration(seconds: Long): String = when {
    seconds <= 0L -> "no answer"
    seconds < 60L -> "${seconds}s"
    else -> "%d:%02d".format(seconds / 60L, seconds % 60L)
}
