package dev.triplex.ui.shell

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import dev.triplex.domain.call.CallPhase
import dev.triplex.ui.agent.AgentDemoScreen
import dev.triplex.ui.agent.AgentHomeScreen
import dev.triplex.ui.agent.CallForwardScreen
import dev.triplex.ui.agent.InboundSetupScreen
import dev.triplex.ui.agent.OutboundSetupScreen
import dev.triplex.ui.agent.RunDetailScreen
import dev.triplex.ui.agent.VoiceCloneScreen
import dev.triplex.ui.agent.VoiceLabScreen
import dev.triplex.ui.call.incoming.IncomingCallSurfaceHost
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.enrollment.EnrollmentScreen
import dev.triplex.ui.theme.LocalTriplexWidthClass
import dev.triplex.ui.theme.TriplexLayout
import dev.triplex.ui.theme.rememberTriplexWidthClass
import dev.triplex.ui.theme.useNavigationRail
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import zed.rainxch.rikkaicons.core.IconToken
import zed.rainxch.rikkaicons.tokens.Mic
import zed.rainxch.rikkaicons.tokens.Phone
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.glass.GlassChip
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassPanel
import zed.rainxch.rikkaui.components.ui.glass.LocalGlassBackdrop
import zed.rainxch.rikkaui.components.ui.glass.glassBackdropSource
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassBackdrop
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassBackdrops
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.navigationbar.GlassNavigationBar
import zed.rainxch.rikkaui.components.ui.navigationbar.NavigationBarItem
import zed.rainxch.rikkaui.components.ui.navigationbar.NavigationBarItemLayout
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.scaffold.ScaffoldWindowInsets
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme

