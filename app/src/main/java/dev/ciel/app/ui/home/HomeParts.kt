package dev.ciel.app.ui.home

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ciel.ai.ImageInput
import dev.ciel.app.ToolActivity
import dev.ciel.app.data.MessageEntity
import dev.ciel.app.design.CielDesign
import dev.ciel.app.ui.components.SystemNotice
import dev.ciel.app.ui.components.panelShape
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.ui.theme.LocalLook
import dev.ciel.engine.CielState
import dev.ciel.engine.ReasoningMode
import dev.ciel.task.TaskState
import java.io.ByteArrayOutputStream

/** Everything a home layout shows. Built from live state, or from sample data for previews. */
data class HomeModel(
    val state: CielState,
    val stateLabel: String?,
    val level: Float,
    val partial: String?,
    val listenError: String?,
    val messages: List<MessageEntity>,
    val streaming: String,
    val activity: List<ToolActivity>,
    val online: Boolean,
    val providerLine: String?,
    val connected: Boolean,
    val activeTasks: List<TaskState>,
    val recentTasks: List<TaskState>,
    val heyCielOn: Boolean,
    val wakeStatus: String,
    val lastReply: String?,
)

class HomeActions(
    val onMic: () -> Unit,
    val onSend: (text: String, image: ImageInput?, mode: ReasoningMode) -> Unit,
    val onStop: () -> Unit,
    val onTapVisual: () -> Unit,
    val onConnect: () -> Unit,
    val onEnableHeyCiel: () -> Unit,
    val onOpen: (route: String) -> Unit,
)

fun stateText(s: CielState) = when (s) {
    CielState.IDLE -> "Standing by"
    CielState.LISTENING -> "Listening"
    CielState.PROCESSING -> "Thinking"
    CielState.ANALYSING -> "Analysing"
    CielState.RESEARCHING -> "Researching"
    CielState.SPEAKING -> "Speaking"
    CielState.EXECUTING -> "Executing"
    CielState.COMPLETED -> "Complete"
    CielState.WARNING -> "Notice"
    CielState.ERROR -> "Error"
}

/** Quick skills: tapping one sends a ready-made request to Ciel. */
data class QuickSkill(val label: String, val prompt: String, val mode: ReasoningMode = ReasoningMode.CONVERSE)

val quickSkills = listOf(
    QuickSkill("Research", "Research this for me: ", ReasoningMode.RESEARCH),
    QuickSkill("Analyse", "Analyse this: ", ReasoningMode.ANALYSE),
    QuickSkill("My tasks", "What tasks and reminders do I have?"),
    QuickSkill("Read my messages", "Summarise my unread messages."),
    QuickSkill("This screen", "What's on my screen right now?"),
    QuickSkill("Game help", "Help me with the game I'm playing."),
)

