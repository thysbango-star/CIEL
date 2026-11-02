package dev.ciel.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.ciel.app.design.MotionClock
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
 *
 * Motion is driven by [MotionClock]: every movement loops seamlessly and state changes blend
 * (speeds ease, effects fade) instead of cutting.
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
    val stateColor by animateColorAsState(CielColors.forState(state), tween(600), label = "state")
    val accent = Color(look.palette.accent)
    val danger = CielColors.Danger
    val success = CielColors.Green
    val lvl by animateFloatAsState(level, tween(120), label = "level")
    val seeds = remember { List(40) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    val busy = state == CielState.PROCESSING || state == CielState.ANALYSING || state == CielState.RESEARCHING || state == CielState.EXECUTING
    // Effects fade in and out with the state rather than popping.
    val fade = tween<Float>(450, easing = FastOutSlowInEasing)
    val listenAmt by animateFloatAsState(if (state == CielState.LISTENING) 1f else 0f, fade, label = "listen")
    val busyAmt by animateFloatAsState(if (busy) 1f else 0f, fade, label = "busy")
    val analyseAmt by animateFloatAsState(if (state == CielState.ANALYSING) 1f else 0f, fade, label = "analyse")
    val execAmt by animateFloatAsState(if (state == CielState.EXECUTING) 1f else 0f, fade, label = "exec")
    val warnAmt by animateFloatAsState(if (state == CielState.WARNING || state == CielState.ERROR) 1f else 0f, fade, label = "warn")
    val doneAmt by animateFloatAsState(if (state == CielState.COMPLETED) 1f else 0f, fade, label = "done")
    val pulseAmp by animateFloatAsState(when (state) {
        CielState.SPEAKING -> 0.05f
        CielState.LISTENING -> 0.04f
        CielState.PROCESSING, CielState.ANALYSING -> 0.025f
        else -> 0.015f
    }, tween(500), label = "pulseAmp")
    val glowTarget = a.glow * (if (state == CielState.LISTENING || state == CielState.SPEAKING) 1.3f else 1f) * (if (offline) 0.4f else 1f)
    val glow by animateFloatAsState(glowTarget, tween(500), label = "glow")
    val progressAnim by animateFloatAsState(progress?.coerceIn(0f, 1f) ?: 0f, tween(500), label = "progress")

    // Continuous clock. Off → a still frame; Subtle → half speed.
    val clock = remember { MotionClock() }
    var frame by remember { mutableLongStateOf(0L) }
    val ringTarget by rememberUpdatedState(0.04 * a.ringSpeed * when (state) {
        CielState.PROCESSING, CielState.ANALYSING -> 3.5
        CielState.RESEARCHING, CielState.EXECUTING -> 2.5
        CielState.LISTENING -> 1.6
        else -> 1.0
    })
    val pulseTarget by rememberUpdatedState(when (state) {
        CielState.SPEAKING -> 1.4
        CielState.PROCESSING, CielState.ANALYSING -> 0.8
        else -> 0.19
    })
    val timeScale = when (a.animation) { AnimationLevel.OFF -> 0.0; AnimationLevel.SUBTLE -> 0.5; AnimationLevel.FULL -> 1.0 }
    LaunchedEffect(timeScale) {
        if (timeScale == 0.0) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) clock.advance((now - last) / 1e9, ringTarget, pulseTarget, timeScale)
                last = now
                frame = now
            }
        }
    }
    val particleCount = (a.particles * seeds.size).toInt()

    Canvas(modifier) {
        frame // read → redraw every animation frame (draw phase only, no recomposition)
        val c = center
        val r = size.minDimension / 2f
        val time = clock.time
        val ring = clock.ring
        val ringColor = if (state == CielState.IDLE) accent else stateColor

        // Aura
        drawCircle(Brush.radialGradient(listOf(ringColor.copy(alpha = (0.35f * glow).coerceIn(0f, 0.6f)), Color.Transparent), c, r), r, c)

        // Rings
        when (ringStyle) {
            RingStyle.ORBITAL -> orbitals(c, r, ringColor, ring, clock.drift, execAmt)
            RingStyle.ORBITAL_RUNES -> { orbitals(c, r, ringColor, ring, clock.drift, execAmt); runes(c, r * 0.95f, ringColor.copy(alpha = 0.5f), MotionClock.degrees(ring), 48) }
            RingStyle.RUNES -> { drawCircle(ringColor.copy(alpha = 0.7f), r * 0.96f, c, style = Stroke(1.5.dp.toPx())); runes(c, r * 0.9f, ringColor, MotionClock.degrees(ring), 60) }
            RingStyle.HEX -> hexes(c, r, ringColor, MotionClock.degrees(ring), -MotionClock.degrees(ring, 2) + 30f)
        }

        // Thinking / analysing symbols
        if (busyAmt > 0.01f) {
            val n = 10
            for (i in 0 until n) {
                val odd = i % 2 == 1
                // Odd symbols only belong to "analysing"; they fade with it.
                val alpha = busyAmt * (if (odd) analyseAmt else 1f)
                if (alpha < 0.01f) continue
                val ang = (MotionClock.degrees(ring, 2) + i * 36f) * (PI.toFloat() / 180f)
                val p = Offset(c.x + r * 0.78f * cos(ang), c.y + r * 0.78f * sin(ang) * 0.6f)
                if (analyseAmt > 0.5f && i % 4 == 0) drawCircle(ringColor.copy(alpha = alpha), 2.5.dp.toPx(), p)
                else diamond(p, 4.dp.toPx(), ringColor.copy(alpha = 0.8f * alpha))
            }
        }

        // Listening ripples (continuously expanding, fading at both ends) + voice wave
        if (listenAmt > 0.01f) {
            for (k in 0 until 3) {
                val f = MotionClock.frac(time * 0.45 + k / 3.0)
                val rr = r * (0.44f + 0.4f * f.toFloat())
                drawCircle(ringColor.copy(alpha = 0.35f * listenAmt * MotionClock.envelope(f)), rr, c, style = Stroke(1.5.dp.toPx()))
            }
            val bars = 36
            for (i in 0 until bars) {
                val ang = i.toFloat() / bars * 2 * PI.toFloat()
                val amp = r * 0.07f * (0.3f + lvl) * (0.5f + 0.5f * sin((time * 1.9 * MotionClock.TAU).toFloat() + i * 0.9f)) * listenAmt
                val r1 = r * 0.47f; val r2 = r1 + amp
                drawLine(ringColor.copy(alpha = 0.7f * listenAmt), Offset(c.x + r1 * cos(ang), c.y + r1 * sin(ang)), Offset(c.x + r2 * cos(ang), c.y + r2 * sin(ang)), 2.dp.toPx())
            }
        }

        // Executing progress arc
        if (execAmt > 0.01f && progress != null) {
            drawArc(success.copy(alpha = execAmt), -90f, 360f * progressAnim, false, Offset(c.x - r * 0.98f, c.y - r * 0.98f), Size(r * 1.96f, r * 1.96f), style = Stroke(3.dp.toPx()))
        }

        // The crystal image: breathing pulse + gentle float + slow spin (all periodic)
        val pulse = 1f + pulseAmp * MotionClock.wave(clock.pulse) + 0.06f * lvl * listenAmt
        val floatY = r * 0.025f * sin((time / 7.0 * MotionClock.TAU).toFloat())
        val coreSize = (r * 0.95f * pulse).toInt().coerceAtLeast(1)
        withTransform({ rotate(MotionClock.degrees(clock.drift), c); translate(0f, floatY) }) {
            drawImage(core, IntOffset.Zero, IntSize(core.width, core.height),
                IntOffset((c.x - coreSize / 2f).toInt(), (c.y - coreSize / 2f).toInt()), IntSize(coreSize, coreSize),
                alpha = if (offline) 0.55f else 1f, colorFilter = filter)
        }

        // Particles: drift outwards and fade in/out, so the loop has no visible restart
        for (i in 0 until particleCount) {
            val (px, py, pz) = seeds[i]
            val ang = ((px * 360.0 + time * 8.0 * (0.3 + pz)) * PI / 180.0).toFloat()
            val f = MotionClock.frac(py + time * 0.05 * (0.4 + pz))
            val dist = r * (0.35f + f.toFloat() * 0.62f)
            drawCircle(ringColor.copy(alpha = 0.55f * MotionClock.envelope(f) * glow.coerceAtMost(1f)), 1.4.dp.toPx() * (0.6f + pz),
                Offset(c.x + dist * cos(ang), c.y + dist * sin(ang)))
        }

        // Status rings
        if (doneAmt > 0.01f) drawCircle(success.copy(alpha = 0.8f * doneAmt), r * 0.98f, c, style = Stroke(2.5.dp.toPx()))
        if (warnAmt > 0.01f) {
            val beat = 0.5f + 0.5f * sin((time * 0.9 * MotionClock.TAU).toFloat())
            drawCircle(danger.copy(alpha = warnAmt * (0.35f + 0.4f * beat)), r * 0.98f, c, style = Stroke(3.dp.toPx()))
        }
    }
}

