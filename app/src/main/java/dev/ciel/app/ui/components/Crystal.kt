package dev.ciel.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ciel.app.R
import dev.ciel.app.design.AnimationLevel
import dev.ciel.app.design.BackgroundKind
import dev.ciel.app.design.CielDesign
import dev.ciel.app.design.CrystalTint
import dev.ciel.app.design.ThemeStyle
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.ui.theme.LocalLook
import dev.ciel.engine.CielState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** How the rings around the crystal are drawn; follows the layout. */
enum class RingStyle { ORBITAL, ORBITAL_RUNES, RUNES, HEX }

fun ringStyleFor(design: CielDesign) = when (design) {
    CielDesign.GREAT_SAGE -> RingStyle.ORBITAL
    CielDesign.HYBRID -> RingStyle.ORBITAL_RUNES
    CielDesign.CIEL -> RingStyle.RUNES
    CielDesign.RAPHAEL -> RingStyle.HEX
}

/**
 * Ciel's crystal: the glowing core image, tinted by theme, floating inside orbital rings.
 * States:
 *  idle — slow float and rotation · listening — brighter, ripples and a voice-level wave ·
 *  thinking — faster rings and orbiting symbols · analysing — extra orbit with data nodes ·
 *  executing — energy travels the ring, optional progress arc · speaking — pulse ·
 *  error/warning — warning ring · completed — confirmation ring · offline — dimmed.
 */
@Composable
fun CrystalCore(
    state: CielState,
    level: Float,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
    progress: Float? = null,
    ringStyle: RingStyle = ringStyleFor(LocalLook.current.design),
) {
    val look = LocalLook.current
    val a = look.appearance
    val core = ImageBitmap.imageResource(R.drawable.ciel_core)
    val filter = remember(a.theme, offline) { ColorFilter.colorMatrix(ColorMatrix(CrystalTint.matrix(a.theme, offline))) }
    val stateColor by animateColorAsState(CielColors.forState(state), tween(500), label = "state")
    val accent = Color(look.palette.accent)
    val danger = CielColors.Danger
    val success = CielColors.Green
    val lvl by animateFloatAsState(level, tween(120), label = "level")
    val seeds = remember { List(40) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    // One continuously advancing clock (seconds), frozen when animation is off.
    val clock = rememberInfiniteTransition(label = "clock")
    val tRaw by clock.animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "t")
    val t = if (a.animation == AnimationLevel.OFF) 0f else tRaw * (if (a.animation == AnimationLevel.SUBTLE) 0.5f else 1f)

    val busy = state == CielState.PROCESSING || state == CielState.ANALYSING || state == CielState.RESEARCHING || state == CielState.EXECUTING
    val speed = a.ringSpeed * when (state) {
        CielState.PROCESSING, CielState.ANALYSING -> 3.5f
        CielState.RESEARCHING, CielState.EXECUTING -> 2.5f
        CielState.LISTENING -> 1.6f
        else -> 1f
    }
    val particleCount = (a.particles * seeds.size).toInt()
    val glow = a.glow * (if (state == CielState.LISTENING || state == CielState.SPEAKING) 1.3f else 1f) * (if (offline) 0.4f else 1f)

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val ringColor = if (state == CielState.IDLE) accent else stateColor

        // Aura
        drawCircle(Brush.radialGradient(listOf(ringColor.copy(alpha = (0.35f * glow).coerceIn(0f, 0.6f)), Color.Transparent), c, r), r, c)

        // Rings
        when (ringStyle) {
            RingStyle.ORBITAL -> orbitals(c, r, ringColor, t * speed, state == CielState.EXECUTING)
            RingStyle.ORBITAL_RUNES -> { orbitals(c, r, ringColor, t * speed, state == CielState.EXECUTING); runes(c, r * 0.95f, ringColor.copy(alpha = 0.5f), t * speed * 4f, 48) }
            RingStyle.RUNES -> { drawCircle(ringColor.copy(alpha = 0.7f), r * 0.96f, c, style = Stroke(1.5.dp.toPx())); runes(c, r * 0.9f, ringColor, t * speed * 6f, 60) }
            RingStyle.HEX -> hexes(c, r, ringColor, t * speed * 8f)
        }

        // Thinking / analysing symbols
        if (busy) {
            val n = if (state == CielState.ANALYSING) 10 else 6
            for (i in 0 until n) {
                val ang = (t * speed * 0.6f + i.toFloat() / n) * 2 * PI.toFloat()
                val p = Offset(c.x + r * 0.78f * cos(ang), c.y + r * 0.78f * sin(ang) * 0.6f)
                if (state == CielState.ANALYSING && i % 2 == 0) drawCircle(ringColor, 2.5.dp.toPx(), p)
                else diamond(p, 4.dp.toPx(), ringColor.copy(alpha = 0.8f))
            }
        }

        // Listening ripples + voice wave
        if (state == CielState.LISTENING) {
            for (k in 1..3) {
                val rr = r * (0.42f + 0.1f * k * (0.4f + lvl))
                drawCircle(ringColor.copy(alpha = 0.3f / k), rr, c, style = Stroke(1.5.dp.toPx()))
            }
            val bars = 36
            for (i in 0 until bars) {
                val ang = i.toFloat() / bars * 2 * PI.toFloat()
                val amp = r * 0.07f * (0.3f + lvl) * (0.5f + 0.5f * sin(t * 12f + i))
                val r1 = r * 0.47f; val r2 = r1 + amp
                drawLine(ringColor.copy(alpha = 0.7f), Offset(c.x + r1 * cos(ang), c.y + r1 * sin(ang)), Offset(c.x + r2 * cos(ang), c.y + r2 * sin(ang)), 2.dp.toPx())
            }
        }

        // Executing progress arc
        if (state == CielState.EXECUTING && progress != null) {
            drawArc(success, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(c.x - r * 0.98f, c.y - r * 0.98f), Size(r * 1.96f, r * 1.96f), style = Stroke(3.dp.toPx()))
        }

        // The crystal image
        val pulse = when (state) {
            CielState.SPEAKING -> 1f + 0.05f * sin(t * 9f)
            CielState.LISTENING -> 1.04f + 0.06f * lvl
            CielState.PROCESSING, CielState.ANALYSING -> 1f + 0.025f * sin(t * 5f)
            else -> 1f + 0.015f * sin(t * 1.2f)
        }
        val floatY = r * 0.025f * sin(t * 0.9f)
        val coreSize = (r * 0.95f * pulse).toInt().coerceAtLeast(1)
        withTransform({ rotate(t * 4f * a.ringSpeed, c); translate(0f, floatY) }) {
            drawImage(core, IntOffset.Zero, IntSize(core.width, core.height),
                IntOffset((c.x - coreSize / 2f).toInt(), (c.y - coreSize / 2f).toInt()), IntSize(coreSize, coreSize),
                alpha = if (offline) 0.55f else 1f, colorFilter = filter)
        }

        // Particles
        for (i in 0 until particleCount) {
            val (px, py, pz) = seeds[i]
            val ang = (px * 360f + t * 8f * (0.3f + pz)) * (PI.toFloat() / 180f)
            val dist = r * (0.35f + ((py + t * 0.05f * pz) % 1f) * 0.62f)
            drawCircle(ringColor.copy(alpha = 0.5f * (1f - dist / r).coerceIn(0f, 1f) * glow.coerceAtMost(1f)), 1.4.dp.toPx() * (0.6f + pz),
                Offset(c.x + dist * cos(ang), c.y + dist * sin(ang)))
        }

        // Status rings
        when (state) {
            CielState.COMPLETED -> drawCircle(success.copy(alpha = 0.8f), r * 0.98f, c, style = Stroke(2.5.dp.toPx()))
            CielState.WARNING, CielState.ERROR -> drawCircle(danger.copy(alpha = 0.45f + 0.35f * sin(t * 6f).coerceAtLeast(0f)), r * 0.98f, c, style = Stroke(3.dp.toPx()))
            else -> Unit
        }
    }
}