/**
 * The single application shell.
 *
 * Hosts the enrollment gate, the two-tab navigation (Keypad | Agent), and the
 * type-safe nav graph declared in [ShellRoute]. It is rendered by
 * `DialerActivity`, which keeps every piece of platform identity (launcher,
 * `DIAL`/`tel:`, `singleTask`, `showWhenLocked`, full-screen-intent target).
 *
 * Presentation order, highest priority first:
 * 1. **Incoming sheet.** A ringing or screened session is drawn as an overlay
 *    above the nav host, with the navigation intact and untouched. It is not a
 *    destination, so it cannot be popped, a tab switch cannot leave it, and
 *    after process death it returns with the session it is derived from.
 * 2. **Call surface.** Once a call is past those phases the keypad destination
 *    renders full-screen with no navigation chrome, exactly as the dialer did
 *    before the consolidation, and the shell pulls navigation back to the
 *    keypad tab.
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
    // overlay: the sheet floats over whatever tab the user is on, the navigation
    // stays, and it is not hijacked — so answering a call does not cost the user
    // their place in the app. A call already in progress owns the whole surface,
    // as it did before.
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
    // Tab identity is owned here, not by a NavHost. Switching Keypad ↔ Agent used
    // to dispose and cold-recompose the destination (glass dialpad / agent cards),
    // which is what felt like lag even with Enter/Exit.None. Both roots stay
    // composed; only which one is measured and placed flips.
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(KeypadTabIndex) }
    val onSelectTab: (ShellRoute) -> Unit = remember {
        { route ->
            val index = ShellTab.entries.indexOfFirst { it.graphRoute == route }
            if (index >= 0) selectedTabIndex = index
        }
    }

    // Nested Agent pushes still need a NavController; the tab switch does not.
    val agentNavController = rememberNavController()
    val agentEntry = agentNavController.currentBackStackEntryAsState()
    val agentDestination =
        if (selectedTabIndex != KeypadTabIndex) agentEntry.value?.destination else null

    val shellViewModel = hiltViewModel<ShellViewModel>()
    LaunchedEffect(Unit) {
        shellViewModel.debugNavigationRequests.collect { route ->
            when (route) {
                dev.triplex.debug.DebugNavigationBus.ROUTE_AGENT_VOICE -> {
                    selectedTabIndex = AgentTabIndex
                    agentNavController.navigate(ShellRoute.AgentVoice) {
                        launchSingleTop = true
                    }
                }
                dev.triplex.debug.DebugNavigationBus.ROUTE_AGENT_HOME -> {
                    selectedTabIndex = AgentTabIndex
                    agentNavController.popBackStack(ShellRoute.AgentHome, inclusive = false)
                }
            }
        }
    }

    // A call must not be hidden behind a tab the user happens to be on.
    LaunchedEffect(callSurfaceActive) {
        if (callSurfaceActive) selectedTabIndex = KeypadTabIndex
    }

    val widthClass = rememberTriplexWidthClass()
    val hasNavigation = !callSurfaceActive
    // The rail replaces the bar rather than joining it: the same two tabs in two
    // places at once is two answers to one question.
    val useRail = hasNavigation && widthClass.useNavigationRail
    val hasBottomBar = hasNavigation && !useRail

    val system = rememberSystemInsets()
    val content = resolveShellContentInsets(
        system = system,
        hasBottomBar = hasBottomBar,
        railWidth = if (useRail) TriplexLayout.navigationRailWidth else 0.dp,
    )

    val backProgress = rememberShellBackPolicy(
        currentDestination = agentDestination,
        selectedTabIndex = selectedTabIndex,
        onSelectTab = onSelectTab,
    )

    // One backdrop for the whole shell. It has to wrap the scaffold rather than
    // sit inside a screen, because the floating navigation is chrome *outside*
    // the nav host and glass with no backdrop in scope degrades to a flat tint.
    TriplexBackground {
        // The atmosphere alone is a smooth gradient, and a lens over a smooth
        // gradient returns a smooth gradient — glass over it reads as flat tint.
        // Recording the nav host as a second source gives the floating chrome the
        // screen's own edges and text to refract. Both bar and rail are drawn
        // after the host, never inside it, so they cannot sample themselves.
        val screenBackdrop = rememberGlassBackdrop()
        val scenery = rememberGlassBackdrops(LocalGlassBackdrop.current, screenBackdrop)

        CompositionLocalProvider(LocalTriplexWidthClass provides widthClass) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    // The gradient above is the background; an opaque scaffold
                    // would paint over it.
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
                                selectedIndex = selectedTabIndex,
                                onSelect = onSelectTab,
                                bottomInset = system.bottom,
                                backdrop = scenery,
                            )
                        }
                    },
                ) { _ ->
                    // Rikka's Scaffold *places* content inside the reserved area
                    // rather than merely reporting it, so the PaddingValues it
                    // hands back are informational. Re-applying them here would
                    // pad twice.
                    Box(modifier = Modifier.fillMaxSize()) {
                        ShellNavHost(
                            agentNavController = agentNavController,
                            enrolled = enrolled,
                            onEnrolled = onEnrolled,
                            keypadContent = keypadContent,
                            selectedTabIndex = selectedTabIndex,
                            onSelectTab = onSelectTab,
                            modifier = Modifier
                                .glassBackdropSource(screenBackdrop)
                                // Read inside the lambda, so a back gesture
                                // invalidates draw and nothing above it.
                                .graphicsLayer {
                                    val progress = backProgress()
                                    if (progress > 0f) {
                                        translationX = BackSlideDistance.toPx() * progress
                                        scaleX = 1f - BackScaleDrop * progress
                                        scaleY = 1f - BackScaleDrop * progress
                                        alpha = 1f - BackAlphaDrop * progress
                                    }
                                },
                        )

                        // The sheet is hosted here rather than in the nav graph
                        // on purpose: it is derived from call state, so it is not
                        // on the back stack, it cannot be popped, and it survives
                        // a tab switch and process death.
                        if (incomingCallActive) {
                            IncomingCallSurfaceHost()
                        }
                    }
                }

                // Outside the scaffold, and last: the rail is glass, and glass
                // has to be painted after the content it refracts. The scaffold
                // reserves its column through the content insets above.
                if (useRail) {
                    ShellNavigationRail(
                        selectedIndex = selectedTabIndex,
                        onSelect = onSelectTab,
                        backdrop = scenery,
                        modifier = Modifier
                            .align(AbsoluteAlignment.CenterLeft)
                            .padding(start = system.left),
                    )
                }
            }
        }
    }
}

/**
 * The shell's answer to the system back gesture.
 *
 * **Back rarely leaves Triplex.** The activity holds the dialer role, so being
 * dropped out of it is expensive — a user who backs out of the agent tab wants
 * the keypad, not their home screen — and it never calls `finish()`: the task is
 * moved to the back so a returning user finds the app where they left it, with
 * any live call still attached.
 *
 * Priority, and the reason for each:
 * 1. **A nested Agent destination pops.** Not handled here at all: the handler
 *    switches itself off, and Navigation Compose takes the gesture instead. That
 *    is what drives the pop transitions *interactively* — the destination
 *    underneath is revealed under the user's thumb rather than after they let
 *    go. Swallowing the gesture here would trade that for a jump cut.
 * 2. **Anywhere else in the Agent tab** — its home, or the enrollment gate —
 *    switches to the keypad.
 * 3. **The keypad itself** backgrounds the task.
 *
 * Screens with their own raised surfaces (the keypad's directory panels, its SIM
 * picker) register their own `BackHandler`s deeper in the composition, which the
 * dispatcher gives priority over this one.
 *
 * @return the in-flight gesture progress, as a lambda to be read in the draw
 *   phase. A back gesture must not recompose the nav host it is animating.
 */
