package dev.ciel.app.design

import android.graphics.Color

/** The four interface designs. Each has its own layout, visualisation and palette. */
enum class CielDesign(val title: String, val tagline: String, val supportsLight: Boolean) {
    CIEL("Ciel", "Anime interface — magic circles, holographic panels, particles", false),
    RAPHAEL("Raphael", "Advanced system — geometric core, structured data panels", false),
    GREAT_SAGE("Great Sage", "Minimal — one calm ring, voice first, almost nothing else", true),
    HYBRID("Hybrid", "Recommended — Ciel's magic, Raphael's structure, Sage's calm", true),
}

enum class AnimationLevel(val label: String) { OFF("Off"), SUBTLE("Subtle"), FULL("Full") }

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

/** Accent presets the user can pick in Appearance. null = the design's own accent. */
enum class AccentColor(val label: String, val argb: Int) {
    CYAN("Cyan", 0xFF5CE1FF.toInt()),
    AZURE("Azure", 0xFF3D8BFF.toInt()),
    VIOLET("Violet", 0xFFA48CFF.toInt()),
    TEAL("Teal", 0xFF3EE6C1.toInt()),
    GOLD("Gold", 0xFFFFC85C.toInt()),
    ROSE("Rose", 0xFFFF7EA8.toInt()),
}

data class Appearance(
    val design: CielDesign = CielDesign.HYBRID,
    val accent: AccentColor? = null,
    val animation: AnimationLevel = AnimationLevel.FULL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/** Colours as ARGB ints so both Compose and the View-based overlays can use them. */
data class DesignPalette(
    val background: Int,
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
        fun of(design: CielDesign, accent: AccentColor? = null, light: Boolean = false): DesignPalette {
            val base = when (design) {
                CielDesign.CIEL -> DesignPalette(
                    background = 0xFF050B18.toInt(), surface = 0xFF0A1830.toInt(), panel = 0xCC0C1E3A.toInt(),
                    border = 0x995CE1FF.toInt(), accent = 0xFF5CE1FF.toInt(), accent2 = 0xFFBDF4FF.toInt(),
                    text = 0xFFDDF6FF.toInt(), muted = 0xFF8FB4C8.toInt())
                CielDesign.RAPHAEL -> DesignPalette(
                    background = 0xFF02040A.toInt(), surface = 0xFF070D1A.toInt(), panel = 0xF00B1426.toInt(),
                    border = 0xFF1E3A66.toInt(), accent = 0xFF3D8BFF.toInt(), accent2 = 0xFF00E5FF.toInt(),
                    text = 0xFFE6F0FF.toInt(), muted = 0xFF7086A8.toInt())
                CielDesign.GREAT_SAGE -> if (light) DesignPalette(
                    background = 0xFFF4F9FF.toInt(), surface = 0xFFFFFFFF.toInt(), panel = 0xF2FFFFFF.toInt(),
                    border = 0xFFCFE3F7.toInt(), accent = 0xFF2A7DE1.toInt(), accent2 = 0xFF7DB8F5.toInt(),
                    text = 0xFF0E2A47.toInt(), muted = 0xFF6A84A0.toInt(), isLight = true)
                    else DesignPalette(
                    background = 0xFF0B1622.toInt(), surface = 0xFF102031.toInt(), panel = 0xEE132638.toInt(),
                    border = 0xFF22405E.toInt(), accent = 0xFF7DB8F5.toInt(), accent2 = 0xFFCFE3F7.toInt(),
                    text = 0xFFE8F2FC.toInt(), muted = 0xFF88A2BD.toInt())
                CielDesign.HYBRID -> if (light) DesignPalette(
                    background = 0xFFEFF5FC.toInt(), surface = 0xFFFFFFFF.toInt(), panel = 0xEEFFFFFF.toInt(),
                    border = 0xFFB9D6F2.toInt(), accent = 0xFF1C8FC9.toInt(), accent2 = 0xFF4A6FDB.toInt(),
                    text = 0xFF0B2236.toInt(), muted = 0xFF5D7A94.toInt(), isLight = true)
                    else DesignPalette(
                    background = 0xFF060D1C.toInt(), surface = 0xFF0C1830.toInt(), panel = 0xD90E1D36.toInt(),
                    border = 0x8C5CE1FF.toInt(), accent = 0xFF5CE1FF.toInt(), accent2 = 0xFF8FB8FF.toInt(),
                    text = 0xFFDDF0FF.toInt(), muted = 0xFF8BA6C2.toInt())
            }
            return if (accent == null) base else base.copy(accent = accent.argb, border = withAlpha(accent.argb, if (base.isLight) 0x66 else 0x8C))
        }

        fun of(design: CielDesign) = of(design, null, design == CielDesign.GREAT_SAGE)

        fun withAlpha(argb: Int, alpha: Int) = Color.argb(alpha, Color.red(argb), Color.green(argb), Color.blue(argb))

        /** Resolves light/dark for an appearance and the system setting. */
        fun resolve(a: Appearance, systemDark: Boolean): DesignPalette {
            val light = a.design.supportsLight && when (a.themeMode) {
                ThemeMode.LIGHT -> true
                ThemeMode.DARK -> false
                ThemeMode.SYSTEM -> if (a.design == CielDesign.GREAT_SAGE) true else !systemDark
            }
            return of(a.design, a.accent, light)
        }
    }
}
