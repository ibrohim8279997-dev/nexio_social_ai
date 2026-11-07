package com.nexiolab.socialai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Brand colours from the design system; the same in every theme. */
object Brand {
    val Primary = Color(0xFF1677FF)
    val Accent = Color(0xFF29B6FF)
    val Violet = Color(0xFF8B5CF6)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF5A524)
    val Danger = Color(0xFFEF4444)
}

/** Surface and text tokens that change with the theme. */
@Immutable
data class NexioColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val cardHigh: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
)

private val Dark = NexioColors(
    background = Color(0xFF071426),
    surface = Color(0xFF0B1B31),
    card = Color(0xFF102746),
    cardHigh = Color(0xFF16325A),
    outline = Color(0xFF1E3B63),
    text = Color(0xFFFFFFFF),
    textMuted = Color(0xFF91A4BC),
)

private val Light = NexioColors(
    background = Color(0xFFF3F6FB),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFE8EFF9),
    outline = Color(0xFFD5DFEC),
    text = Color(0xFF0B1B31),
    textMuted = Color(0xFF5A6B82),
)

val LocalNexioColors = staticCompositionLocalOf { Dark }

object Nexio {
    val colors: NexioColors
        @Composable get() = LocalNexioColors.current
}

enum class ThemeMode { DARK, LIGHT, SYSTEM }

private val NexioTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NexioTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = Brand.Primary, onPrimary = Color.White, secondary = Brand.Accent, onSecondary = Color.White,
            tertiary = Brand.Violet, background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.card,
            onSurfaceVariant = colors.textMuted, surfaceContainer = colors.card, surfaceContainerHigh = colors.cardHigh,
            surfaceContainerHighest = colors.cardHigh, surfaceContainerLow = colors.surface,
            outline = colors.outline, outlineVariant = colors.outline, error = Brand.Danger,
        )
    } else {
        lightColorScheme(
            primary = Brand.Primary, onPrimary = Color.White, secondary = Brand.Accent, onSecondary = Color.White,
            tertiary = Brand.Violet, background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.cardHigh,
            onSurfaceVariant = colors.textMuted, surfaceContainer = colors.card, surfaceContainerHigh = colors.cardHigh,
            surfaceContainerHighest = colors.cardHigh, surfaceContainerLow = colors.surface,
            outline = colors.outline, outlineVariant = colors.outline, error = Brand.Danger,
        )
    }
    CompositionLocalProvider(LocalNexioColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = NexioTypography, content = content)
    }
}
