package app.lightneet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Calm and monochrome, with a single warm "light" accent. Deliberately no hacker aesthetics.
 */
object LnColors {
    val Background = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181D)
    val SurfaceHigh = Color(0xFF1E2128)
    val Outline = Color(0xFF2A2E37)
    val Text = Color(0xFFE8E9EC)
    val TextMuted = Color(0xFF9097A3)
    val Accent = Color(0xFFF2C14E)
    val OnAccent = Color(0xFF1A1405)
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF46A758)
}

private val scheme = darkColorScheme(
    primary = LnColors.Accent,
    onPrimary = LnColors.OnAccent,
    primaryContainer = Color(0xFF3A2F12),
    onPrimaryContainer = LnColors.Accent,
    secondary = LnColors.TextMuted,
    onSecondary = LnColors.Background,
    background = LnColors.Background,
    onBackground = LnColors.Text,
    surface = LnColors.Background,
    onSurface = LnColors.Text,
    surfaceVariant = LnColors.Surface,
    onSurfaceVariant = LnColors.TextMuted,
    surfaceContainer = LnColors.Surface,
    surfaceContainerHigh = LnColors.SurfaceHigh,
    surfaceContainerHighest = LnColors.SurfaceHigh,
    surfaceContainerLow = LnColors.Surface,
    outline = LnColors.Outline,
    outlineVariant = LnColors.Outline,
    inverseSurface = Color(0xFF2A2E37),
    inverseOnSurface = LnColors.Text,
    inversePrimary = LnColors.Accent,
    error = LnColors.Danger,
    onError = Color.White,
)

private val typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
    )
}

@Composable
fun LightneetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

val MonoLabel = TextStyle(fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
