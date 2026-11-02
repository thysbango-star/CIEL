package dev.ciel.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout rules shared by every screen (see docs/UI_GUIDELINES.md):
 *  - Window insets are applied ONCE, in the app shell (status/navigation bars, cutouts, keyboard).
 *    Screens never add imePadding()/systemBarsPadding() themselves.
 *  - Content is width-limited and centred on large screens and in landscape.
 *  - Content that can exceed the screen scrolls; text wraps instead of shrinking.
 */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    /** Comfortable reading width; wider screens centre the content. */
    val maxContent = 680.dp
}

/** True while the on-screen keyboard is showing (any keyboard, any height). */
@Composable
fun imeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

/** Centres a screen's content and caps its width on tablets, foldables and landscape. */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxSize().widthIn(max = Spacing.maxContent).wrapContentWidth(Alignment.CenterHorizontally)) { content() }
    }
}

/**
 * Renders [content] laid out at a fixed design size ([designWidth] × [designHeight]) and scales
 * it uniformly to fit the space available, keeping the aspect ratio. The child is placed at the
 * top-left of the scaled box, so nothing is shifted or cut off (unlike requiredSize + scale).
 */
@Composable
fun ScaledPreview(
    modifier: Modifier = Modifier,
    designWidth: Dp = 360.dp,
    designHeight: Dp = 720.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val scale = minOf(
            if (constraints.hasBoundedWidth) maxWidth / designWidth else 1f,
            if (constraints.hasBoundedHeight) maxHeight / designHeight else 1f,
        ).coerceAtLeast(0.05f)
        Box(Modifier.size(designWidth * scale, designHeight * scale).clip(shape)) {
            Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, c ->
                val fixed = Constraints.fixed(designWidth.roundToPx(), designHeight.roundToPx())
                val placeables = measurables.map { it.measure(fixed) }
                layout(c.maxWidth, c.maxHeight) {
                    placeables.forEach { p ->
                        p.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
                    }
                }
            }
        }
    }
}

/** Full-width helper for content placed inside [ScreenFrame]. */
fun Modifier.contentWidth(): Modifier = this.fillMaxWidth()
