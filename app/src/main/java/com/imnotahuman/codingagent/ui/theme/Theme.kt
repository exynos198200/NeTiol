package com.imnotahuman.codingagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AmoledBlack = Color(0xFF000000)
val Surface1 = Color(0xFF050505)
val Surface2 = Color(0xFF0B0B0B)
val Accent = Color(0xFFFF7A00)
val AccentDim = Color(0xFF7A3B00)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF8A8A8A)
val OutlineDim = Color(0xFF1C1C1C)
val Danger = Color(0xFFFF4D4D)
val Success = Color(0xFF39D353)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = AmoledBlack,
    secondary = AccentDim,
    onSecondary = TextPrimary,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = OutlineDim,
    error = Danger,
    onError = AmoledBlack
)

val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
val MonoBody = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

private val AppTypography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
