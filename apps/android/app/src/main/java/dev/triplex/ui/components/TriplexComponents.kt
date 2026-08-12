package dev.triplex.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import zed.rainxch.rikkaicons.core.IconToken
import zed.rainxch.rikkaui.components.ui.button.Button
import zed.rainxch.rikkaui.components.ui.button.ButtonAnimation
import zed.rainxch.rikkaui.components.ui.button.ButtonSize
import zed.rainxch.rikkaui.components.ui.button.ButtonVariant
import zed.rainxch.rikkaui.components.ui.button.IconButton
import zed.rainxch.rikkaui.components.ui.button.IconButtonSize
import zed.rainxch.rikkaui.components.ui.glass.GlassCard
import zed.rainxch.rikkaui.components.ui.glass.GlassCapability
import zed.rainxch.rikkaui.components.ui.glass.GlassContainer
import zed.rainxch.rikkaui.components.ui.glass.GlassLevel
import zed.rainxch.rikkaui.components.ui.glass.GlassScenery
import zed.rainxch.rikkaui.components.ui.glass.LocalGlassLensPolicy
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassCapability
import zed.rainxch.rikkaui.components.ui.glass.rememberGlassLensPolicy
import zed.rainxch.rikkaui.components.ui.icon.Icon
import zed.rainxch.rikkaui.components.ui.icon.IconSize
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.components.ui.topappbar.TopAppBar
import zed.rainxch.rikkaui.components.ui.topappbar.TopAppBarVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme
import kotlinx.coroutines.delay

enum class TriplexButtonStyle {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    GHOST,
    DANGER
}

enum class TriplexCardTone {
    DEFAULT,
    ACCENT,
    SUCCESS,
    WARNING,
    DANGER
}

/**
 * App-wide atmospheric surface, and the backdrop every glass component refracts.
 *
 * Paints [triplexScenery] through [GlassScenery] — violet/cyan lobes at strengths
 * a 12–44dp lens can actually displace across. The old flat gradient + ~9–18%
 * auras starved blur, refraction, and dispersion.
 *
 * `TriplexShell` wraps its whole scaffold in one of these, which is why screens
 * no longer call it themselves: the nav bar and the screen underneath have to
 * refract the *same* layer, and a per-screen backdrop would leave the chrome
 * with nothing to sample.
 *
 * Idle capability is Blur (painted bevels + dome, no live AGSL lenses). Pressing
 * glass promotes Full for a short hold via [rememberGlassLensPolicy], then
 * demotes — lenses when you look, quiet when you don't. Call / demo surfaces can
 * still force sustained Full with [glassCapability].
 */
@Composable
fun TriplexBackground(
    modifier: Modifier = Modifier,
    /** Journey/demo surfaces may opt into lobe drift; shell stays static. */
    animateScenery: Boolean = false,
    /**
     * Ceiling for glass. Null (default) uses [rememberGlassCapability] under a
     * [rememberGlassLensPolicy] so idle stays Blur and presses briefly promote
     * Full. Pass an explicit tier to lock the scene (e.g. Full for cinematic).
     */
    glassCapability: GlassCapability? = null,
    content: @Composable () -> Unit
) {
    // Scenery is installed by LiquidGlassTheme via RikkaTheme(scenery = …);
    // reading it here keeps the shell and every glass surface on the same scene.
    val scenery = RikkaTheme.scenery
    val ceiling = glassCapability ?: rememberGlassCapability()
    val lensPolicy = rememberGlassLensPolicy(ceiling = ceiling)
    val effective = glassCapability ?: lensPolicy.effective

    GlassContainer(
        modifier = modifier.fillMaxSize(),
        capability = effective,
        background = {
            GlassScenery(
                scenery = scenery,
                // Default off: Full-tier glass + drifting scenery was keeping
                // the UI compositor busy (~100% CPU idle on Keypad).
                animated = animateScenery,
            )
        },
    ) {
        CompositionLocalProvider(LocalGlassLensPolicy provides lensPolicy) {
            content()
        }
    }
}

/** Standard staged screen entrance; navigation owns the matching exit. */
@Composable
fun TriplexReveal(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val motion = RikkaTheme.motion
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(motion.durationSlow)) +
            slideInVertically(tween(motion.durationSlow)) { it / 8 },
        exit = fadeOut(tween(motion.durationDefault))
    ) {
        content()
    }
}

@Composable
fun TriplexScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs)
        ) {
            eyebrow?.let {
                Text(
                    text = it.uppercase(),
                    variant = TextVariant.Small,
                    color = RikkaTheme.colors.primary
                )
            }
            Text(text = title, variant = TextVariant.H2)
            description?.let {
                Text(text = it, variant = TextVariant.Muted)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(RikkaTheme.spacing.md))
            it()
        }
    }
}

@Composable
fun TriplexButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TriplexButtonStyle = TriplexButtonStyle.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: IconToken? = null
) {
    Button(
        text = text,
        onClick = onClick,
        // Rikka's Lg size is 44dp; Triplex buttons have always been 54dp and the
        // screens are laid out around that, so the floor is kept explicitly.
        modifier = modifier.defaultMinSize(minHeight = 54.dp),
        variant = style.toButtonVariant(),
        size = ButtonSize.Lg,
        animation = ButtonAnimation.Scale,
        enabled = enabled,
        loading = loading,
        leadingIcon = leadingIcon?.let { icon ->
            { Icon(imageVector = icon, contentDescription = null, size = IconSize.Sm) }
        }
    )
}

