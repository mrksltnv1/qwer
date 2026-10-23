package com.qwer.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * One design system for the whole app: a single accent colour, Material 3 tonal
 * surfaces, and dynamic colour on Android 12+ so the app feels native on any device.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    secondary = Color(0xFF2C8C5A),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF00172E),
    secondary = Color(0xFF6FD79B),
    background = Color(0xFF101215),
    surface = Color(0xFF171A1F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun QwerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** State colours are centralised so every surface agrees on what "connected" looks like. */
object StateColors {
    val connected = Color(0xFF2C8C5A)
    val connecting = Color(0xFFB07A00)
    val degraded = Color(0xFFD97706)
    val failed = Color(0xFFB3261E)
    val idle = Color(0xFF6B7280)
}