/** Three tilted orbital ellipses with a light travelling along each (energy while executing). */
private fun DrawScope.orbitals(c: Offset, r: Float, color: Color, t: Float, executing: Boolean) {
    val rings = listOf(Triple(-18f, 0.96f, 0.30f), Triple(14f, 0.84f, 0.24f), Triple(62f, 0.74f, 0.20f))
    rings.forEachIndexed { i, (tilt, rx, ry) ->
        val w = r * rx; val h = r * ry
        rotate(tilt + sin(t * 0.15f + i) * 6f, c) {
            drawOval(color.copy(alpha = 0.55f), Offset(c.x - w, c.y - h), Size(w * 2, h * 2), style = Stroke(1.2.dp.toPx()))
            val trail = if (executing) 6 else 1
            for (k in 0 until trail) {
                val ang = (t * (0.25f + i * 0.08f) * (if (executing) 3f else 1f) - k * 0.04f) * 2 * PI.toFloat() * (if (i % 2 == 0) 1 else -1)
                drawCircle(Color.White.copy(alpha = 0.9f / (k + 1)), (2.6f - k * 0.3f).coerceAtLeast(0.8f) * density,
                    Offset(c.x + w * cos(ang), c.y + h * sin(ang)))
            }
        }
    }
}

private fun DrawScope.runes(c: Offset, r: Float, color: Color, rotation: Float, count: Int) {
    rotate(rotation, c) {
        for (i in 0 until count) {
            val ang = (i.toFloat() / count) * 2 * PI.toFloat()
            val long = i % 4 == 0
            val r1 = r - (if (long) 8.dp.toPx() else 3.5.dp.toPx())
            drawLine(color.copy(alpha = if (long) 0.85f else 0.45f), Offset(c.x + r1 * cos(ang), c.y + r1 * sin(ang)), Offset(c.x + r * cos(ang), c.y + r * sin(ang)), 1.1.dp.toPx())
        }
    }
}

