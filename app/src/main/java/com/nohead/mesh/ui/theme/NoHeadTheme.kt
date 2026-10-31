package com.nohead.mesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PhosphorGreen = Color(0xFF00E676)
val PhosphorGreenDim = Color(0xFF00A152)
val CyanRelay = Color(0xFF00B0FF)
val AmberNostr = Color(0xFFFFB300)
val AlertRed = Color(0xFFFF5252)

val ObsidianBg = Color(0xFF0A0E12)
val ObsidianSurface = Color(0xFF11181F)
val ObsidianSurfaceVariant = Color(0xFF1A242F)
val TerminalTextPrimary = Color(0xFFE6F1F8)
val TerminalTextSecondary = Color(0xFF90A4AE)

val PaperBg = Color(0xFFF5F7F9)
val PaperSurface = Color(0xFFFFFFFF)
val PaperSurfaceVariant = Color(0xFFE8EEF3)
val PaperTextPrimary = Color(0xFF0D141A)
val PaperTextSecondary = Color(0xFF455A64)

private val DarkTerminalScheme = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = Color(0xFF00210B),
    primaryContainer = Color(0xFF003919),
    onPrimaryContainer = Color(0xFF69F0AE),
    secondary = CyanRelay,
    onSecondary = Color(0xFF001E2F),
    secondaryContainer = Color(0xFF00344F),
    onSecondaryContainer = Color(0xFF80D8FF),
    tertiary = AmberNostr,
    onTertiary = Color(0xFF261900),
    background = ObsidianBg,
    onBackground = TerminalTextPrimary,
    surface = ObsidianSurface,
    onSurface = TerminalTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TerminalTextSecondary,
    error = AlertRed,
    onError = Color.Black
)

private val LightTerminalScheme = lightColorScheme(
    primary = Color(0xFF008640),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F6CA),
    onPrimaryContainer = Color(0xFF00210B),
    secondary = Color(0xFF006699),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F5FE),
    onSecondaryContainer = Color(0xFF001E2F),
    tertiary = Color(0xFFC68400),
    onTertiary = Color.White,
    background = PaperBg,
    onBackground = PaperTextPrimary,
    surface = PaperSurface,
    onSurface = PaperTextPrimary,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperTextSecondary,
    error = Color(0xFFD32F2F),
    onError = Color.White
)

val NoHeadTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp
    )
)

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

@Composable
fun NoHeadTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkTerminalScheme else LightTerminalScheme,
        typography = NoHeadTypography,
        content = content
    )
}
