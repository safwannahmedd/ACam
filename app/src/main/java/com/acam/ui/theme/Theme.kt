package com.acam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.acam.R
import com.acam.data.ThemeMode

/**
 * Typography is set in Inter (bundled, SIL OFL 1.1; static instances
 * generated from the official variable font).
 */
val InterFamily = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold)
)

val AcamTypography = Typography(
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

// One restrained accent: burnt orange. Flat surfaces everywhere else.
private val DarkScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFE8590C),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    background = androidx.compose.ui.graphics.Color(0xFF0C0C0E),
    onBackground = androidx.compose.ui.graphics.Color(0xFFEDEDEF),
    surface = androidx.compose.ui.graphics.Color(0xFF131316),
    onSurface = androidx.compose.ui.graphics.Color(0xFFEDEDEF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1D1D21),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9E9EA6),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF17171A),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1D1D21),
    outline = androidx.compose.ui.graphics.Color(0xFF3A3A40),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF2A2A2F),
    error = androidx.compose.ui.graphics.Color(0xFFFF6B6B)
)

private val LightScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFC2410C),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    background = androidx.compose.ui.graphics.Color(0xFFFAFAF7),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFECECE6),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF5D5D64),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF2F2EE),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFECECE6),
    outline = androidx.compose.ui.graphics.Color(0xFFC9C9C1),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFDDDDD6),
    error = androidx.compose.ui.graphics.Color(0xFFC92A2A)
)

/** Manual Light/Dark toggle with System fallback (persisted in DataStore). */
@Composable
fun ACamTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AcamTypography,
        content = content
    )
}
