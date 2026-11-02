package dev.ciel.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.ciel.app.design.AnimationLevel
import dev.ciel.app.design.CielDesign
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.ui.theme.LocalLook
import dev.ciel.engine.CielState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The central visualisation for the active design. */
@Composable
fun CielVisual(state: CielState, level: Float, modifier: Modifier = Modifier, design: CielDesign = LocalLook.current.design) {
    val anim = LocalLook.current.animation
    when (design) {
        CielDesign.CIEL -> MagicCircle(state, level, anim, particles = true, modifier = modifier)
        CielDesign.RAPHAEL -> HexCore(state, level, anim, modifier)
        CielDesign.GREAT_SAGE -> SageRing(state, level, anim, modifier)
        CielDesign.HYBRID -> MagicCircle(state, level, anim, particles = anim == AnimationLevel.FULL, waveform = true, modifier = modifier)
    }
}

@Composable
private fun spin(anim: AnimationLevel, periodMs: Int): Float {
    val t = rememberInfiniteTransition(label = "spin")
    val v by t.animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "spin")
    return if (anim == AnimationLevel.OFF) 0f else v
}

@Composable
private fun breathe(anim: AnimationLevel): Float {
    val t = rememberInfiniteTransition(label = "breathe")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    return if (anim == AnimationLevel.OFF) 0.5f else v
}

private fun speedFor(state: CielState, anim: AnimationLevel): Float {
    val base = when (state) {
        CielState.PROCESSING, CielState.ANALYSING -> 4f
        CielState.RESEARCHING, CielState.EXECUTING -> 3f
        CielState.LISTENING, CielState.SPEAKING -> 1.6f
        else -> 1f
    }
    return if (anim == AnimationLevel.SUBTLE) base * 0.5f else base
}

/**
 * Ciel / Hybrid: an original magic-circle construct of layered rotating rings, rune ticks, an
 * inscribed star and drifting particles. Each state changes colour, speed and form.
 */
@Composable
fun MagicCircle(state: CielState, level: Float, anim: AnimationLevel, particles: Boolean, modifier: Modifier = Modifier, waveform: Boolean = false) {
    val color by animateColorAsState(CielColors.forState(state), tween(500), label = "color")
    val done = CielColors.Green
    val spinV = spin(anim, 24_000)
    val breatheV = breathe(anim)
    val lvl by animateFloatAsState(level, tween(120), label = "level")
    val seeds = remember { List(36) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    val a = spinV * speedFor(state, anim)

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.92f
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.22f + 0.1f * breatheV), Color.Transparent), c, r * 1.08f), r * 1.08f, c)
        drawCircle(color.copy(alpha = 0.85f), r, c, style = Stroke(2.2.dp.toPx()))
        drawCircle(color.copy(alpha = 0.45f), r * 0.94f, c, style = Stroke(1.dp.toPx()))
        rotate(a, c) { runeRing(c, r * 0.86f, color, 48) }
        rotate(-a * 1.4f, c) {
            for (i in 0 until 6) drawArc(color.copy(alpha = 0.7f), i * 60f + 8f, 44f, false, Offset(c.x - r * 0.7f, c.y - r * 0.7f),
                Size(r * 1.4f, r * 1.4f), style = Stroke(1.6.dp.toPx()))
        }
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
        val coreR = r * (0.2f + 0.05f * breatheV + if (state == CielState.LISTENING) 0.12f * lvl else 0f)
        drawCircle(Brush.radialGradient(listOf(Color.White, color, Color.Transparent), c, coreR * 1.6f), coreR * 1.6f, c)
        if (waveform && (state == CielState.SPEAKING || state == CielState.LISTENING)) {
            // Waveform band across the core while Ciel speaks or listens.
            val bars = 24
            val w = r * 1.1f
            for (i in 0 until bars) {
                val x = c.x - w / 2 + w * i / (bars - 1)
                val amp = r * 0.12f * (0.3f + 0.7f * kotlin.math.abs(sin(Math.toRadians((a * 6 + i * 37).toDouble())).toFloat())) *
                    (if (state == CielState.LISTENING) 0.4f + lvl else 1f)
                drawLine(color.copy(alpha = 0.8f), Offset(x, c.y - amp), Offset(x, c.y + amp), strokeWidth = 2.dp.toPx())
            }
        }
        if (particles) seeds.forEach { (px, py, pz) ->
            val ang = (px * 360f + a * (0.2f + pz)) * (Math.PI / 180f).toFloat()
            val dist = r * (0.4f + ((py + breatheV * 0.15f + spinV / 360f * pz) % 1f) * 0.7f)
            drawCircle(color.copy(alpha = 0.35f * (1f - dist / (r * 1.1f)).coerceIn(0f, 1f)), 1.6.dp.toPx() * (0.6f + pz),
                Offset(c.x + dist * cos(ang), c.y + dist * sin(ang)))
        }
        statusMarks(state, c, r, done)
    }
}