/** Three tilted orbital ellipses with a light travelling along each (a trail while executing). */
private fun DrawScope.orbitals(c: Offset, r: Float, color: Color, ring: Double, drift: Double, exec: Float) {
    val rings = listOf(Triple(-18f, 0.96f, 0.30f), Triple(14f, 0.84f, 0.24f), Triple(62f, 0.74f, 0.20f))
    rings.forEachIndexed { i, (tilt, rx, ry) ->
        val w = r * rx; val h = r * ry
        // The wobble uses whole cycles of the drift phase → seamless.
        rotate(tilt + MotionClock.wave(drift * (i + 2) + i / 3.0) * 6f, c) {
            drawOval(color.copy(alpha = 0.55f), Offset(c.x - w, c.y - h), Size(w * 2, h * 2), style = Stroke(1.2.dp.toPx()))
            val dir = if (i % 2 == 0) 1f else -1f
            val base = MotionClock.degrees(ring, 3 + i) * dir
            for (k in 0 until 6) {
                val alpha = if (k == 0) 0.9f else 0.9f / (k + 1) * exec
                if (alpha < 0.02f) continue
                val ang = (base - dir * k * 14f) * (PI.toFloat() / 180f)
                drawCircle(Color.White.copy(alpha = alpha), (2.6f - k * 0.3f).coerceAtLeast(0.8f) * density,
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

private fun DrawScope.hexes(c: Offset, r: Float, color: Color, outerRotation: Float, innerRotation: Float) {
    fun hex(radius: Float, rot: Float, alpha: Float) {
        val p = Path()
        for (i in 0..6) {
            val a = ((rot + i * 60f) * PI / 180f).toFloat()
            val x = c.x + radius * cos(a); val y = c.y + radius * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color.copy(alpha = alpha), style = Stroke(1.4.dp.toPx()))
    }
    hex(r * 0.95f, outerRotation, 0.85f)
    hex(r * 0.8f, innerRotation, 0.5f)
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