@Composable
private fun rememberShellBackPolicy(
    currentDestination: NavDestination?,
    selectedTabIndex: Int,
    onSelectTab: (ShellRoute) -> Unit,
): () -> Float {
    val activity = LocalContext.current.findComponentActivity()
    var progress by remember { mutableFloatStateOf(0f) }

    val nestedInAgent = remember(currentDestination) {
        currentDestination != null && NestedAgentRoutes.any { currentDestination.hasRoute(it) }
    }

    PredictiveBackHandler(enabled = !nestedInAgent) { events ->
        try {
            events.collect { event -> progress = event.progress }
            when {
                selectedTabIndex != KeypadTabIndex -> onSelectTab(ShellRoute.Keypad)
                else -> activity?.moveTaskToBack(true)
            }
        } catch (_: CancellationException) {
            // The user changed their mind mid-swipe; the finally block unwinds
            // the visual and nothing else happened.
        } finally {
            progress = 0f
        }
    }

    // Remembered so the draw-phase reader below is a stable reference; the value
    // it returns is not, which is the whole point.
    return remember { { progress } }
}

/** How far the content slides out from under a completed back gesture. */
private val BackSlideDistance: Dp = 48.dp

/** How far it shrinks, and fades, at full progress. Deliberately barely there. */
private const val BackScaleDrop = 0.02f
private const val BackAlphaDrop = 0.08f

private const val KeypadTabIndex = 0
private const val AgentTabIndex = 1

/**
 * The Agent destinations that are pushes rather than tabs.
 *
 * These are the ones back should pop, which is to say the ones the shell's own
 * handler must stay out of the way of.
 */
private val NestedAgentRoutes: List<KClass<out ShellRoute>> = listOf(
    ShellRoute.AgentInbound::class,
    ShellRoute.AgentOutbound::class,
    ShellRoute.AgentVoice::class,
    ShellRoute.AgentVoiceLab::class,
    ShellRoute.AgentCallForward::class,
    ShellRoute.AgentDemo::class,
    ShellRoute.AgentRunDetail::class,
)

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
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

/**
 * Keeps a tab root composed while it is off-screen.
 *
 * Leaving composition tears down glass surfaces; coming back rebuilds them, and
 * that rebuild is what read as content "drawing in" on every Keypad ↔ Agent
 * switch. The inactive tab stays measured and placed (layout stays warm) but
 * skips draw and hit-testing, so glass shaders are not paid for twice.
 */
@Composable
private fun KeepAliveTab(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .semantics {
                if (!visible) hideFromAccessibility()
            }
            .drawWithContent {
                if (visible) drawContent()
            },
    ) {
        content()
    }
}

