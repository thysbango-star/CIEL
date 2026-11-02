package dev.ciel.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.engine.CielState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ciel's central visualisation: an original magic-circle construct of layered rotating rings,
 * rune ticks, an inscribed star and drifting particles. Each state changes colour, speed and
 * form. [level] (0..1) reacts to the microphone while listening. Kept to simple Canvas
 * primitives so it stays smooth on modest phones; [reducedMotion] freezes rotation.
 */
@Composable
fun MagicCircle(state: CielState, level: Float, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(CielColors.forState(state), tween(500), label = "color")
    val t = rememberInfiniteTransition(label = "circle")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    val particles = remember { List(36) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    val speed = when (state) {
        CielState.PROCESSING, CielState.ANALYSING -> 4f
        CielState.RESEARCHING, CielState.EXECUTING -> 3f
        CielState.LISTENING, CielState.SPEAKING -> 1.6f
        else -> 1f
    }
    val a = if (reducedMotion) 0f else spin * speed

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.92f
        // Aura
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.22f + 0.1f * breathe), Color.Transparent), c, r * 1.08f), r * 1.08f, c)
        // Outer double ring
        drawCircle(color.copy(alpha = 0.85f), r, c, style = Stroke(2.2.dp.toPx()))
        drawCircle(color.copy(alpha = 0.45f), r * 0.94f, c, style = Stroke(1.dp.toPx()))
        // Rune ring (rotating)
        rotate(a, c) { runeRing(c, r * 0.86f, color, 48) }
        // Middle ring with arcs (counter-rotating)
        rotate(-a * 1.4f, c) {
            for (i in 0 until 6) drawArc(color.copy(alpha = 0.7f), i * 60f + 8f, 44f, false, Offset(c.x - r * 0.7f, c.y - r * 0.7f),
                androidx.compose.ui.geometry.Size(r * 1.4f, r * 1.4f), style = Stroke(1.6.dp.toPx()))
        }
        // Inscribed star (two triangles) — sharper while analysing
        rotate(a * 0.5f, c) {
            val rr = r * (if (state == CielState.ANALYSING || state == CielState.PROCESSING) 0.62f else 0.58f)
            for (k in 0..1) {
                val p = Path()
                for (i in 0..3) {
                    val ang = Math.toRadians((k * 60 + i * 120 - 90).toDouble())
                    val x = c.x + (rr * cos(ang)).toFloat(); val y = c.y + (rr * sin(ang)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, color.copy(alpha = 0.55f), style = Stroke(1.3.dp.toPx()))
            }
        }
        drawCircle(color.copy(alpha = 0.6f), r * 0.36f, c, style = Stroke(1.dp.toPx()))
        // Core, reacting to voice level
        val coreR = r * (0.2f + 0.05f * breathe + if (state == CielState.LISTENING) 0.12f * level else 0f)
        drawCircle(Brush.radialGradient(listOf(Color.White, color, Color.Transparent), c, coreR * 1.6f), coreR * 1.6f, c)
        // Particles drifting outward
        if (!reducedMotion) particles.forEach { (px, py, pz) ->
            val ang = (px * 360f + a * (0.2f + pz)) * (Math.PI / 180f).toFloat()
            val dist = r * (0.4f + ((py + breathe * 0.15f + spin / 360f * pz) % 1f) * 0.7f)
            drawCircle(color.copy(alpha = 0.35f * (1f - dist / (r * 1.1f)).coerceIn(0f, 1f)), 1.6.dp.toPx() * (0.6f + pz),
                Offset(c.x + dist * cos(ang), c.y + dist * sin(ang)))
        }
        // Completion / warning / error marks
        when (state) {
            CielState.COMPLETED -> drawCircle(CielColors.Green.copy(alpha = 0.9f), r * 1.0f, c, style = Stroke(3.dp.toPx()))
            CielState.WARNING, CielState.ERROR -> {
                val p = Path().apply { moveTo(c.x, c.y - r * 0.14f); lineTo(c.x, c.y + r * 0.04f) }
                drawPath(p, Color.White, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 2.dp.toPx(), Offset(c.x, c.y + r * 0.11f))
            }
            else -> Unit
        }
    }
}

private fun DrawScope.runeRing(c: Offset, r: Float, color: Color, count: Int) {
    for (i in 0 until count) {
        val ang = Math.toRadians(i * 360.0 / count)
        val long = i % 4 == 0
        val r1 = r - (if (long) 9.dp.toPx() else 4.dp.toPx())
        drawLine(color.copy(alpha = if (long) 0.9f else 0.5f),
            Offset(c.x + (r1 * cos(ang)).toFloat(), c.y + (r1 * sin(ang)).toFloat()),
            Offset(c.x + (r * cos(ang)).toFloat(), c.y + (r * sin(ang)).toFloat()), strokeWidth = 1.2.dp.toPx())
        if (i % 8 == 4) drawCircle(color, 1.8.dp.toPx(), Offset(c.x + ((r - 14.dp.toPx()) * cos(ang)).toFloat(), c.y + ((r - 14.dp.toPx()) * sin(ang)).toFloat()))
    }
}

/** Translucent "system window" panel used throughout the interface. */
@Composable
fun CielPanel(modifier: Modifier = Modifier, title: String? = null, accent: Color = CielColors.PanelBorder, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(CielColors.Panel, RoundedCornerShape(14.dp))
            .border(1.dp, accent, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        if (title != null) Text("« $title »", style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

/** Anime-style system notification line, e.g. «Notice» Skill Research activated. */
@Composable
fun SystemNotice(kind: String, text: String, color: Color = CielColors.Cyan, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("«$kind» $text", style = MaterialTheme.typography.labelSmall, color = color)
    }
}
