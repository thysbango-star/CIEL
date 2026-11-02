package dev.ciel.app.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.ai.ImageInput
import dev.ciel.app.CielApplication
import dev.ciel.app.data.MessageEntity
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.MagicCircle
import dev.ciel.app.ui.components.SystemNotice
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.voice.ListenState
import dev.ciel.engine.CielState
import dev.ciel.engine.ReasoningMode
import java.io.ByteArrayOutputStream

private fun stateText(s: CielState) = when (s) {
    CielState.IDLE -> "Standing by"
    CielState.LISTENING -> "Listening"
    CielState.PROCESSING -> "Processing"
    CielState.ANALYSING -> "Analysing"
    CielState.RESEARCHING -> "Researching"
    CielState.SPEAKING -> "Speaking"
    CielState.EXECUTING -> "Executing"
    CielState.COMPLETED -> "Complete"
    CielState.WARNING -> "Notice"
    CielState.ERROR -> "Error"
}

/** Mode A — full-screen Ciel. Landscape puts the circle beside the conversation. */
@Composable
fun HomeScreen(onListen: () -> Unit) {
    val graph = CielApplication.graph
    val conv = graph.conversation
    val state by conv.state.collectAsStateWithLifecycle()
    val label by conv.stateLabel.collectAsStateWithLifecycle()
    val listen by graph.speech.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val tasks by graph.tasks.tasks.collectAsStateWithLifecycle()
    val online by graph.network.online.collectAsStateWithLifecycle()
    val provider by conv.provider.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activeTasks = tasks.filter { it.isActive }
    val level = (listen as? ListenState.Listening)?.level ?: 0f

    val header: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
            MagicCircle(state, level, settings.reducedMotion,
                Modifier.size(if (landscape) 200.dp else 190.dp).clickable { if (state == CielState.SPEAKING) graph.voice.stop() else onListen() }
                    .semantics { contentDescription = "Ciel, ${stateText(state)}. Tap to speak." })
            Text(stateText(state).uppercase() + (label?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = CielColors.forState(state))
            Text(
                (if (online) "Online" else "Offline — basic commands only") + (provider?.let { " · $it" } ?: if (settings.providers.isEmpty()) " · no AI provider configured" else ""),
                style = MaterialTheme.typography.labelSmall, color = CielColors.Muted,
            )
            if (activeTasks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = { graph.emergencyStop.trigger("in-app stop") }, colors = ButtonDefaults.buttonColors(containerColor = CielColors.Danger)) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("EMERGENCY STOP (${activeTasks.size} active)")
                }
            }
        }
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().padding(8.dp)) {
            header(Modifier.width(240.dp).fillMaxHeight().padding(top = 16.dp))
            Conversation(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            header(Modifier.fillMaxWidth().padding(top = 12.dp))
            Conversation(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun Conversation(modifier: Modifier) {
    val graph = CielApplication.graph
    val conv = graph.conversation
    val messages by conv.messages.collectAsStateWithLifecycle(emptyList())
    val streaming by conv.streaming.collectAsStateWithLifecycle()
    val activity by conv.activity.collectAsStateWithLifecycle()
    val listen by graph.speech.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ReasoningMode.CONVERSE) }
    var modeMenu by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ImageInput?>(null) }

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

    LaunchedEffect(messages.size, streaming.length / 80, activity.size) {
        val count = messages.size + (if (streaming.isNotEmpty() || activity.isNotEmpty()) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier.padding(horizontal = 12.dp).imePadding()) {
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && streaming.isEmpty()) item {
                CielPanel(title = "Report") {
                    Text("I am Ciel. Speak or type, and I will analyse, research, remember and act on your behalf.\n\n" +
                        "To begin: Settings → AI Providers to add a model. Basic commands (open apps, calculations, reminders, \"stop\") work offline.",
                        color = CielColors.Text)
                }
            }
            items(messages, key = { it.id }) { MessageBubble(it) }
            if (streaming.isNotEmpty() || activity.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    activity.forEach { a ->
                        val color = when { !a.done -> CielColors.Cyan; a.success == true -> CielColors.Green; else -> CielColors.Amber }
                        val status = when { !a.done -> "activated"; a.success == true && a.verified == true -> "complete · verified"; a.success == true -> "complete"; else -> "failed" }
                        SystemNotice("Skill: ${a.skill}", "${a.tool} $status", color)
                    }
                    if (streaming.isNotEmpty()) MessageBubble(MessageEntity(-1, "", "ciel", streaming))
                }
            }
        }
        val l = listen
        if (l is ListenState.Listening && l.partial.isNotEmpty()) Text("“${l.partial}”", color = CielColors.Ice, modifier = Modifier.padding(4.dp))
        if (l is ListenState.Error) Text(l.message, color = CielColors.Amber, modifier = Modifier.padding(4.dp))
        image?.let { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Image attached", color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { image = null }) { Text("Remove") }
        } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Box {
                TextButton(onClick = { modeMenu = true }) { Text(mode.name.take(3), style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan) }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    ReasoningMode.values().forEach { m ->
                        DropdownMenuItem(text = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { mode = m; modeMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Speak to Ciel…") }, maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CielColors.Cyan, unfocusedBorderColor = CielColors.PanelBorder),
                shape = RoundedCornerShape(18.dp),
            )
            IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.Image, "Attach image", tint = CielColors.Muted)
            }
            if (input.isBlank()) {
                IconButton(onClick = { (context as? dev.ciel.app.ui.MainActivity)?.listen() }) { Icon(Icons.Filled.Mic, "Speak", tint = CielColors.Cyan) }
            } else {
                IconButton(onClick = {
                    conv.send(input.trim(), listOfNotNull(image), mode)
                    input = ""; image = null; mode = ReasoningMode.CONVERSE
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = CielColors.Cyan) }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageEntity) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .background(if (mine) CielColors.Cyan.copy(alpha = 0.12f) else CielColors.Panel, RoundedCornerShape(14.dp))
                .border(1.dp, if (mine) CielColors.Cyan.copy(alpha = 0.3f) else CielColors.PanelBorder.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            if (!mine) Text(if (m.offline) "CIEL · OFFLINE" else "CIEL", style = MaterialTheme.typography.labelSmall, color = CielColors.Cyan)
            SelectionContainer { Text(m.text, color = CielColors.Text, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