@Composable
private fun ShellNavHost(
    agentNavController: NavHostController,
    enrolled: Boolean,
    onEnrolled: () -> Unit,
    keypadContent: @Composable (onOpenAgent: () -> Unit) -> Unit,
    selectedTabIndex: Int,
    onSelectTab: (ShellRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Agent is composed after the first frame on Keypad (or on first visit), then
    // kept. That way the first Agent tap does not also pay for first composition —
    // only for the first draw of already-laid-out content.
    var agentVisited by rememberSaveable { mutableStateOf(false) }
    if (selectedTabIndex != KeypadTabIndex) agentVisited = true
    LaunchedEffect(Unit) {
        // Let Keypad paint once, then warm Agent off the critical path.
        yield()
        delay(TabPrefetchDelayMs)
        agentVisited = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        KeepAliveTab(visible = selectedTabIndex == KeypadTabIndex) {
            keypadContent { onSelectTab(ShellRoute.AgentGraph) }
        }
        if (agentVisited) {
            KeepAliveTab(visible = selectedTabIndex != KeypadTabIndex) {
                AgentNavHost(
                    navController = agentNavController,
                    enrolled = enrolled,
                    onEnrolled = onEnrolled,
                )
            }
        }
    }
}

/** Pause after first composition before warming the Agent tab off-screen. */
private const val TabPrefetchDelayMs = 320L

@Composable
private fun AgentNavHost(
    navController: NavHostController,
    enrolled: Boolean,
    onEnrolled: () -> Unit,
) {
    val motion = RikkaTheme.motion

    // Nested Agent pushes get the full push/pop pair: Navigation Compose drives
    // those *interactively* under a back gesture — the pop transitions are the
    // gesture, seeked frame by frame, rather than an animation played once it
    // is released. Tab switches themselves are not in this host.
    val nestedEnter = {
        fadeIn(tween(motion.durationSlow)) +
            slideInHorizontally(tween(motion.durationSlow)) { width -> width / 5 } +
            scaleIn(tween(motion.durationSlow), initialScale = NestedEnterScale)
    }
    val nestedExit = {
        fadeOut(tween(motion.durationDefault)) +
            slideOutHorizontally(tween(motion.durationSlow)) { width -> -width / 12 }
    }
    val nestedPopEnter = {
        fadeIn(tween(motion.durationSlow)) +
            scaleIn(
                animationSpec = tween(motion.durationSlow),
                initialScale = TriplexLayout.navigationScale,
            ) +
            slideInHorizontally(tween(motion.durationSlow)) { width -> -width / 10 }
    }
    val nestedPopExit = {
        fadeOut(tween(motion.durationSlow)) +
            scaleOut(
                animationSpec = tween(motion.durationSlow),
                targetScale = TriplexLayout.navigationScale,
                transformOrigin = TransformOrigin.Center,
            ) +
            slideOutHorizontally(tween(motion.durationSlow)) { width -> width / 5 }
    }

    NavHost(
        navController = navController,
        startDestination = ShellRoute.AgentHome,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
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
                        onOpenVoiceLab = { navController.navigate(ShellRoute.AgentVoiceLab) },
                        onOpenCallForward = { navController.navigate(ShellRoute.AgentCallForward) },
                        onOpenDemo = { navController.navigate(ShellRoute.AgentDemo) },
                        onOpenRun = { runId ->
                            navController.navigate(ShellRoute.AgentRunDetail(runId))
                        },
                    )
            }
        }

        composable<ShellRoute.AgentInbound>(
            enterTransition = { nestedEnter() },
            exitTransition = { nestedExit() },
            popEnterTransition = { nestedPopEnter() },
            popExitTransition = { nestedPopExit() },
        ) {
            InboundSetupScreen(
                onBack = { navController.popBackStack() },
                onOpenVoice = { navController.navigate(ShellRoute.AgentVoice) },
            )
        }

        composable<ShellRoute.AgentOutbound>(
            enterTransition = { nestedEnter() },
            exitTransition = { nestedExit() },
            popEnterTransition = { nestedPopEnter() },
            popExitTransition = { nestedPopExit() },
        ) {
            OutboundSetupScreen(
                onBack = { navController.popBackStack() },
                onOpenVoice = { navController.navigate(ShellRoute.AgentVoice) },
            )
        }

            composable<ShellRoute.AgentVoice>(
                enterTransition = { nestedEnter() },
                exitTransition = { nestedExit() },
                popEnterTransition = { nestedPopEnter() },
                popExitTransition = { nestedPopExit() },
            ) {
                VoiceCloneScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVoiceLab = { navController.navigate(ShellRoute.AgentVoiceLab) },
                )
            }

            composable<ShellRoute.AgentVoiceLab>(
                enterTransition = { nestedEnter() },
                exitTransition = { nestedExit() },
                popEnterTransition = { nestedPopEnter() },
                popExitTransition = { nestedPopExit() },
            ) {
                VoiceLabScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCallForward = { navController.navigate(ShellRoute.AgentCallForward) },
                )
            }

            composable<ShellRoute.AgentCallForward>(
                enterTransition = { nestedEnter() },
                exitTransition = { nestedExit() },
                popEnterTransition = { nestedPopEnter() },
                popExitTransition = { nestedPopExit() },
            ) {
                CallForwardScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEnrollmentHint = {
                        // Account/line setup still lives behind the Agent gate.
                        navController.popBackStack(ShellRoute.AgentHome, inclusive = false)
                    },
                )
            }

            composable<ShellRoute.AgentDemo>(
                enterTransition = { nestedEnter() },
                exitTransition = { nestedExit() },
                popEnterTransition = { nestedPopEnter() },
                popExitTransition = { nestedPopExit() },
            ) {
                AgentDemoScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVoice = { navController.navigate(ShellRoute.AgentVoice) },
                    onOpenVoiceLab = { navController.navigate(ShellRoute.AgentVoiceLab) },
                    onOpenCallForward = { navController.navigate(ShellRoute.AgentCallForward) },
                )
            }

            // The run id is read from the route by RunDetailViewModel's
            // SavedStateHandle, so nothing needs to be threaded through here.
            composable<ShellRoute.AgentRunDetail>(
                enterTransition = { nestedEnter() },
                exitTransition = { nestedExit() },
                popEnterTransition = { nestedPopEnter() },
                popExitTransition = { nestedPopExit() },
            ) {
                RunDetailScreen(onBack = { navController.popBackStack() })
            }
    }
}

