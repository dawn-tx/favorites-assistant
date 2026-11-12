package com.favkit.tablet.ui.theme

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

// 平板观看距离比手机远，正文与标签字号整体上调，避免"字太小看不清"
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF006A64),
    onSecondary = Color.White,
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDCE3EF),
    onSurfaceVariant = Color(0xFF414855),
    outline = Color(0xFF717886),
    background = Color(0xFFF8F9FC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF06307B),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF4FD9CE),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF3F4756),
    onSurfaceVariant = Color(0xFFBFC7D5),
    background = Color(0xFF121416),
)

private val TabletTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun FavoritesTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = TabletTypography,
        content = content,
    )
}
