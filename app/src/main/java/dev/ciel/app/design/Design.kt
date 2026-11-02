package dev.ciel.app.design

import android.graphics.Color

/** Layouts: how the home screen is arranged. */
enum class CielDesign(val title: String, val tagline: String, val supportsLight: Boolean) {
    GREAT_SAGE("Great Sage", "Minimal — the crystal, one line of text, voice first", true),
    HYBRID("Hybrid", "Crystal + quick skills, recent tasks and conversation", true),
    CIEL("Ciel", "Immersive — large crystal with floating conversation", false),
    RAPHAEL("Raphael", "Structured — crystal with data panels and analysis log", false),
}

/** Background decoration drawn behind the interface for each theme. */
enum class BackgroundKind { CLOUDS, LIGHT_RAYS, VOID, PETALS, LEAVES, GOLDEN_RAYS, STARS, GLASS_WAVES }

/** The eight visual themes. Each changes palette, background, particles and the crystal's tint. */
enum class ThemeStyle(
    val title: String,
    val tagline: String,
    val background: BackgroundKind,
    /** Hue rotation applied to the crystal image, in degrees. */
    val crystalHue: Float,
    /** Saturation multiplier for the crystal image. */
    val crystalSaturation: Float,
) {
    AZURE_CALM("Azure Calm", "Clean. Elegant. Timeless.", BackgroundKind.CLOUDS, 0f, 1f),
    ETHEREAL_LIGHT("Ethereal Light", "Pure. Serene. Divine.", BackgroundKind.LIGHT_RAYS, 8f, 0.9f),
    VOID_MINIMAL("Void Minimal", "Simple. Focused. Powerful.", BackgroundKind.VOID, 12f, 1f),
    TWILIGHT_SAGE("Twilight Sage", "Calm. Mystical. Elegant.", BackgroundKind.PETALS, 70f, 1f),
    NATURES_WISDOM("Nature's Wisdom", "Peaceful. Natural. Balanced.", BackgroundKind.LEAVES, -25f, 1.05f),
    DIVINE_LIGHT("Divine Light", "Sacred. Regal. Pure.", BackgroundKind.GOLDEN_RAYS, 0f, 0.35f),
    STARFALL("Starfall", "Infinite. Boundless. Calm.", BackgroundKind.STARS, 15f, 1f),
    GLASSMORPHISM("Glassmorphism", "Modern. Minimal. Smooth.", BackgroundKind.GLASS_WAVES, 5f, 0.95f),
}

enum class AnimationLevel(val label: String) { OFF("Off"), SUBTLE("Subtle"), FULL("Full") }

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

enum class FloatingSize(val label: String, val dp: Int) { SMALL("Small", 52), MEDIUM("Medium", 64), LARGE("Large", 80) }

/** Accent presets the user can pick in Appearance. null = the theme's own accent. */
enum class AccentColor(val label: String, val argb: Int) {
    CYAN("Cyan", 0xFF5CE1FF.toInt()),
    AZURE("Azure", 0xFF3D8BFF.toInt()),
    VIOLET("Violet", 0xFFA48CFF.toInt()),
    TEAL("Teal", 0xFF3EE6C1.toInt()),
    GOLD("Gold", 0xFFFFC85C.toInt()),
    ROSE("Rose", 0xFFFF7EA8.toInt()),
}

data class Appearance(
    val design: CielDesign = CielDesign.GREAT_SAGE,
    val theme: ThemeStyle = ThemeStyle.AZURE_CALM,
    val accent: AccentColor? = null,
    val animation: AnimationLevel = AnimationLevel.FULL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 0..1 */
    val glow: Float = 0.7f,
    /** 0..1 */
    val particles: Float = 0.5f,
    /** 0..1 — how see-through panels are. */
    val transparency: Float = 0.5f,
    /** 0.25..2 — orbital ring speed multiplier. */
    val ringSpeed: Float = 1f,
    val floatingSize: FloatingSize = FloatingSize.MEDIUM,
)