/** Where a pushed destination starts from. Close enough to 1 to read as depth, not as zoom. */
private const val NestedEnterScale = 0.96f

/**
 * The floating glass tab bar.
 *
 * A [GlassNavigationBar], not the design system's plain `NavigationBar`: that
 * container paints an opaque fill and a full-width hairline along its top edge,
 * both of which fight a bar that is supposed to be a pill floating over the
 * wallpaper. The glass variant also owns the selection marker, so switching tabs
 * slides one refracting pill across rather than swapping two static ones.
 *
 * @param selectedIndex which tab is lit, as an index into [ShellTab.entries].
 *   An index rather than a destination: the bar has no business walking a back
 *   stack, and passing one made it recompose on every nested push.
 * @param bottomInset the gesture-bar inset. The panel floats above it rather
 *   than letting the system draw over the tabs.
 * @param backdrop what the panel refracts — the atmosphere combined with the
 *   recorded screen, so the bar picks up the content scrolling under it.
 */
@Composable
private fun ShellNavigationBar(
    selectedIndex: Int,
    onSelect: (ShellRoute) -> Unit,
    bottomInset: Dp,
    backdrop: Backdrop,
) {
    val tabs = ShellTab.entries

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
        // Two tabs split whatever width the window has, so on anything wider
        // than a phone the pill would grow into a half-screen slab behind a
        // five-letter label.
        maxIndicatorWidth = TriplexLayout.navIndicatorMaxWidth,
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

/**
 * The same two tabs, stood up along the start edge.
 *
 * Once the window is wider than a phone, a bar pinned to the bottom is both a
 * long way from the hand holding the hinge and a horizontal band across content
 * that is now laid out in columns. The rail keeps navigation at a thumb's reach
 * on an unfolded device and gives the bottom of the screen back to the dialler.
 *
 * No travelling pill here. The bar's marker earns its complexity by crossing the
 * screen between two adjacent slots; stacked vertically the tabs are far enough
 * apart that a pill sliding down the rail reads as a lift, not as a selection.
 * Each item carries its own glass chip instead.
 *
 * Placed against the physical left edge rather than the start one, because the
 * column it occupies is reserved by [ShellInsets.left]; the two have to name the
 * same side or the content is inset away from a rail that is not there.
 */
@Composable
private fun ShellNavigationRail(
    selectedIndex: Int,
    onSelect: (ShellRoute) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val spacing = RikkaTheme.spacing

    GlassPanel(
        modifier = modifier
            .width(TriplexLayout.navigationRailWidth)
            .padding(horizontal = spacing.sm),
        backdrop = backdrop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShellTab.entries.forEachIndexed { index, tab ->
                ShellRailItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    onClick = { onSelect(tab.graphRoute) },
                )
            }
        }
    }
}

@Composable
private fun ShellRailItem(tab: ShellTab, selected: Boolean, onClick: () -> Unit) {
    val colors = RikkaTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassChip(
            selected = selected,
            onClick = onClick,
            // Nested one level inside the rail panel, which steps every level
            // down a rung — so Regular here is what lands on Subtle at the chip.
            level = GlassLevel.Regular,
            contentPadding = PaddingValues(
                horizontal = RikkaTheme.spacing.lg,
                vertical = RikkaTheme.spacing.sm,
            ),
            label = tab.label,
        ) {
            Icon(imageVector = tab.icon, contentDescription = null, size = IconSize.Lg)
        }
        Text(
            text = tab.label,
            // The chip above is the control and already carries this word as its
            // description; announcing it twice is noise.
            modifier = Modifier.clearAndSetSemantics {},
            variant = TextVariant.Small,
            color = if (selected) colors.onBackground else colors.onMuted,
        )
    }
}

/** Shorter than the design system's 80dp bar: the panel adds its own padding. */
private val ShellNavigationBarHeight = 64.dp

/**
 * The two shell sections. Recents and contacts live inside [Keypad].
 *
 * [graphRoute] is the tab identity passed to [onSelectTab]; Agent nested
 * destinations live on a separate NavHost and are not listed here.
 */
private enum class ShellTab(
    val label: String,
    val icon: IconToken,
    val graphRoute: ShellRoute,
) {
    Keypad("Keypad", RikkaIcons.Phone, ShellRoute.Keypad),
    Agent("Agent", RikkaIcons.Mic, ShellRoute.AgentGraph),
}
