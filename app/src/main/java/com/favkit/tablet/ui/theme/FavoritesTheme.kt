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

/**
 * 影音类应用的配色：底色偏冷、卡片与背景有明确明度差，
 * 封面图（网络图片，颜色不可控）放上去之后角标和标题仍然读得清。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF006A64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE9E5),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color.White,
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE2E7F0),
    onSurfaceVariant = Color(0xFF414855),
    outline = Color(0xFF717886),
    outlineVariant = Color(0xFFC5CCD8),
    background = Color(0xFFF6F7FA),
    onBackground = Color(0xFF1A1C1E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF06307B),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF4FD9CE),
    onSecondary = Color(0xFF003734),
    secondaryContainer = Color(0xFF00504B),
    onSecondaryContainer = Color(0xFFCFE9E5),
    tertiary = Color(0xFFF7BD48),
    onTertiary = Color(0xFF412D00),
    surface = Color(0xFF16181B),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF333A46),
    onSurfaceVariant = Color(0xFFBFC7D5),
    outline = Color(0xFF89919F),
    outlineVariant = Color(0xFF3F4756),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE3E2E6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

// 平板观看距离比手机远，正文与标签字号整体上调，避免"字太小看不清"；
// 卡片标题/来源单独给两档小字号，保证 2 列窄卡也能放完整行。
private val TabletTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
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