/**
 * Maps the Triplex button vocabulary onto Rikka's.
 *
 * `internal` rather than `private` so `TriplexButtonStyleTest` can pin the
 * mapping: DANGER landing anywhere but [ButtonVariant.Destructive] would make a
 * destructive action look ordinary, and that is not something a compiler catches.
 */
internal fun TriplexButtonStyle.toButtonVariant(): ButtonVariant = when (this) {
    TriplexButtonStyle.PRIMARY -> ButtonVariant.Default
    TriplexButtonStyle.SECONDARY -> ButtonVariant.Secondary
    TriplexButtonStyle.OUTLINE -> ButtonVariant.Outline
    TriplexButtonStyle.GHOST -> ButtonVariant.Ghost
    TriplexButtonStyle.DANGER -> ButtonVariant.Destructive
}

@Composable
fun TriplexIconButton(
    imageVector: IconToken,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // Deliberately the opaque design-system IconButton rather than
    // GlassIconButton: this is the trailing action of a directory/history row,
    // so it can appear dozens of times in one scrolling list and a blur layer
    // per row is a cost nobody has profiled. Floating chrome uses glass; row
    // furniture does not.
    IconButton(
        icon = imageVector,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        variant = ButtonVariant.Secondary,
        size = IconButtonSize.Default,
        animation = ButtonAnimation.Scale,
        enabled = enabled
    )
}

/**
 * The standard content container.
 *
 * A glass card, per the implementation plan — the tone is applied as the wash
 * over the blurred backdrop rather than as an opaque fill, so a warning card
 * reads as amber-tinted glass and not as a Material container.
 *
 * Content padding is zero because every existing call site pads its own content;
 * `GlassCard` would otherwise add `spacing.lg` on top of that.
 *
 * @param level how far the card reads as sitting above what is behind it. Drop
 *   to [GlassLevel.Subtle] for cards that repeat down a list: a directory row is
 *   furniture *on* the screen, and a dozen Regular slabs stacked vertically read
 *   as a pile of floating panels rather than as a list.
 * @param hostsGlass set this when the card contains glass of its own — dial keys,
 *   glass chips. The card then records what it drew, so the nested glass refracts
 *   *the card* instead of the scene far behind it, which is the difference
 *   between keys that look like glass beads and keys that look like flat discs.
 */
@Composable
fun TriplexCard(
    modifier: Modifier = Modifier,
    tone: TriplexCardTone = TriplexCardTone.DEFAULT,
    onClick: (() -> Unit)? = null,
    level: GlassLevel = GlassLevel.Regular,
    hostsGlass: Boolean = false,
    content: @Composable () -> Unit
) {
    GlassCard(
        modifier = modifier,
        level = level,
        onClick = onClick,
        tint = tone.glassTint(),
        contentColor = RikkaTheme.colors.onSurface,
        contentPadding = PaddingValues(0.dp),
        hostsGlass = hostsGlass
    ) {
        content()
    }
}

/**
 * The wash a [TriplexCardTone] puts over the backdrop.
 *
 * Content stays `onSurface` for every tone: the tint is applied as a wash over
 * the blurred backdrop, so it colours the surface without ever becoming a
 * container that needs its own contrasting foreground.
 */
@Composable
internal fun TriplexCardTone.glassTint(): Color = when (this) {
    TriplexCardTone.DEFAULT -> RikkaTheme.glass.tint
    TriplexCardTone.ACCENT -> RikkaTheme.colors.primary
    TriplexCardTone.SUCCESS -> RikkaTheme.colors.success
    TriplexCardTone.WARNING -> RikkaTheme.colors.warning
    TriplexCardTone.DANGER -> RikkaTheme.colors.destructive
}

/**
 * A compact status label.
 *
 * Not built on the design-system `Badge`: that models four variants
 * (default/secondary/outline/destructive) and Triplex statuses need five, with
 * distinct success and warning readings. Rather than collapse two of them onto
 * a wrong colour, the pill is assembled here from palette tokens — a tinted
 * fill at 16%, a hairline at 40%, and the tone itself as the text colour.
 */
@Composable
fun TriplexStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: TriplexCardTone = TriplexCardTone.DEFAULT,
    leadingColor: Color? = null
) {
    val accent = when (tone) {
        TriplexCardTone.DEFAULT -> RikkaTheme.colors.onMuted
        TriplexCardTone.ACCENT -> RikkaTheme.colors.primary
        TriplexCardTone.SUCCESS -> RikkaTheme.colors.success
        TriplexCardTone.WARNING -> RikkaTheme.colors.warning
        TriplexCardTone.DANGER -> RikkaTheme.colors.destructive
    }
    Row(
        modifier = modifier
            .background(accent.copy(alpha = 0.16f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.40f), CircleShape)
            .padding(horizontal = RikkaTheme.spacing.md, vertical = RikkaTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(RikkaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingColor?.let {
            Box(Modifier.size(7.dp).background(it, CircleShape))
        }
        Text(
            text = text,
            variant = TextVariant.Small,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TriplexTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: IconToken? = null,
    navigationContentDescription: String = "Back",
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = {
            if (navigationIcon != null && onNavigationClick != null) {
                TriplexIconButton(
                    imageVector = navigationIcon,
                    contentDescription = navigationContentDescription,
                    onClick = onNavigationClick
                )
            }
        },
        actions = actions,
        // The shell's TriplexBackground is the point; an opaque bar would cut a
        // band out of it.
        variant = TopAppBarVariant.Transparent
    )
}

@Composable
fun TriplexTopBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        animation = ButtonAnimation.Scale
    )
}
