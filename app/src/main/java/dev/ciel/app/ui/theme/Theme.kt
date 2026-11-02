package dev.ciel.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ciel.app.design.AnimationLevel
import dev.ciel.app.design.Appearance
import dev.ciel.app.design.CielDesign
import dev.ciel.app.design.DesignPalette
import dev.ciel.app.design.ThemeStyle
import dev.ciel.app.overlay.StateColors
import dev.ciel.engine.CielState

/** The active appearance and its resolved palette, available anywhere in the UI tree. */
data class CielLook(val appearance: Appearance, val palette: DesignPalette) {
    val design: CielDesign get() = appearance.design
    val theme: ThemeStyle get() = appearance.theme
    val animation: AnimationLevel get() = appearance.animation
}

val LocalLook = staticCompositionLocalOf { CielLook(Appearance(), DesignPalette.resolve(Appearance())) }

/**
 * Colour accessors used throughout the UI. They read the active design, so every screen follows
 * the user's chosen design without each screen knowing about designs.
 */
object CielColors {
    private val p: DesignPalette @Composable @ReadOnlyComposable get() = LocalLook.current.palette
    val Void: Color @Composable @ReadOnlyComposable get() = Color(p.background)
    val Deep: Color @Composable @ReadOnlyComposable get() = Color(p.surface)
    val Panel: Color @Composable @ReadOnlyComposable get() = Color(p.panel)
    val PanelBorder: Color @Composable @ReadOnlyComposable get() = Color(p.border)
    val Cyan: Color @Composable @ReadOnlyComposable get() = Color(p.accent)
    val Ice: Color @Composable @ReadOnlyComposable get() = Color(p.accent2)
    val Text: Color @Composable @ReadOnlyComposable get() = Color(p.text)
    val Muted: Color @Composable @ReadOnlyComposable get() = Color(p.muted)
    val Danger: Color @Composable @ReadOnlyComposable get() = Color(p.danger)
    val Amber: Color @Composable @ReadOnlyComposable get() = Color(p.warning)
    val Green: Color @Composable @ReadOnlyComposable get() = Color(p.success)

    @Composable @ReadOnlyComposable
    fun forState(s: CielState): Color = if (s == CielState.IDLE) Color(p.accent) else Color(StateColors.of(s))
}

private fun typographyFor(design: CielDesign): Typography {
    val mono = design == CielDesign.RAPHAEL
    val light = design == CielDesign.GREAT_SAGE
    return Typography(
        titleLarge = TextStyle(fontWeight = if (light) FontWeight.ExtraLight else FontWeight.Light, fontSize = 22.sp, letterSpacing = 2.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.5.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        bodyLarge = TextStyle(fontSize = if (light) 18.sp else 16.sp, lineHeight = if (light) 27.sp else 23.sp,
            fontWeight = if (light) FontWeight.Light else FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp),
    )
}

@Composable
fun CielTheme(appearance: Appearance, content: @Composable () -> Unit) {
    CielTheme(CielLook(appearance, DesignPalette.resolve(appearance)), content)
}

@Composable
fun CielTheme(look: CielLook, content: @Composable () -> Unit) {
    val p = look.palette
    val scheme = if (p.isLight) lightColorScheme(
        primary = Color(p.accent), onPrimary = Color.White, secondary = Color(p.accent2),
        background = Color(p.background), onBackground = Color(p.text), surface = Color(p.surface), onSurface = Color(p.text),
        surfaceVariant = Color(p.surface), onSurfaceVariant = Color(p.muted), error = Color(p.danger), outline = Color(p.border),
    ) else darkColorScheme(
        primary = Color(p.accent), onPrimary = Color(p.background), secondary = Color(p.accent2),
        background = Color(p.background), onBackground = Color(p.text), surface = Color(p.surface), onSurface = Color(p.text),
        surfaceVariant = Color(p.surface), onSurfaceVariant = Color(p.muted), error = Color(p.danger), outline = Color(p.border),
    )
    CompositionLocalProvider(LocalLook provides look) {
        MaterialTheme(colorScheme = scheme, typography = typographyFor(look.design), content = content)
    }
}
