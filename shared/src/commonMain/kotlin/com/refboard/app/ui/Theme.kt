package com.refboard.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFFF7A90),
    onPrimary = Color(0xFF2A0710),
    primaryContainer = Color(0xFF4A1A26),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFD6C2C7),
    secondaryContainer = Color(0xFF2E2930),
    onSecondaryContainer = Color(0xFFEDE0E3),
    background = Color(0xFF111014),
    onBackground = Color(0xFFEAE3E6),
    surface = Color(0xFF111014),
    onSurface = Color(0xFFEAE3E6),
    surfaceVariant = Color(0xFF242128),
    onSurfaceVariant = Color(0xFFB9B0B5),
    surfaceContainer = Color(0xFF1A181D),
    surfaceContainerHigh = Color(0xFF221F25),
    surfaceContainerHighest = Color(0xFF2B282E),
    surfaceContainerLow = Color(0xFF161419),
    outline = Color(0xFF4A4549),
    outlineVariant = Color(0xFF332F35),
    error = Color(0xFFFFB4AB),
)

@Composable
fun RefBoardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