/** Short system notices: missing connection, Hey Ciel status, live skill activations. */
@Composable
fun Notices(m: HomeModel, actions: HomeActions, max: Int = 3) {
    val items = mutableListOf<@Composable () -> Unit>()
    if (!m.connected) items += { Box(Modifier.clickable { actions.onConnect() }) { SystemNotice("Notice", "No AI connected — tap to connect (one step)", CielColors.Amber) } }
    if (!m.heyCielOn) items += { Box(Modifier.clickable { actions.onEnableHeyCiel() }) { SystemNotice("Voice", "Tap to enable \"Hey Ciel\"", CielColors.Cyan) } }
    m.activity.takeLast(2).forEach { a ->
        items += {
            val color = when { !a.done -> CielColors.Cyan; a.success == true -> CielColors.Green; else -> CielColors.Amber }
            val status = when { !a.done -> "activated"; a.success == true && a.verified == true -> "complete · verified"; a.success == true -> "complete"; else -> "failed" }
            SystemNotice("Skill: ${a.skill}", "${a.tool} $status", color)
        }
    }
    if (m.listenError != null) items += { SystemNotice("Notice", m.listenError, CielColors.Amber) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { items.take(max).forEach { it() } }
}

/**
 * Conversation list anchored to the bottom (reverseLayout): the newest message stays visible when
 * the keyboard opens or a reply streams in. If the user scrolls up to read, new messages do not
 * pull them down; a "latest" button appears instead.
 */
@Composable
fun MessageList(m: HomeModel, modifier: Modifier, console: Boolean = false, emptyText: String? = null) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atLatest by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 120 } }
    val newest = m.messages.lastOrNull()?.id
    LaunchedEffect(newest) {
        // A message the user just sent always brings them to the bottom; replies only if already there.
        val mine = m.messages.lastOrNull()?.role == "user"
        if (mine || listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize(), state = listState, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(if (console) 4.dp else 8.dp, Alignment.Bottom),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            if (m.streaming.isNotEmpty()) item(key = "live") {
                val live = MessageEntity(-1, "", "ciel", m.streaming)
                if (console) ConsoleLine(live) else Bubble(live)
            }
            items(m.messages.asReversed(), key = { it.id }) {
                Box(Modifier.animateItem(fadeInSpec = tween(260), placementSpec = tween(260), fadeOutSpec = tween(160))) {
                    if (console) ConsoleLine(it) else Bubble(it)
                }
            }
            if (m.messages.isEmpty() && m.streaming.isEmpty() && emptyText != null) item(key = "empty") {
                Text(emptyText, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            }
        }
        AnimatedVisibility(!atLatest, Modifier.align(Alignment.BottomEnd).padding(8.dp), enter = fadeIn(tween(200)) + scaleIn(tween(200), 0.8f), exit = fadeOut(tween(150)) + scaleOut(tween(150), 0.8f)) {
            SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }, containerColor = CielColors.Panel, contentColor = CielColors.Cyan) {
                Icon(Icons.Filled.KeyboardArrowDown, "Jump to the latest message")
            }
        }
    }
}