/** Raphael: a precise geometric core — nested hexagons, measurement ticks, orbiting nodes. */
@Composable
fun HexCore(state: CielState, level: Float, anim: AnimationLevel, modifier: Modifier = Modifier) {
    val color by animateColorAsState(CielColors.forState(state), tween(400), label = "color")
    val accent2 = CielColors.Ice
    val done = CielColors.Green
    val spinV = spin(anim, 30_000)
    val breatheV = breathe(anim)
    val a = spinV * speedFor(state, anim)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.92f
        fun hex(radius: Float, rot: Float, col: Color, width: Float) {
            val p = Path()
            for (i in 0..6) {
                val t = Math.toRadians((rot + i * 60).toDouble())
                val x = c.x + (radius * cos(t)).toFloat(); val y = c.y + (radius * sin(t)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, col, style = Stroke(width))
        }
        // Measurement ticks
        for (i in 0 until 72) {
            val t = Math.toRadians(i * 5.0)
            val len = if (i % 6 == 0) 10.dp.toPx() else 4.dp.toPx()
            drawLine(color.copy(alpha = if (i % 6 == 0) 0.8f else 0.35f),
                Offset(c.x + (r * cos(t)).toFloat(), c.y + (r * sin(t)).toFloat()),
                Offset(c.x + ((r - len) * cos(t)).toFloat(), c.y + ((r - len) * sin(t)).toFloat()), strokeWidth = 1.dp.toPx())
        }
        hex(r * 0.82f, a * 0.3f, color.copy(alpha = 0.9f), 2.dp.toPx())
        hex(r * 0.64f, -a * 0.6f + 30f, accent2.copy(alpha = 0.6f), 1.2.dp.toPx())
        hex(r * 0.42f, a + 0f, color.copy(alpha = 0.5f), 1.dp.toPx())
        // Orbiting nodes with connecting lines
        val nodes = (0 until 6).map { i ->
            val t = Math.toRadians((a * 0.3 + i * 60))
            Offset(c.x + (r * 0.82f * cos(t)).toFloat(), c.y + (r * 0.82f * sin(t)).toFloat())
        }
        nodes.forEachIndexed { i, n ->
            drawCircle(color, 3.dp.toPx(), n)
            if (state != CielState.IDLE && i % 2 == 0) drawLine(color.copy(alpha = 0.35f), n, c, strokeWidth = 1.dp.toPx())
        }
        val coreR = r * (0.16f + 0.04f * breatheV + if (state == CielState.LISTENING) 0.1f * level else 0f)
        drawRect(Brush.radialGradient(listOf(Color.White, color, Color.Transparent), c, coreR * 1.8f),
            Offset(c.x - coreR * 1.8f, c.y - coreR * 1.8f), Size(coreR * 3.6f, coreR * 3.6f))
        statusMarks(state, c, r, done)
    }
}

