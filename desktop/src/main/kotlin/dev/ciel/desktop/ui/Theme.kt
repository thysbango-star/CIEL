package dev.ciel.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ciel.engine.CielState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Ciel's eight themes — the same colours as on the phone. */
enum class CielTheme(val title: String, val tagline: String, val bg: Long, val bg2: Long, val surface: Long, val panel: Long, val border: Long,
                     val accent: Long, val accent2: Long, val text: Long, val muted: Long, val light: Boolean = false) {
    AZURE_CALM("Azure Calm", "Clean. Elegant. Timeless.", 0xFF061633, 0xFF0E3270, 0xFF0A1F45, 0xB30A2350, 0x8C7FD3FF, 0xFF7FD3FF, 0xFFCFEAFF, 0xFFE6F4FF, 0xFF8FB0D0),
    ETHEREAL_LIGHT("Ethereal Light", "Pure. Serene. Divine.", 0xFFEFF6FF, 0xFFC6DCF6, 0xFFFFFFFF, 0xB3FFFFFF, 0x993E7BD6, 0xFF3E7BD6, 0xFF7FB2F0, 0xFF0D2A4A, 0xFF5E7FA3, true),
    VOID_MINIMAL("Void Minimal", "Simple. Focused. Powerful.", 0xFF010309, 0xFF040A1C, 0xFF050B18, 0xCC060E20, 0x803D8BFF, 0xFF3D8BFF, 0xFF9CC4FF, 0xFFDDE8FF, 0xFF6F84A8),
    TWILIGHT_SAGE("Twilight Sage", "Calm. Mystical. Elegant.", 0xFF100A2A, 0xFF2D2068, 0xFF1A1340, 0xB3221A55, 0x8CA68CFF, 0xFFA68CFF, 0xFFE0D4FF, 0xFFEEE8FF, 0xFFA196C8),
    NATURES_WISDOM("Nature's Wisdom", "Peaceful. Natural. Balanced.", 0xFF031B1C, 0xFF0B4140, 0xFF07292A, 0xB30A3332, 0x8C3EE6C1, 0xFF3EE6C1, 0xFFB4F5E4, 0xFFE2FBF4, 0xFF84B6AC),
    DIVINE_LIGHT("Divine Light", "Sacred. Regal. Pure.", 0xFFF7F3EA, 0xFFDCD6CA, 0xFFFFFDF8, 0xB3FFFDF6, 0x99C9A24A, 0xFFB8902F, 0xFFD9BC6E, 0xFF2A2418, 0xFF7D735E, true),
    STARFALL("Starfall", "Infinite. Boundless. Calm.", 0xFF020611, 0xFF0A1736, 0xFF060E24, 0xB3081430, 0x808FB8FF, 0xFF8FB8FF, 0xFFDCE8FF, 0xFFE8F0FF, 0xFF7F92B8),
    GLASSMORPHISM("Glassmorphism", "Modern. Minimal. Smooth.", 0xFF183A8C, 0xFF5A86E0, 0xFF2A4FA8, 0x33FFFFFF, 0x66FFFFFF, 0xFFDCEBFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFC9D9F5);

    companion object { fun of(name: String) = values().firstOrNull { it.name == name } ?: AZURE_CALM }
}

val LocalCielTheme = staticCompositionLocalOf { CielTheme.AZURE_CALM }

object C {
    val t: CielTheme @Composable get() = LocalCielTheme.current
    val bg @Composable get() = Color(t.bg)
    val panel @Composable get() = Color(t.panel)
    val border @Composable get() = Color(t.border)
    val accent @Composable get() = Color(t.accent)
    val accent2 @Composable get() = Color(t.accent2)
    val text @Composable get() = Color(t.text)
    val muted @Composable get() = Color(t.muted)
    val green = Color(0xFF4FD99A)
    val amber = Color(0xFFFFB347)
    val danger = Color(0xFFE0304A)
}

