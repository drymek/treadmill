package com.craftersy.treadmill.workout.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.craftersy.treadmill.protocol.Speed

/** The app's colours: a deep ink background, glassy surfaces and an intensity scale from walk to sprint. */
object Ink {
    val background = Color(0xFF07080C)
    val surface = Color(0xFF11131A)
    val surfaceHigh = Color(0xFF181B24)
    val surfaceHigher = Color(0xFF20242F)
    val outline = Color(0x1FFFFFFF)
    val outlineStrong = Color(0x33FFFFFF)
    val text = Color(0xFFF4F5F8)
    val textMuted = Color(0xFF9BA1B0)
    val textFaint = Color(0xFF5E6473)
    val accent = Color(0xFFC6FF4A)
    val onAccent = Color(0xFF0B1200)
    val danger = Color(0xFFFF5C6C)
    val warning = Color(0xFFFFC857)

    /** Walk, jog, run, tempo, sprint. */
    private val scale = listOf(
        3.0 to Color(0xFF2DE2C4),
        7.0 to Color(0xFF3CB8FF),
        10.5 to Color(0xFF8B7CFF),
        13.5 to Color(0xFFFF5FB1),
        17.0 to Color(0xFFFF7A45),
    )

    /** The colour of a speed on the intensity scale, the same in every chart. */
    fun intensity(speed: Speed): Color {
        val kmh = speed.kmh
        if (kmh <= scale.first().first) return scale.first().second
        for (i in 1 until scale.size) {
            val (k1, c1) = scale[i]
            val (k0, c0) = scale[i - 1]
            if (kmh <= k1) return lerp(c0, c1, ((kmh - k0) / (k1 - k0)).toFloat())
        }
        return scale.last().second
    }

    val heroGradient = Brush.linearGradient(listOf(Color(0xFF2DE2C4), Color(0xFF8B7CFF), Color(0xFFFF5FB1)))
}

private val numbers = TextStyle(fontFeatureSettings = "tnum", fontFamily = FontFamily.SansSerif)

val AppTypography = Typography(
    displayLarge = numbers.copy(fontSize = 84.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.04).em, lineHeight = 84.sp),
    displayMedium = numbers.copy(fontSize = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em, lineHeight = 58.sp),
    displaySmall = numbers.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = numbers.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelMedium = numbers.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.12.em, lineHeight = 14.sp),
)

@Composable
fun WorkoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.accent,
            onPrimary = Ink.onAccent,
            secondary = Color(0xFF8B7CFF),
            background = Ink.background,
            onBackground = Ink.text,
            surface = Ink.surface,
            onSurface = Ink.text,
            surfaceVariant = Ink.surfaceHigh,
            onSurfaceVariant = Ink.textMuted,
            surfaceContainer = Ink.surfaceHigh,
            surfaceContainerHigh = Ink.surfaceHigher,
            surfaceContainerHighest = Ink.surfaceHigher,
            outline = Ink.outlineStrong,
            outlineVariant = Ink.outline,
            error = Ink.danger,
        ),
        typography = AppTypography,
        content = content,
    )
}
