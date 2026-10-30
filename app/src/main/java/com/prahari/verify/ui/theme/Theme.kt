package com.prahari.verify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue700 = Color(0xFF1A73E8)
private val Blue500 = Color(0xFF4285F4)

private val LightColors = lightColorScheme(
    primary = Blue700,
    onPrimary = Color.White,
    secondary = Blue500,
    background = Color(0xFFF8F9FA),
    surface = Color.White
)

@Composable
fun PrahariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
