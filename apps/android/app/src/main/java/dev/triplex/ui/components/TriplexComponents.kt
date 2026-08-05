package dev.triplex.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.triplex.ui.theme.TriplexDesign
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

/** App-wide atmospheric surface. It uses only cheap draw operations. */
@Composable
fun TriplexBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = TriplexDesign.colors
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to colors.surfaceRaised,
                    0.52f to background,
                    1f to colors.surfaceSunken
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.auraPrimary, Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.06f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.92f, size.height * 0.06f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.auraSecondary, Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 0.78f),
                        radius = size.width * 0.62f
                    ),
                    radius = size.width * 0.62f,
                    center = Offset(size.width * 0.08f, size.height * 0.78f)
                )
            }
    ) {
        content()
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
    val motion = TriplexDesign.motion
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(motion.standardMillis)) +
            slideInVertically(tween(motion.standardMillis)) { it / 8 },
        exit = fadeOut(tween(motion.quickMillis))
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
            verticalArrangement = Arrangement.spacedBy(TriplexDesign.spacing.small)
        ) {
            eyebrow?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(TriplexDesign.spacing.medium))
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
    leadingIcon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) TriplexDesign.motion.pressedScale else 1f,
        animationSpec = spring(stiffness = 720f, dampingRatio = 0.78f),
        label = "Triplex button press"
    )
    val shapedModifier = modifier
        .scale(scale)
        .defaultMinSize(minHeight = 54.dp)
    val content: @Composable RowScope.() -> Unit = {
        TriplexButtonContent(text = text, loading = loading, leadingIcon = leadingIcon)
    }
    val canClick = enabled && !loading

    when (style) {
        TriplexButtonStyle.PRIMARY -> Button(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = canClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.medium,
            contentPadding = ButtonDefaults.ContentPadding,
            content = content
        )
        TriplexButtonStyle.SECONDARY -> Button(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = canClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            content = content
        )
        TriplexButtonStyle.OUTLINE -> OutlinedButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = canClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, TriplexDesign.colors.glassBorder),
            content = content
        )
        TriplexButtonStyle.GHOST -> TextButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = canClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.medium,
            content = content
        )
        TriplexButtonStyle.DANGER -> Button(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = canClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            content = content
        )
    }
}

@Composable
private fun RowScope.TriplexButtonContent(
    text: String,
    loading: Boolean,
    leadingIcon: ImageVector?
) {
    val motion = TriplexDesign.motion
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            fadeIn(tween(motion.quickMillis)) togetherWith
                fadeOut(tween(motion.instantMillis))
        },
        label = "Triplex button state"
    ) { busy ->
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = androidx.compose.material3.LocalContentColor.current
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leadingIcon?.let {
                    Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(TriplexDesign.spacing.small))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TriplexIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) TriplexDesign.motion.pressedScale else 1f,
        animationSpec = spring(stiffness = 760f, dampingRatio = 0.78f),
        label = "Triplex icon press"
    )
    Surface(
        modifier = modifier.scale(scale),
        shape = CircleShape,
        color = TriplexDesign.colors.surfaceRaised.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, TriplexDesign.colors.glassBorder)
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource
        ) {
            Icon(imageVector, contentDescription = contentDescription)
        }
    }
}

@Composable
fun TriplexCard(
    modifier: Modifier = Modifier,
    tone: TriplexCardTone = TriplexCardTone.DEFAULT,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.987f else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.8f),
        label = "Triplex card press"
    )
    val container = when (tone) {
        TriplexCardTone.DEFAULT -> TriplexDesign.colors.surfaceRaised
        TriplexCardTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
        TriplexCardTone.SUCCESS -> TriplexDesign.colors.successContainer
        TriplexCardTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        TriplexCardTone.DANGER -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        TriplexCardTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
        TriplexCardTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        TriplexCardTone.SUCCESS -> TriplexDesign.colors.onSuccessContainer
        TriplexCardTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        TriplexCardTone.DANGER -> MaterialTheme.colorScheme.onErrorContainer
    }
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = onClick
        )
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .then(clickableModifier),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = contentColor,
        border = BorderStroke(1.dp, TriplexDesign.colors.glassBorder.copy(alpha = 0.8f)),
        shadowElevation = TriplexDesign.elevation.resting
    ) {
        content()
    }
}

@Composable
fun TriplexStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: TriplexCardTone = TriplexCardTone.DEFAULT,
    leadingColor: Color? = null
) {
    val container = when (tone) {
        TriplexCardTone.DEFAULT -> MaterialTheme.colorScheme.surfaceVariant
        TriplexCardTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
        TriplexCardTone.SUCCESS -> TriplexDesign.colors.successContainer
        TriplexCardTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        TriplexCardTone.DANGER -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        TriplexCardTone.DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
        TriplexCardTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        TriplexCardTone.SUCCESS -> TriplexDesign.colors.onSuccessContainer
        TriplexCardTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        TriplexCardTone.DANGER -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingColor?.let {
                Box(Modifier.size(7.dp).background(it, CircleShape))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriplexTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    navigationContentDescription: String = "Back",
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (navigationIcon != null && onNavigationClick != null) {
                TriplexIconButton(
                    imageVector = navigationIcon,
                    contentDescription = navigationContentDescription,
                    onClick = onNavigationClick,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = TriplexDesign.colors.surfaceRaised.copy(alpha = 0.96f)
        )
    )
}

@Composable
fun TriplexTopBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) TriplexDesign.motion.pressedScale else 1f,
        animationSpec = spring(stiffness = 760f, dampingRatio = 0.78f),
        label = "Triplex top bar action"
    )
    TextButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TriplexSignalOrb(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "Triplex signal")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (active) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 1_450 else 2_400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Signal pulse"
    )
    Box(
        modifier = modifier
            .scale(pulse)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                ),
                shape = CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
