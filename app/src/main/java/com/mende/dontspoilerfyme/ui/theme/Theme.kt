package com.mende.dontspoilerfyme.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    secondary = AccentCyan,
    tertiary = AccentTeal,

    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    outline = LightOutline,

    onPrimary = Color.White,
    onSecondary = Color(0xFF00121A),
    onTertiary = Color(0xFF00140F),

    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightOnSurfaceVariant
).copy(
    // ⬇️ QUESTI GUIDANO LE CARD DI DEFAULT (Material3)
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6FF),
    surfaceContainer = Color(0xFFEFF3FF),
    surfaceContainerHigh = Color(0xFFE8EEFF),
    surfaceContainerHighest = Color(0xFFE1E9FF),
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentCyan,
    tertiary = AccentTeal,

    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline,

    onPrimary = Color.White,
    onSecondary = Color(0xFF00121A),
    onTertiary = Color(0xFF00140F),

    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkOnSurfaceVariant
).copy(
    // ⬇️ QUESTI GUIDANO LE CARD DI DEFAULT (Material3)
    surfaceContainerLowest = Color(0xFF0C1220),
    surfaceContainerLow = Color(0xFF101A2B),
    surfaceContainer = Color(0xFF121E33),
    surfaceContainerHigh = Color(0xFF162540),
    surfaceContainerHighest = Color(0xFF1A2B4A),
)


@Composable
fun DontSpoilerfyMeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