@Composable
fun Bubble(m: MessageEntity) {
    val mine = m.role == "user"
    val shape = panelShape()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .background(if (mine) CielColors.Cyan.copy(alpha = 0.12f) else CielColors.Panel, shape)
                .border(1.dp, if (mine) CielColors.Cyan.copy(alpha = 0.3f) else CielColors.PanelBorder.copy(alpha = 0.5f), shape)
                .padding(12.dp)
        ) {
            if (!mine) Text(if (m.offline) "CIEL · OFFLINE" else "CIEL", style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan)
            SelectionContainer { Text(m.text, color = CielColors.Text, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

/** Raphael's log style: one line per entry, monospace prefixes. */
@Composable
fun ConsoleLine(m: MessageEntity) {
    val mine = m.role == "user"
    SelectionContainer {
        Text((if (mine) "> " else "CIEL: ") + m.text, color = if (mine) CielColors.Ice else CielColors.Text,
            fontFamily = if (mine) FontFamily.Monospace else FontFamily.Default, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun QuickSkillRow(actions: HomeActions, modifier: Modifier = Modifier) {
    var pending by remember { mutableStateOf<QuickSkill?>(null) }
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(quickSkills) { q ->
            Text(q.label, color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.border(1.dp, CielColors.PanelBorder, panelShape()).clickable {
                    if (q.prompt.endsWith(": ")) pending = q else actions.onSend(q.prompt, null, q.mode)
                }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    pending?.let { q -> PromptDialog(q, onDismiss = { pending = null }) { text -> actions.onSend(q.prompt + text, null, q.mode); pending = null } }
}

@Composable
private fun PromptDialog(q: QuickSkill, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, title = { Text(q.label) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), maxLines = 6, placeholder = { Text("What should Ciel ${q.label.lowercase()}?") }) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun RecentTasks(m: HomeModel, actions: HomeActions, max: Int = 3) {
    val list = (m.activeTasks + m.recentTasks.filter { r -> m.activeTasks.none { it.id == r.id } }).take(max)
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.forEach { t ->
            Row(Modifier.fillMaxWidth().clickable { actions.onOpen("tasks") }, verticalAlignment = Alignment.CenterVertically) {
                val color = when { t.isActive -> CielColors.Cyan; t.status.name == "COMPLETED" -> CielColors.Green; else -> CielColors.Amber }
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text("  ${t.objective.take(60)}", Modifier.weight(1f), color = CielColors.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(t.status.name.lowercase(), color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Text input with image attach and reasoning mode. Grows with the text up to [MAX_INPUT_HEIGHT],
 * then scrolls inside itself; the keyboard's action key sends. Unsent text survives the keyboard
 * closing, rotation and recomposition (rememberSaveable).
 */
@Composable
fun InputBar(actions: HomeActions, modifier: Modifier = Modifier, bigMic: Boolean = false, placeholder: String = "Speak or type to Ciel…", autoFocus: Boolean = false) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ReasoningMode.CONVERSE) }
    var modeMenu by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ImageInput?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) image = runCatching {
            val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            val scale = 1280f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            ImageInput("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }.getOrNull()
    }
    val send = {
        if (input.isNotBlank()) {
            actions.onSend(input.trim(), image, mode)
            input = ""; image = null; mode = ReasoningMode.CONVERSE
        }
    }
    val raphael = LocalLook.current.design == CielDesign.RAPHAEL
    Column(modifier.fillMaxWidth().animateContentSize(tween(180))) {
        image?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Image attached", color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { image = null }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Box {
                TextButton(onClick = { modeMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(mode.name.take(3), style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan)
                }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    ReasoningMode.values().forEach { md ->
                        DropdownMenuItem(text = { Text(md.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { mode = md; modeMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = MAX_INPUT_HEIGHT).focusRequester(focus),
                placeholder = { Text(if (raphael) "> command" else placeholder, maxLines = 1) },
                minLines = 1, maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CielColors.Cyan, unfocusedBorderColor = CielColors.PanelBorder),
                shape = if (raphael) RoundedCornerShape(4.dp) else RoundedCornerShape(24.dp),
            )
            Crossfade(input.isBlank(), animationSpec = tween(160), label = "send") { blank ->
                Row {
                    if (blank) {
                        IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Filled.Image, "Attach image", tint = CielColors.Muted)
                        }
                        if (!bigMic) IconButton(onClick = actions.onMic) { Icon(Icons.Filled.Mic, "Speak", tint = CielColors.Cyan) }
                    } else {
                        IconButton(onClick = send) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = CielColors.Cyan) }
                    }
                }
            }
        }
    }
}

/** Long messages stop growing the field at this height and scroll inside it. */
val MAX_INPUT_HEIGHT = 168.dp

/** Large round microphone button. While listening, soft halos expand and fade (seamless loop). */
@Composable
fun BigMic(state: CielState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = state == CielState.LISTENING
    val bg by androidx.compose.animation.animateColorAsState(if (active) CielColors.Cyan else CielColors.Cyan.copy(alpha = 0.15f), tween(250), label = "mic")
    val haloAmt by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0f, tween(300), label = "halo")
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic")
    val f by loop.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.LinearEasing)), label = "f")
    val halo = CielColors.Cyan
    Box(modifier.size(96.dp).drawBehind {
        if (haloAmt > 0.01f) for (k in 0 until 2) {
            val p = (f + k * 0.5f) % 1f
            // Fades in and out over its cycle, so the restart is invisible.
            val alpha = 0.35f * haloAmt * kotlin.math.sin(p * Math.PI).toFloat()
            drawCircle(halo.copy(alpha = alpha), radius = size.minDimension * (0.38f + 0.12f * p), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(72.dp).background(bg, CircleShape)
                .border(1.5.dp, CielColors.Cyan, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Mic, "Speak to Ciel", tint = if (active) CielColors.Void else CielColors.Cyan, modifier = Modifier.size(32.dp)) }
    }
}
