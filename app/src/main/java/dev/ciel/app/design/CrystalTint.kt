package dev.ciel.app.design

import kotlin.math.cos
import kotlin.math.sin

/**
 * Colour matrix (4×5, Android/Compose layout) that recolours the crystal image for a theme:
 * a luminance-preserving hue rotation plus a saturation change, and a dimmed variant for
 * offline mode. Shared by the Compose crystal and the View-based floating orb.
 */
object CrystalTint {
    fun matrix(theme: ThemeStyle, dimmed: Boolean = false): FloatArray {
        val hue = hueRotation(theme.crystalHue)
        val sat = saturation(if (dimmed) theme.crystalSaturation * 0.35f else theme.crystalSaturation)
        val m = multiply(sat, hue)
        if (dimmed) for (row in 0..2) for (col in 0..3) m[row * 5 + col] *= 0.6f
        return m
    }

    private fun hueRotation(degrees: Float): FloatArray {
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun saturation(s: Float): FloatArray {
        val r = 0.213f * (1 - s); val g = 0.715f * (1 - s); val b = 0.072f * (1 - s)
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** a × b for 4×5 colour matrices (treating them as 5×5 with an implicit last row 0 0 0 0 1). */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0..3) {
            for (col in 0..4) {
                var v = 0f
                for (k in 0..3) v += a[row * 5 + k] * b[k * 5 + col]
                if (col == 4) v += a[row * 5 + 4]
                out[row * 5 + col] = v
            }
        }
        return out
    }
}
