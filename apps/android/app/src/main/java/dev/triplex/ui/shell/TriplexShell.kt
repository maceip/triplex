package dev.triplex.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import dev.triplex.domain.call.CallPhase
import dev.triplex.ui.agent.AgentHomeScreen
import dev.triplex.ui.agent.InboundSetupScreen
import dev.triplex.ui.agent.OutboundSetupScreen
import dev.triplex.ui.agent.RunDetailScreen
import dev.triplex.ui.agent.VoiceCloneScreen
import dev.triplex.ui.call.incoming.IncomingCallSheetHost
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.enrollment.EnrollmentScreen
import zed.rainxch.rikkaicons.core.IconToken
import zed.rainxch.rikkaicons.tokens.Mic
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.glass.LocalGlassBackdrop
import zed.rainxch.rikkaui.components.ui.glass.glassBackdropSource
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassBackdrop
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassBackdrops
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.navigationbar.GlassNavigationBar
import zed.rainxch.rikkaui.components.ui.navigationbar.NavigationBarItem
import zed.rainxch.rikkaui.components.ui.navigationbar.NavigationBarItemLayout
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.scaffold.ScaffoldWindowInsets
import zed.rainxch.rikkaui.foundation.RikkaTheme
import dev.triplex.ui.theme.TriplexDesign

/**
 * The single application shell.
 *
 * Hosts the enrollment gate, the two-tab bottom navigation (Keypad | Agent),
 * and the type-safe nav graph declared in [ShellRoute]. It is rendered by
 * `DialerActivity`, which keeps every piece of platform identity (launcher,
 * `DIAL`/`tel:`, `singleTask`, `showWhenLocked`, full-screen-intent target).
 *
 * Presentation order, highest priority first:
 * 1. **Incoming sheet.** A ringing or screened session is drawn as an overlay
 *    above the nav host, with the bottom bar intact and navigation untouched. It
 *    is not a destination, so it cannot be popped, a tab switch cannot leave it,
 *    and after process death it returns with the session it is derived from.
 * 2. **Call surface.** Once a call is past those phases the keypad destination
 *    renders full-screen with no bottom bar, exactly as the dialer did before
 *    the consolidation, and the shell pulls navigation back to the keypad tab.
 * 3. **Tabs**, with the enrollment gate scoped to the agent tab only.
 *
 * **The enrollment gate never covers telephony.** Enrollment is a *gateway*
 * concern — it obtains the device token the agent needs — while this activity
 * holds the platform dialer role. An app that owns that role and then refuses to
 * dial is broken in the way that matters most, so a missing device token gates
 * the agent tab and nothing else: the keypad, recents, contacts and emergency
 * dialling stay reachable at all times. (`reskin.md` §2.2 originally drew the
 * gate in front of the whole shell; that predates the shell also being the
 * dialer.)
 *
 * @param initiallyEnrolled whether a device token already existed when the
 *   activity was created.
 * @param keypadContent the dialer surface. It receives a callback that switches
 *   to the agent tab.
 */
@Composable
fun TriplexShell(
    initiallyEnrolled: Boolean,
    keypadContent: @Composable (onOpenAgent: () -> Unit) -> Unit,
) {
    var enrolled by rememberSaveable { mutableStateOf(initiallyEnrolled) }
    val session by hiltViewModel<ShellViewModel>().session.collectAsState()

    // Two regimes, and the difference matters. A call being *decided* is an
    // overlay: the sheet floats over whatever tab the user is on, the bottom bar
    // stays, and navigation is not hijacked — so answering a call does not cost
    // the user their place in the app. A call already in progress owns the whole
    // surface, as it did before.
    val incoming = session?.phase == CallPhase.RINGING || session?.phase == CallPhase.SCREENING
    val callSurfaceActive = session != null && !incoming

    ShellScaffold(
        enrolled = enrolled,
        onEnrolled = { enrolled = true },
        callSurfaceActive = callSurfaceActive,
        incomingCallActive = incoming,
        keypadContent = keypadContent,
    )
}

