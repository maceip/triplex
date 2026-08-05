package dev.triplex.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val TriplexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun TriplexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand colors are the default. Dynamic colors remain available as an
    // explicit product choice rather than changing Triplex per wallpaper.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> TriplexDarkColorScheme
        else -> TriplexLightColorScheme
    }
    val extendedColors = if (darkTheme) {
        TriplexDarkExtendedColors
    } else {
        TriplexLightExtendedColors
    }

    CompositionLocalProvider(
        LocalTriplexExtendedColors provides extendedColors,
        LocalTriplexSpacing provides TriplexSpacing(),
        LocalTriplexElevation provides TriplexElevation(),
        LocalTriplexMotion provides TriplexMotion()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TriplexTypography,
            shapes = TriplexShapes,
            content = content
        )
    }
}
