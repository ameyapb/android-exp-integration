package com.ameyapb.androidexp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GeminiDarkColorScheme = darkColorScheme(
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = SurfaceRaised,
    onSurface = TextPrimary,
    primary = Accent,
    onPrimary = SurfaceBase,
)

@Composable
fun GeminiAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GeminiDarkColorScheme,
        content = content,
    )
}
