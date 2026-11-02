package dev.ciel.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ciel.app.overlay.StateColors
import dev.ciel.engine.CielState

object CielColors {
    val Void = Color(0xFF050B18)
    val Deep = Color(0xFF0A1830)
    val Panel = Color(0xCC0C1E3A)
    val PanelBorder = Color(0x995CE1FF)
    val Cyan = Color(0xFF5CE1FF)
    val Ice = Color(0xFFBDF4FF)
    val Text = Color(0xFFDDF6FF)
    val Muted = Color(0xFF8FB4C8)
    val Danger = Color(0xFFE0304A)
    val Amber = Color(0xFFFFC45C)
    val Green = Color(0xFF6EF0AA)

    fun forState(s: CielState) = Color(StateColors.of(s))
}

private val scheme = darkColorScheme(
    primary = CielColors.Cyan,
    onPrimary = CielColors.Void,
    secondary = CielColors.Ice,
    background = CielColors.Void,
    onBackground = CielColors.Text,
    surface = CielColors.Deep,
    onSurface = CielColors.Text,
    surfaceVariant = Color(0xFF10233F),
    onSurfaceVariant = CielColors.Muted,
    error = CielColors.Danger,
    outline = CielColors.PanelBorder,
)

private val typography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 22.sp, letterSpacing = 2.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun CielTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
