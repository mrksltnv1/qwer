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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

/**
 * One design system for the whole app.
 *
 * The palette is a deep indigo/teal pair instead of stock blue: it reads calmer on an
 * OLED phone and keeps the single "connected" green clearly distinct from the accents.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001158),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF12131A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12131A),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF5A5F6E),
    outline = Color(0xFFC7CBD8),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    onPrimary = Color(0xFF001158),
    primaryContainer = Color(0xFF1E2A6B),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF4DD0C4),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2DFDB),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE4E6EF),
    surface = Color(0xFF151821),
    onSurface = Color(0xFFE4E6EF),
    surfaceVariant = Color(0xFF1E222D),
    onSurfaceVariant = Color(0xFFA8AEC0),
    outline = Color(0xFF3A4050),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C0F0C),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * The scale is deliberately one step tighter than stock Material. The previous revision
 * raised every size to fix unreadable 11sp text, and the pendulum swung too far: an
 * 22sp screen title over a 360dp-wide screen wrapped mid-word, and "Конфигурации" at
 * 17sp dominated the list it was labelling.
 *
 * Rules applied here:
 *  - screen titles 18sp: fits "Маршрутизация" on one line at 360dp;
 *  - section headers 14sp semibold, one step below a title — a label, not a heading;
 *  - body 14sp, small print 13sp; nothing informational below 11.5sp;
 *  - lineHeight set explicitly everywhere, because the default 1.2 ratio collides on
 *    two-line Russian labels at 3x density;
 *  - sizes are in sp, so the system font-size slider scales the whole app.
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
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
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

/** Semantic colours used by every state surface, so nothing drifts. */
object StateColors {
    val connected = Color(0xFF2E9E6B)
    val connectedDim = Color(0xFF2E9E6B)
    val connecting = Color(0xFFD08A00)
    val degraded = Color(0xFFE07A1F)
    val failed = Color(0xFFC62828)
    val idle = Color(0xFF7A8296)
}
