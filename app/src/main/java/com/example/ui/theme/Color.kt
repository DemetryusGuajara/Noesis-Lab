package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Editorial Light Palette
val AnthropicCreamBg = Color(0xFFFAF9F5)
val AnthropicParchmentSurface = Color(0xFFF3F1EA)
val AnthropicWarmCard = Color(0xFFEBE8DE)
val AnthropicCharcoalText = Color(0xFF141413)
val AnthropicStoneMuted = Color(0xFF6B6961)
val AnthropicTerracottaPrimary = Color(0xFFD97757)
val AnthropicTerracottaDark = Color(0xFFB8593B)
val AnthropicSageSecondary = Color(0xFF5D7052)
val AnthropicGoldTertiary = Color(0xFFB38F40)
val AnthropicBorderLight = Color(0xFFDFDCD1)

// Editorial Dark Palette
val AnthropicObsidianBg = Color(0xFF141413)
val AnthropicSlateSurface = Color(0xFF1E1E1C)
val AnthropicElevatedCard = Color(0xFF282724)
val AnthropicIvoryText = Color(0xFFF2F0E8)
val AnthropicAshMuted = Color(0xFFA3A096)
val AnthropicTerracottaBright = Color(0xFFE08364)
val AnthropicSageBright = Color(0xFF8AA37B)
val AnthropicGoldBright = Color(0xFFD4AF59)
val AnthropicBorderDark = Color(0xFF33322E)

/**
 * Computes the synesthetic color of a sphere directly from its 2 sliders (Item 6):
 * - Slider 1 (importanceMass: 0.4f..2.5f) defines the HUE:
 *   Least important (0.4f) = Cold Blue (225°)
 *   Medium importance = Cyan (180°) -> Green (130°) -> Warm Gold/Yellow (55°) -> Orange (28°)
 *   Most important (2.5f) = Warm Red (6°)
 * - Slider 2 (heavinessWeight: 0.0f..1.0f, where 0 = Light sensation, 1 = Heavy sensation) defines SATURATION:
 *   Kept within tasteful pastel-to-rich bounds (0.36f .. 0.78f) so it never turns dull gray
 *   and never becomes neon/garish.
 */
fun computeSynestheticSphereColor(
    importanceMass: Float,
    heavinessWeight: Float,
    isDarkTheme: Boolean = true
): Color {
    val normImp = ((importanceMass - 0.4f) / 2.1f).coerceIn(0f, 1f)
    val normWeight = heavinessWeight.coerceIn(0f, 1f)

    // Hue from 225° (Cold Blue) down to 6° (Warm Coral-Red) passing through Cyan, Green, Yellow, Orange
    val hue = (225f * (1f - normImp) + 6f * normImp).coerceIn(0f, 360f)

    // Saturation from 0.36f (soft airy pastel when Light) to 0.78f (rich expressive pastel when Heavy)
    val saturation = (0.36f + normWeight * 0.42f).coerceIn(0.36f, 0.78f)

    // Value/Brightness tuned so pastel hues remain luminous and harmonious
    val value = if (isDarkTheme) {
        (0.92f - normWeight * 0.10f).coerceIn(0.78f, 0.94f)
    } else {
        (0.86f - normWeight * 0.12f).coerceIn(0.70f, 0.88f)
    }

    return Color.hsv(hue, saturation, value)
}

/**
 * Formats any concept or emotion title with Title Case (First letter capitalized on words)
 * to enforce a clean standard across the canvas and texts (Item 12).
 */
fun formatConceptTitleCase(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.split(Regex("\\s+")).joinToString(" ") { word ->
        if (word.isEmpty()) ""
        else word.lowercase().replaceFirstChar { ch -> ch.titlecase() }
    }
}
