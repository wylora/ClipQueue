package com.clipqueue.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.clipqueue.app.data.PostStatus
import com.clipqueue.app.data.ThemeMode

// «Монтажная»: глубокий чернильно-фиолетовый, бирюза — «в работе», коралл — «нужно ваше действие».
private val Ink = Color(0xFF14112A)
private val InkRaised = Color(0xFF1E1A3A)
private val InkHigh = Color(0xFF2A2550)
private val Teal = Color(0xFF3DDCC8)
private val Coral = Color(0xFFFF5C8A)
private val Amber = Color(0xFFFFC857)
private val Mist = Color(0xFFE9E6F5)

private val DarkColors = darkColorScheme(
    primary = Teal, onPrimary = Color(0xFF062A26),
    primaryContainer = Color(0xFF0F4A44), onPrimaryContainer = Color(0xFFB8F5EC),
    secondary = Coral, onSecondary = Color(0xFF3D0018),
    secondaryContainer = Color(0xFF5A1530), onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Amber, onTertiary = Color(0xFF3A2A00),
    background = Ink, onBackground = Mist,
    surface = Ink, onSurface = Mist,
    surfaceVariant = InkHigh, onSurfaceVariant = Color(0xFFB9B3D6),
    surfaceContainerLowest = Color(0xFF100D22),
    surfaceContainerLow = Color(0xFF1A1633),
    surfaceContainer = InkRaised,
    surfaceContainerHigh = Color(0xFF252046),
    surfaceContainerHighest = InkHigh,
    outline = Color(0xFF5B5480), outlineVariant = Color(0xFF3A3463),
    error = Color(0xFFFF6B6B), onError = Color(0xFF410002)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7F73), onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0E7), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFFC92A60), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2), onSecondaryContainer = Color(0xFF3E001D),
    tertiary = Color(0xFF946200), onTertiary = Color.White,
    background = Color(0xFFF6F4FB), onBackground = Color(0xFF1B1734),
    surface = Color(0xFFF6F4FB), onSurface = Color(0xFF1B1734),
    surfaceVariant = Color(0xFFE6E1F2), onSurfaceVariant = Color(0xFF4A4466),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1EEF8),
    surfaceContainer = Color(0xFFECE8F5),
    surfaceContainerHigh = Color(0xFFE6E2F1),
    surfaceContainerHighest = Color(0xFFE0DBEE),
    outline = Color(0xFF7A7398), outlineVariant = Color(0xFFCBC4DE)
)

private val Display = FontFamily.SansSerif
val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Display, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Display, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Display, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

@Immutable
data class StatusPalette(val fg: Color, val bg: Color)

val LocalDark = staticCompositionLocalOf { true }

@Composable
fun statusColors(s: PostStatus): StatusPalette {
    val cs = MaterialTheme.colorScheme
    return when (s) {
        PostStatus.DRAFT -> StatusPalette(cs.onSurfaceVariant, cs.surfaceVariant)
        PostStatus.PENDING -> StatusPalette(cs.onPrimaryContainer, cs.primaryContainer)
        PostStatus.READY -> StatusPalette(cs.onPrimary, cs.primary)
        PostStatus.NEEDS_CONFIRMATION -> StatusPalette(cs.onSecondary, cs.secondary)
        PostStatus.PUBLISHING -> StatusPalette(cs.onTertiary, cs.tertiary)
        PostStatus.PUBLISHED -> StatusPalette(cs.primary, cs.primary.copy(alpha = 0.14f))
        PostStatus.ERROR -> StatusPalette(cs.onError, cs.error)
        PostStatus.CANCELLED -> StatusPalette(cs.onSurfaceVariant, cs.outlineVariant)
    }
}

@Composable
fun ClipQueueTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp)
        )
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDark provides dark, content = content)
    }
}