@Composable
fun CielDesktopTheme(theme: CielTheme, content: @Composable () -> Unit) {
    val scheme = if (theme.light) lightColorScheme(primary = Color(theme.accent), onPrimary = Color.White, background = Color(theme.bg), surface = Color(theme.surface),
        onSurface = Color(theme.text), onBackground = Color(theme.text), surfaceVariant = Color(theme.surface), onSurfaceVariant = Color(theme.muted), outline = Color(theme.border))
    else darkColorScheme(primary = Color(theme.accent), onPrimary = Color(theme.bg), background = Color(theme.bg), surface = Color(theme.surface),
        onSurface = Color(theme.text), onBackground = Color(theme.text), surfaceVariant = Color(theme.surface), onSurfaceVariant = Color(theme.muted), outline = Color(theme.border))
    CompositionLocalProvider(LocalCielTheme provides theme) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** The themed background: a soft vertical gradient. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Brush.verticalGradient(listOf(C.bg, Color(C.t.bg2)))), content = content)
}

/** Translucent panel used throughout (Ciel's "system window"). */
@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().background(C.panel, shape).border(1.dp, accent ?: C.border, shape).padding(16.dp)) {
        if (title != null) Text(title, color = C.accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

private var coreImage: ImageBitmap? = null
private fun core(): ImageBitmap? = coreImage ?: runCatching { useResource("ciel_core.webp") { loadImageBitmap(it) } }.getOrNull()?.also { coreImage = it }

/** Ciel's crystal with orbital rings; motion loops seamlessly (see MotionClock). */
@Composable
fun Crystal(state: CielState, modifier: Modifier = Modifier, animate: Boolean = true) {
    val accent = C.accent
    val clock = remember { MotionClock() }
    var frame by remember { mutableLongStateOf(0L) }
    val ringTarget by rememberUpdatedState(0.04 * when (state) {
        CielState.PROCESSING, CielState.ANALYSING -> 3.5; CielState.RESEARCHING, CielState.EXECUTING -> 2.5; CielState.LISTENING -> 1.6; else -> 1.0
    })
    val pulseTarget by rememberUpdatedState(if (state == CielState.SPEAKING) 1.4 else if (state == CielState.PROCESSING) 0.8 else 0.19)
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now -> if (last != 0L) clock.advance((now - last) / 1e9, ringTarget, pulseTarget); last = now; frame = now }
    }
    val img = remember { core() }
    Canvas(modifier) {
        frame
        val c = center; val r = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.3f), Color.Transparent), c, r), r, c)
        val rings = listOf(Triple(-18f, 0.96f, 0.30f), Triple(14f, 0.84f, 0.24f), Triple(62f, 0.74f, 0.20f))
        rings.forEachIndexed { i, (tilt, rx, ry) ->
            val w = r * rx; val h = r * ry
            rotate(tilt + MotionClock.wave(clock.drift * (i + 2) + i / 3.0) * 6f, c) {
                drawOval(accent.copy(alpha = 0.55f), Offset(c.x - w, c.y - h), Size(w * 2, h * 2), style = Stroke(1.2.dp.toPx()))
                val ang = (MotionClock.degrees(clock.ring, 3 + i) * (if (i % 2 == 0) 1 else -1)) * (PI.toFloat() / 180f)
                drawCircle(Color.White.copy(alpha = 0.9f), 2.6f * density, Offset(c.x + w * cos(ang), c.y + h * sin(ang)))
            }
        }
        val pulse = 1f + 0.02f * MotionClock.wave(clock.pulse)
        val s = (r * 0.95f * pulse).toInt().coerceAtLeast(1)
        if (img != null) withTransform({ rotate(MotionClock.degrees(clock.drift), c); translate(0f, r * 0.025f * sin((clock.time / 7.0 * MotionClock.TAU).toFloat())) }) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((c.x - s / 2f).toInt(), (c.y - s / 2f).toInt()), IntSize(s, s))
        } else drawCircle(Brush.radialGradient(listOf(Color.White, accent, Color.Transparent), c, r * 0.5f), r * 0.5f, c)
    }
}

fun stateLabel(s: CielState) = when (s) {
    CielState.IDLE -> "Standing by"; CielState.LISTENING -> "Listening"; CielState.PROCESSING -> "Thinking"; CielState.ANALYSING -> "Analysing"
    CielState.RESEARCHING -> "Researching"; CielState.SPEAKING -> "Speaking"; CielState.EXECUTING -> "Working"; CielState.COMPLETED -> "Complete"
    CielState.WARNING -> "Notice"; CielState.ERROR -> "Error"
}
