package com.m15.gptlive.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
)

@Composable
fun GptLiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