@Composable
private fun ShellScaffold(
    enrolled: Boolean,
    onEnrolled: () -> Unit,
    callSurfaceActive: Boolean,
    incomingCallActive: Boolean,
    keypadContent: @Composable (onOpenAgent: () -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    // A call must not be hidden behind a tab the user happens to be on.
    LaunchedEffect(callSurfaceActive) {
        if (callSurfaceActive) navController.switchTab(ShellRoute.Keypad)
    }

    val hasBottomBar = !callSurfaceActive
    val system = rememberSystemInsets()
    val content = resolveShellContentInsets(system, hasBottomBar)

    // One backdrop for the whole shell. It has to wrap the scaffold rather than
    // sit inside a screen, because the floating nav bar is chrome *outside* the
    // nav host and glass with no backdrop in scope degrades to a flat tint.
    TriplexBackground {
        // The atmosphere alone is a smooth gradient, and a lens over a smooth
        // gradient returns a smooth gradient — glass over it reads as flat tint.
        // Recording the nav host as a second source gives the floating bar the
        // screen's own edges and text to refract. The bar is drawn after the
        // host, never inside it, so it cannot sample itself.
        val screenBackdrop = rememberGlassBackdrop()
        val scenery = rememberGlassBackdrops(LocalGlassBackdrop.current, screenBackdrop)

        Scaffold(
            // The gradient above is the background; an opaque scaffold would
            // paint over it.
            containerColor = Color.Transparent,
            contentWindowInsets = ScaffoldWindowInsets(
                left = content.left,
                top = content.top,
                right = content.right,
                bottom = content.bottom,
            ),
            bottomBar = {
                if (hasBottomBar) {
                    ShellNavigationBar(
                        currentDestination = currentDestination,
                        onSelect = navController::switchTab,
                        bottomInset = system.bottom,
                        backdrop = scenery,
                    )
                }
            },
        ) { _ ->
            // Rikka's Scaffold *places* content inside the reserved area rather
            // than merely reporting it, so the PaddingValues it hands back are
            // informational. Re-applying them here would pad twice.
            Box(modifier = Modifier.fillMaxSize()) {
                ShellNavHost(
                    navController = navController,
                    enrolled = enrolled,
                    onEnrolled = onEnrolled,
                    keypadContent = keypadContent,
                    modifier = Modifier.glassBackdropSource(screenBackdrop),
                )

                // The sheet is hosted here rather than in the nav graph on
                // purpose: it is derived from call state, so it is not on the
                // back stack, it cannot be popped, and it survives a tab switch
                // and process death.
                if (incomingCallActive) {
                    IncomingCallSheetHost()
                }
            }
        }
    }
}

/** The system-bar insets, widened by the display cutout so landscape notches clear. */
@Composable
private fun rememberSystemInsets(): ShellInsets {
    val direction = LocalLayoutDirection.current
    val padding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    return remember(padding, direction) {
        ShellInsets(
            left = padding.calculateLeftPadding(direction),
            top = padding.calculateTopPadding(),
            right = padding.calculateRightPadding(direction),
            bottom = padding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun ShellNavHost(
    navController: NavHostController,
    enrolled: Boolean,
    onEnrolled: () -> Unit,
    keypadContent: @Composable (onOpenAgent: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = TriplexDesign.motion

    NavHost(
        navController = navController,
        startDestination = ShellRoute.Keypad,
        modifier = modifier,
        enterTransition = {
            fadeIn(tween(motion.standardMillis)) +
                slideInHorizontally(tween(motion.standardMillis)) { width -> width / 10 }
        },
        exitTransition = {
            fadeOut(tween(motion.quickMillis)) +
                slideOutHorizontally(tween(motion.standardMillis)) { width -> -width / 16 }
        },
        popEnterTransition = {
            fadeIn(tween(motion.standardMillis)) +
                scaleIn(
                    animationSpec = tween(motion.standardMillis),
                    initialScale = motion.navigationScale,
                )
        },
        // Navigation Compose drives this transition interactively during the
        // system back gesture, revealing the destination underneath.
        popExitTransition = {
            fadeOut(tween(motion.standardMillis)) +
                scaleOut(
                    animationSpec = tween(motion.standardMillis),
                    targetScale = motion.navigationScale,
                    transformOrigin = TransformOrigin.Center,
                )
        },
    ) {
        composable<ShellRoute.Keypad> {
            keypadContent { navController.switchTab(ShellRoute.AgentGraph) }
        }

        navigation<ShellRoute.AgentGraph>(startDestination = ShellRoute.AgentHome) {
            composable<ShellRoute.AgentHome> {
                // The gate sits on the graph's start destination, so the deeper
                // agent routes are unreachable while unenrolled; they are only
                // navigated to from here. Revisit if they ever gain deep links.
                if (!enrolled) {
                    EnrollmentScreen(onEnrollmentComplete = onEnrolled)
                } else {
                    AgentHomeScreen(
                        onOpenInbound = { navController.navigate(ShellRoute.AgentInbound) },
                        onOpenOutbound = { navController.navigate(ShellRoute.AgentOutbound) },
                        onOpenVoice = { navController.navigate(ShellRoute.AgentVoice) },
                        onOpenRun = { runId ->
                            navController.navigate(ShellRoute.AgentRunDetail(runId))
                        },
                    )
                }
            }

            composable<ShellRoute.AgentInbound> {
                InboundSetupScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVoice = { navController.navigate(ShellRoute.AgentVoice) },
                )
            }

            composable<ShellRoute.AgentOutbound> {
                OutboundSetupScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVoice = { navController.navigate(ShellRoute.AgentVoice) },
                )
            }

            composable<ShellRoute.AgentVoice> {
                VoiceCloneScreen(onBack = { navController.popBackStack() })
            }

            // The run id is read from the route by RunDetailViewModel's
            // SavedStateHandle, so nothing needs to be threaded through here.
            composable<ShellRoute.AgentRunDetail> {
                RunDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * The floating glass tab bar.
 *
 * A [GlassNavigationBar], not the design system's plain `NavigationBar`: that
 * container paints an opaque fill and a full-width hairline along its top edge,
 * both of which fight a bar that is supposed to be a pill floating over the
 * wallpaper. The glass variant also owns the selection marker, so switching tabs
 * slides one refracting pill across rather than swapping two static ones.
 *
 * @param bottomInset the gesture-bar inset. The panel floats above it rather
 *   than letting the system draw over the tabs.
 * @param backdrop what the panel refracts — the atmosphere combined with the
 *   recorded screen, so the bar picks up the content scrolling under it.
 */
@Composable
private fun ShellNavigationBar(
    currentDestination: NavDestination?,
    onSelect: (ShellRoute) -> Unit,
    bottomInset: Dp,
    backdrop: Backdrop,
) {
    val tabs = ShellTab.entries
    val selectedIndex = tabs.indexOfFirst { tab ->
        currentDestination?.hierarchy?.any { destination ->
            destination.hasRoute(tab.graphRoute::class)
        } == true
    }.coerceAtLeast(0)

    // The selection marker is a single glass pill owned by the bar, not a
    // background on whichever item happens to be selected. That is what lets it
    // travel between tabs — and travel as glass, refracting the bar it slides
    // over — instead of blinking out of one slot and into another.
    GlassNavigationBar(
        selectedIndex = selectedIndex,
        itemCount = tabs.size,
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = RikkaTheme.spacing.lg,
                end = RikkaTheme.spacing.lg,
                bottom = bottomInset + RikkaTheme.spacing.sm,
            ),
        contentPadding = PaddingValues(horizontal = RikkaTheme.spacing.sm),
    ) {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(tab.graphRoute) },
                icon = tab.icon,
                label = tab.label,
                modifier = Modifier.height(ShellNavigationBarHeight),
                // Stacked icon-over-label makes each item taller than the
                // pill it floats in; inline keeps the bar short and legible.
                layout = NavigationBarItemLayout.Inline,
                iconSize = IconSize.Lg,
                // The default active color is the violet primary, which is
                // hard to read at label size over the glass.
                activeColor = RikkaTheme.colors.onBackground,
                // The bar draws the travelling pill; a per-item one would
                // double up with it.
                showIndicator = false,
            )
        }
    }
}

/** Shorter than the design system's 80dp bar: the panel adds its own padding. */
private val ShellNavigationBarHeight = 64.dp

/** The two shell sections. Recents and contacts live inside [Keypad]. */
private enum class ShellTab(
    val label: String,
    val icon: IconToken,
    val graphRoute: ShellRoute,
) {
    Keypad("Keypad", RikkaIcons.Phone, ShellRoute.Keypad),
    Agent("Agent", RikkaIcons.Mic, ShellRoute.AgentGraph),
}

/**
 * Tab-switch semantics: single instance per tab, the outgoing tab's back stack
 * is saved, and the incoming tab's is restored.
 */
private fun NavHostController.switchTab(route: ShellRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
