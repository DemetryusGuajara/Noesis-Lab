package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val AnthropicDarkColorScheme = darkColorScheme(
    primary = AnthropicTerracottaBright,
    onPrimary = Color(0xFF141413),
    primaryContainer = Color(0xFF3E2720),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = AnthropicSageBright,
    onSecondary = Color(0xFF141413),
    secondaryContainer = Color(0xFF283124),
    onSecondaryContainer = Color(0xFFD9E7D1),
    tertiary = AnthropicGoldBright,
    onTertiary = Color(0xFF141413),
    tertiaryContainer = Color(0xFF382F1B),
    onTertiaryContainer = Color(0xFFF5E2B8),
    background = AnthropicObsidianBg,
    onBackground = AnthropicIvoryText,
    surface = AnthropicSlateSurface,
    onSurface = AnthropicIvoryText,
    surfaceVariant = AnthropicElevatedCard,
    onSurfaceVariant = AnthropicAshMuted,
    outline = AnthropicBorderDark,
    outlineVariant = Color(0xFF2A2926)
)

private val AnthropicLightColorScheme = lightColorScheme(
    primary = AnthropicTerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E2DA),
    onPrimaryContainer = Color(0xFF3D190E),
    secondary = AnthropicSageSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECE0),
    onSecondaryContainer = Color(0xFF1C2617),
    tertiary = AnthropicGoldTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4EBD4),
    onTertiaryContainer = Color(0xFF332609),
    background = AnthropicCreamBg,
    onBackground = AnthropicCharcoalText,
    surface = AnthropicParchmentSurface,
    onSurface = AnthropicCharcoalText,
    surfaceVariant = AnthropicWarmCard,
    onSurfaceVariant = AnthropicStoneMuted,
    outline = AnthropicBorderLight,
    outlineVariant = Color(0xFFE5E2D7)
)

@Composable
fun NoesisTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (useDarkTheme) {
        AnthropicDarkColorScheme
    } else {
        AnthropicLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
