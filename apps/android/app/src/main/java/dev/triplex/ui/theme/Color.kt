package dev.triplex.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Triplex "Signal" palette. Violet represents the agent, cyan represents the
// live voice path, and amber is reserved for preparation or attention states.
internal val SignalViolet = Color(0xFF6558E8)
internal val SignalVioletLight = Color(0xFFCAC3FF)
internal val VoiceCyan = Color(0xFF006B75)
internal val VoiceCyanLight = Color(0xFF62D9E6)
internal val SignalAmber = Color(0xFF8B5D00)
internal val SignalAmberLight = Color(0xFFFFC56B)

internal val TriplexLightColorScheme = lightColorScheme(
    primary = SignalViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E2FF),
    onPrimaryContainer = Color(0xFF211A56),
    secondary = VoiceCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9EF0FA),
    onSecondaryContainer = Color(0xFF00363C),
    tertiary = SignalAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF2B1A00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F8FE),
    onBackground = Color(0xFF1A1B23),
    surface = Color(0xFFF8F8FE),
    onSurface = Color(0xFF1A1B23),
    surfaceVariant = Color(0xFFE5E5F0),
    onSurfaceVariant = Color(0xFF464651),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC8C5D0),
    inverseSurface = Color(0xFF2F3039),
    inverseOnSurface = Color(0xFFF1F0FA),
    inversePrimary = SignalVioletLight,
    scrim = Color.Black
)

internal val TriplexDarkColorScheme = darkColorScheme(
    primary = SignalVioletLight,
    onPrimary = Color(0xFF322978),
    primaryContainer = Color(0xFF4A4098),
    onPrimaryContainer = Color(0xFFE7E2FF),
    secondary = VoiceCyanLight,
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF004F57),
    onSecondaryContainer = Color(0xFF9EF0FA),
    tertiary = SignalAmberLight,
    onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF684400),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0D15),
    onBackground = Color(0xFFE4E1EC),
    surface = Color(0xFF0B0D15),
    onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF252632),
    onSurfaceVariant = Color(0xFFC9C5D0),
    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF464651),
    inverseSurface = Color(0xFFE4E1EC),
    inverseOnSurface = Color(0xFF2F3039),
    inversePrimary = SignalViolet,
    scrim = Color.Black
)

@Immutable
data class TriplexExtendedColors(
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val glassBorder: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val auraPrimary: Color,
    val auraSecondary: Color
)

internal val TriplexLightExtendedColors = TriplexExtendedColors(
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF0F0F8),
    glassBorder = Color(0xFFD9D6E4),
    success = Color(0xFF087A55),
    onSuccess = Color.White,
    successContainer = Color(0xFFB9F4D9),
    onSuccessContainer = Color(0xFF002116),
    warning = SignalAmber,
    onWarning = Color.White,
    auraPrimary = Color(0x1F6558E8),
    auraSecondary = Color(0x1800A8B8)
)

internal val TriplexDarkExtendedColors = TriplexExtendedColors(
    surfaceRaised = Color(0xFF151824),
    surfaceSunken = Color(0xFF090B12),
    glassBorder = Color(0xFF303341),
    success = Color(0xFF69DDAE),
    onSuccess = Color(0xFF003826),
    successContainer = Color(0xFF005139),
    onSuccessContainer = Color(0xFF8CF9CB),
    warning = SignalAmberLight,
    onWarning = Color(0xFF482900),
    auraPrimary = Color(0x2E8B80FF),
    auraSecondary = Color(0x2455D9E7)
)
