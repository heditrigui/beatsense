package com.beatsense.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

object Beat {
    val Violet = Color(0xFFA77BFF)
    val Pink = Color(0xFFFF5C8A)
    val Cyan = Color(0xFF4FD8EB)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB547)
    val Bg = Color(0xFF0C0A12)
    val Card = Color(0xFF16131F)
    val Card2 = Color(0xFF211C2E)
    val Muted = Color(0xFF9A93AD)
    val gradient = Brush.linearGradient(listOf(Violet, Pink))
}

private val scheme = darkColorScheme(
    primary = Beat.Violet,
    onPrimary = Color.White,
    secondary = Beat.Pink,
    background = Beat.Bg,
    onBackground = Color(0xFFEDE9F5),
    surface = Beat.Card,
    onSurface = Color(0xFFEDE9F5),
    surfaceVariant = Beat.Card2,
    onSurfaceVariant = Beat.Muted,
    surfaceContainer = Beat.Card,
    surfaceContainerLow = Beat.Card,
    surfaceContainerHigh = Beat.Card2,
    surfaceContainerHighest = Beat.Card2,
    outline = Color(0xFF3A3348),
    outlineVariant = Color(0xFF2A2436),
)

private val base = Typography()
private val type = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun BeatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
