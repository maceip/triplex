package com.triplex.dialer.view

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Triplex dialer theme — professional dark UI for business owners. */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6C63FF),
    secondary = Color(0xFF00BFA5),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
)

@Composable
fun TriplexDialerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
