package com.systemutility.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.systemutility.core.model.ChatAppearanceConfig
import com.systemutility.core.model.ThemeMode

val LocalChatAppearance = staticCompositionLocalOf { ChatAppearanceConfig() }

val SurfaceSlateDark = Color(0xFF0B1118)
val CardSlateDark = Color(0xFF131C26)
val BorderSlateDark = Color(0xFF22303F)
val TextPrimaryDark = Color(0xFFE6EDF3)
val TextSecondaryDark = Color(0xFF8B98A5)

val SurfaceLight = Color(0xFFF7F9FB)
val CardLight = Color(0xFFFFFFFF)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF64748B)

val StatusGreen = Color(0xFF10B981)
val StatusAmber = Color(0xFFF59E0B)
val StatusRed = Color(0xFFEF4444)

@Composable
fun SystemUtilityTheme(
    appearance: ChatAppearanceConfig = ChatAppearanceConfig(),
    content: @Composable () -> Unit
) {
    val useDark = when (appearance.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val accent = Color(appearance.accentHex)

    val colorScheme = if (useDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = Color(0xFF38BDF8),
            background = SurfaceSlateDark,
            surface = CardSlateDark,
            surfaceVariant = Color(0xFF1A2532),
            onBackground = TextPrimaryDark,
            onSurface = TextPrimaryDark,
            onSurfaceVariant = TextSecondaryDark,
            error = StatusRed,
            outline = BorderSlateDark
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = Color(0xFF0284C7),
            background = SurfaceLight,
            surface = CardLight,
            surfaceVariant = Color(0xFFEEF2F6),
            onBackground = TextPrimaryLight,
            onSurface = TextPrimaryLight,
            onSurfaceVariant = TextSecondaryLight,
            error = StatusRed,
            outline = Color(0xFFDCE3EB)
        )
    }

    val scale = appearance.fontScale.coerceIn(0.85f, 1.35f)
    val typography = Typography(
        headlineMedium = TextStyle(fontSize = (24 * scale).sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = (20 * scale).sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = (16 * scale).sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = (15.5f * scale).sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = (14 * scale).sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = (12 * scale).sp, fontWeight = FontWeight.Normal),
        labelMedium = TextStyle(fontSize = (12 * scale).sp, fontWeight = FontWeight.Medium)
    )

    CompositionLocalProvider(LocalChatAppearance provides appearance) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