/** Colours as ARGB ints so both Compose and the View-based overlays can use them. */
data class DesignPalette(
    val background: Int,
    val background2: Int,
    val surface: Int,
    val panel: Int,
    val border: Int,
    val accent: Int,
    val accent2: Int,
    val text: Int,
    val muted: Int,
    val danger: Int = 0xFFE0304A.toInt(),
    val success: Int = 0xFF4FD99A.toInt(),
    val warning: Int = 0xFFFFB347.toInt(),
    val isLight: Boolean = false,
) {
    companion object {
        fun forTheme(t: ThemeStyle): DesignPalette = when (t) {
            ThemeStyle.AZURE_CALM -> DesignPalette(0xFF061633.toInt(), 0xFF0E3270.toInt(), 0xFF0A1F45.toInt(), 0xB30A2350.toInt(),
                0x8C7FD3FF.toInt(), 0xFF7FD3FF.toInt(), 0xFFCFEAFF.toInt(), 0xFFE6F4FF.toInt(), 0xFF8FB0D0.toInt())
            ThemeStyle.ETHEREAL_LIGHT -> DesignPalette(0xFFEFF6FF.toInt(), 0xFFC6DCF6.toInt(), 0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(),
                0x993E7BD6.toInt(), 0xFF3E7BD6.toInt(), 0xFF7FB2F0.toInt(), 0xFF0D2A4A.toInt(), 0xFF5E7FA3.toInt(), isLight = true)
            ThemeStyle.VOID_MINIMAL -> DesignPalette(0xFF010309.toInt(), 0xFF040A1C.toInt(), 0xFF050B18.toInt(), 0xCC060E20.toInt(),
                0x803D8BFF.toInt(), 0xFF3D8BFF.toInt(), 0xFF9CC4FF.toInt(), 0xFFDDE8FF.toInt(), 0xFF6F84A8.toInt())
            ThemeStyle.TWILIGHT_SAGE -> DesignPalette(0xFF100A2A.toInt(), 0xFF2D2068.toInt(), 0xFF1A1340.toInt(), 0xB3221A55.toInt(),
                0x8CA68CFF.toInt(), 0xFFA68CFF.toInt(), 0xFFE0D4FF.toInt(), 0xFFEEE8FF.toInt(), 0xFFA196C8.toInt())
            ThemeStyle.NATURES_WISDOM -> DesignPalette(0xFF031B1C.toInt(), 0xFF0B4140.toInt(), 0xFF07292A.toInt(), 0xB30A3332.toInt(),
                0x8C3EE6C1.toInt(), 0xFF3EE6C1.toInt(), 0xFFB4F5E4.toInt(), 0xFFE2FBF4.toInt(), 0xFF84B6AC.toInt())
            ThemeStyle.DIVINE_LIGHT -> DesignPalette(0xFFF7F3EA.toInt(), 0xFFDCD6CA.toInt(), 0xFFFFFDF8.toInt(), 0xB3FFFDF6.toInt(),
                0x99C9A24A.toInt(), 0xFFB8902F.toInt(), 0xFFD9BC6E.toInt(), 0xFF2A2418.toInt(), 0xFF7D735E.toInt(), isLight = true)
            ThemeStyle.STARFALL -> DesignPalette(0xFF020611.toInt(), 0xFF0A1736.toInt(), 0xFF060E24.toInt(), 0xB3081430.toInt(),
                0x808FB8FF.toInt(), 0xFF8FB8FF.toInt(), 0xFFDCE8FF.toInt(), 0xFFE8F0FF.toInt(), 0xFF7F92B8.toInt())
            ThemeStyle.GLASSMORPHISM -> DesignPalette(0xFF183A8C.toInt(), 0xFF5A86E0.toInt(), 0xFF2A4FA8.toInt(), 0x33FFFFFF,
                0x66FFFFFF, 0xFFDCEBFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFC9D9F5.toInt())
        }

        fun withAlpha(argb: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(argb), Color.green(argb), Color.blue(argb))

        /** Palette for an appearance: theme colours, optional accent, panel transparency. */
        fun resolve(a: Appearance, @Suppress("UNUSED_PARAMETER") systemDark: Boolean = true): DesignPalette {
            var p = forTheme(a.theme)
            if (a.accent != null) p = p.copy(accent = a.accent.argb, border = withAlpha(a.accent.argb, if (p.isLight) 0x66 else 0x8C))
            // Transparency 0 → nearly opaque panels, 1 → very see-through glass.
            val baseAlpha = Color.alpha(p.panel)
            val panelAlpha = (baseAlpha * (1.3f - a.transparency * 0.9f)).toInt().coerceIn(0x22, 0xF2)
            return p.copy(panel = withAlpha(p.panel, panelAlpha))
        }

        fun of(theme: ThemeStyle) = forTheme(theme)
    }
}