private fun DrawScope.hexes(c: Offset, r: Float, color: Color, rotation: Float) {
    fun hex(radius: Float, rot: Float, alpha: Float) {
        val p = Path()
        for (i in 0..6) {
            val a = ((rot + i * 60f) * PI / 180f).toFloat()
            val x = c.x + radius * cos(a); val y = c.y + radius * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color.copy(alpha = alpha), style = Stroke(1.4.dp.toPx()))
    }
    hex(r * 0.95f, rotation * 0.3f, 0.85f)
    hex(r * 0.8f, -rotation * 0.5f + 30f, 0.5f)
    for (i in 0 until 72) {
        val a = (i * 5f * PI / 180f).toFloat()
        val len = if (i % 6 == 0) 7.dp.toPx() else 3.dp.toPx()
        drawLine(color.copy(alpha = if (i % 6 == 0) 0.7f else 0.3f), Offset(c.x + r * cos(a), c.y + r * sin(a)), Offset(c.x + (r - len) * cos(a), c.y + (r - len) * sin(a)), 1.dp.toPx())
    }
}

private fun DrawScope.diamond(p: Offset, s: Float, color: Color) {
    val path = Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s * 0.6f, p.y); lineTo(p.x, p.y + s); lineTo(p.x - s * 0.6f, p.y); close() }
    drawPath(path, color)
}

/**
 * Static themed background (drawn once and cached — no per-frame cost): gradient plus the
 * theme's decoration (clouds, light rays, petals, leaves, golden rays, stars, glass waves).
 */
@Composable
fun ThemeBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val look = LocalLook.current
    val theme = look.theme
    val top = Color(look.palette.background)
    val bottom = Color(look.palette.background2)
    val accent = Color(look.palette.accent)
    val particleDensity = look.appearance.particles
    Box(modifier.drawWithCache {
        val w = size.width; val h = size.height
        onDrawBehind {
            val rnd = Random(theme.ordinal * 31 + 7) // same seed every draw → decorations never jump
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
            val n = (20 + 80 * particleDensity).toInt()
            when (theme.background) {
                BackgroundKind.CLOUDS -> for (i in 0 until 7) {
                    val cx = rnd.nextFloat() * w; val cy = h * (0.7f + rnd.nextFloat() * 0.35f); val rw = w * (0.3f + rnd.nextFloat() * 0.4f)
                    drawOval(Color.White.copy(alpha = 0.05f), Offset(cx - rw, cy - rw * 0.25f), Size(rw * 2, rw * 0.5f))
                }
                BackgroundKind.LIGHT_RAYS, BackgroundKind.GOLDEN_RAYS -> {
                    val ray = if (theme.background == BackgroundKind.GOLDEN_RAYS) Color(0xFFFFE7A8) else Color.White
                    for (i in 0 until 6) {
                        val x = w * (0.1f + i * 0.16f + rnd.nextFloat() * 0.05f); val rw = w * (0.03f + rnd.nextFloat() * 0.05f)
                        drawRect(Brush.verticalGradient(listOf(ray.copy(alpha = 0.28f), Color.Transparent)), Offset(x, 0f), Size(rw, h * 0.75f))
                    }
                }
                BackgroundKind.VOID -> drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.06f), Color.Transparent), Offset(w / 2, h * 0.35f), w * 0.7f), w * 0.7f, Offset(w / 2, h * 0.35f))
                BackgroundKind.PETALS, BackgroundKind.LEAVES -> {
                    val col = if (theme == ThemeStyle.TWILIGHT_SAGE) Color(0xFFC9B4FF) else Color(0xFF5CF0B4)
                    for (i in 0 until n / 3) {
                        val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val s = 4f + rnd.nextFloat() * 8f
                        rotate(rnd.nextFloat() * 180f, Offset(x, y)) { drawOval(col.copy(alpha = 0.18f + rnd.nextFloat() * 0.25f), Offset(x - s, y - s / 2.5f), Size(s * 2, s / 1.25f)) }
                    }
                }
                BackgroundKind.STARS -> {
                    for (i in 0 until n * 2) drawCircle(Color.White.copy(alpha = 0.2f + rnd.nextFloat() * 0.6f), 0.5f + rnd.nextFloat() * 1.3f, Offset(rnd.nextFloat() * w, rnd.nextFloat() * h))
                    drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f))), Offset(w * 0.15f, h * 0.12f), Offset(w * 0.4f, h * 0.22f), 1.5f)
                }
                BackgroundKind.GLASS_WAVES -> for (i in 0 until 4) {
                    val p = Path()
                    val y0 = h * (0.25f + i * 0.18f)
                    p.moveTo(0f, y0)
                    p.cubicTo(w * 0.3f, y0 - h * 0.12f, w * 0.6f, y0 + h * 0.12f, w, y0 - h * 0.04f)
                    p.lineTo(w, y0 + h * 0.08f); p.cubicTo(w * 0.6f, y0 + h * 0.2f, w * 0.3f, y0, 0f, y0 + h * 0.1f); p.close()
                    drawPath(p, Color.White.copy(alpha = 0.06f))
                }
            }
        }
    }) { content() }
}
