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

/** The central visualisation: Ciel's crystal, with rings matching the active layout. */
@Composable
fun CielVisual(state: CielState, level: Float, modifier: Modifier = Modifier, offline: Boolean = false, progress: Float? = null) {
    CrystalCore(state, level, modifier, offline = offline, progress = progress)
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