/** Great Sage: one calm ring that breathes, fills while thinking and ripples while listening. */
@Composable
fun SageRing(state: CielState, level: Float, anim: AnimationLevel, modifier: Modifier = Modifier) {
    val color by animateColorAsState(CielColors.forState(state), tween(600), label = "color")
    val done = CielColors.Green
    val spinV = spin(anim, 6000)
    val breatheV = breathe(anim)
    val lvl by animateFloatAsState(level, tween(150), label = "level")
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.8f
        drawCircle(color.copy(alpha = 0.08f + 0.06f * breatheV), r * 1.15f, c)
        drawCircle(color.copy(alpha = 0.9f), r, c, style = Stroke(3.dp.toPx()))
        when (state) {
            CielState.LISTENING -> for (k in 1..3) {
                val rr = r * (1f + 0.08f * k * (0.3f + lvl))
                drawCircle(color.copy(alpha = 0.35f / k), rr, c, style = Stroke(1.5.dp.toPx()))
            }
            CielState.PROCESSING, CielState.ANALYSING, CielState.RESEARCHING, CielState.EXECUTING ->
                drawArc(color, spinV, 90f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(5.dp.toPx()))
            CielState.SPEAKING -> drawCircle(color.copy(alpha = 0.25f), r * (0.55f + 0.15f * breatheV), c)
            else -> Unit
        }
        drawCircle(color.copy(alpha = 0.18f), r * 0.5f, c)
        statusMarks(state, c, r, done)
    }
}

private fun DrawScope.statusMarks(state: CielState, c: Offset, r: Float, done: Color) {
    when (state) {
        CielState.COMPLETED -> drawCircle(done.copy(alpha = 0.9f), r * 1.0f, c, style = Stroke(3.dp.toPx()))
        CielState.WARNING, CielState.ERROR -> {
            val p = Path().apply { moveTo(c.x, c.y - r * 0.14f); lineTo(c.x, c.y + r * 0.04f) }
            drawPath(p, Color.White, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 2.dp.toPx(), Offset(c.x, c.y + r * 0.11f))
        }
        else -> Unit
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

/** Panel shape per design: soft for Ciel/Sage, cut corners for Raphael. */
@Composable
fun panelShape(): Shape = when (LocalLook.current.design) {
    CielDesign.RAPHAEL -> CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    CielDesign.GREAT_SAGE -> RoundedCornerShape(22.dp)
    else -> RoundedCornerShape(14.dp)
}

/** Translucent "system window" panel used throughout the interface. */
@Composable
fun CielPanel(modifier: Modifier = Modifier, title: String? = null, accent: Color = CielColors.PanelBorder, content: @Composable ColumnScope.() -> Unit) {
    val design = LocalLook.current.design
    val shape = panelShape()
    Column(
        modifier
            .fillMaxWidth()
            .background(CielColors.Panel, shape)
            .border(if (design == CielDesign.GREAT_SAGE) 0.5.dp else 1.dp, accent, shape)
            .padding(14.dp)
    ) {
        if (title != null) {
            val label = when (design) {
                CielDesign.RAPHAEL -> "▸ ${title.uppercase()}"
                CielDesign.GREAT_SAGE -> title
                else -> "« $title »"
            }
            Text(label, style = if (design == CielDesign.GREAT_SAGE) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
                color = CielColors.Cyan, modifier = Modifier.padding(bottom = 6.dp))
        }
        content()
    }
}

/** Anime-style system notification line, e.g. «Notice» Skill Research activated. */
@Composable
fun SystemNotice(kind: String, text: String, color: Color = CielColors.Cyan, modifier: Modifier = Modifier) {
    val design = LocalLook.current.design
    val label = when (design) {
        CielDesign.RAPHAEL -> "[$kind] $text"
        CielDesign.GREAT_SAGE -> text
        else -> "«$kind» $text"
    }
    Box(modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
