@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ciel.app.ui.screens

import androidx.compose.foundation.layout.FlowRow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.ai.AuthStyle
import dev.ciel.ai.CapabilityTier
import dev.ciel.ai.ConnectionTestResult
import dev.ciel.ai.FreeTier
import dev.ciel.ai.ModelInfo
import dev.ciel.ai.ProtocolKind
import dev.ciel.ai.ProviderConfig
import dev.ciel.ai.ProviderException
import dev.ciel.ai.ProviderPresets
import dev.ciel.app.CielApplication
import dev.ciel.app.security.KeyVault
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.theme.CielColors
import kotlinx.coroutines.launch
import java.util.UUID

/** AI Provider Manager (Part 4): add, key, endpoint, model, test, status, preferred, remove. */
@Composable
fun ProvidersScreen() {
    val graph = CielApplication.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val status = remember { mutableStateOf(mapOf<String, String>()) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CielPanel(title = "AI Providers") {
                Text("Bring your own API keys. Keys are encrypted with the Android Keystore and never leave this phone except to the provider they belong to. " +
                    "Free-tier information is shown only when the provider publishes it; otherwise it is marked unknown.", color = CielColors.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatic model selection", Modifier.weight(1f), color = CielColors.Text)
                    Switch(settings.engine.autoSelect, { v -> scope.launch { graph.settings.update { it.copy(engine = it.engine.copy(autoSelect = v)) } } })
                }
                Button(onClick = { addMenu = true }) { Text("Add provider") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    ProviderPresets.all.forEach { p ->
                        DropdownMenuItem(text = { Text(p.displayName) }, onClick = {
                            addMenu = false
                            editing = ProviderConfig(UUID.randomUUID().toString().take(8), p.displayName, p.protocol, p.baseUrl, p.suggestedModel, p.authStyle,
                                authHeaderName = if (p.protocol == ProtocolKind.GEMINI_GENERATE_CONTENT) "x-goog-api-key" else null,
                                supportsTools = true, supportsVision = p.key == "gemini")
                        })
                    }
                }
            }
        }
        items(settings.providers, key = { it.id }) { p ->
            val preferred = settings.engine.preferredProviderId == p.id
            CielPanel(title = p.displayName + if (preferred) " · preferred" else "") {
                Text("${p.model} · ${p.baseUrl}", color = CielColors.Text, style = MaterialTheme.typography.bodyMedium)
                Text(buildString {
                    append("Tier: ${p.tier.name.lowercase()} · Free tier: ${p.freeTier.name.lowercase()}")
                    p.contextWindow?.let { append(" · Context: $it") }
                    append(" · Tools: ${if (p.supportsTools) "yes" else "no"} · Images: ${if (p.supportsVision) "yes" else "no"}")
                    p.rateLimitNote?.let { append(" · Limits: $it") }
                    append(" · Key: ${graph.vault.hint(KeyVault.providerKeyName(p.id)) ?: if (p.authStyle == AuthStyle.NONE) "not needed" else "missing"}")
                    append(" · Requests today: ${graph.usage.requestsToday(p.id)}")
                }, color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                status.value[p.id]?.let { Text(it, color = if (it.startsWith("✓")) CielColors.Green else CielColors.Amber) }
                FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        status.value = status.value + (p.id to "Testing…")
                        scope.launch {
                            val r = graph.gateway.provider(p).testConnection()
                            status.value = status.value + (p.id to when (r) {
                                is ConnectionTestResult.Success -> "✓ ${r.latencyMillis} ms — ${r.detail}"
                                is ConnectionTestResult.Failure -> "✗ ${r.reason}"
                            })
                        }
                    }) { Text("Test") }
                    OutlinedButton(onClick = { editing = p }) { Text("Edit") }
                    if (!preferred) TextButton(onClick = { scope.launch { graph.settings.update { it.copy(engine = it.engine.copy(preferredProviderId = p.id)) } } }) { Text("Prefer") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(p.enabled, { v -> scope.launch { graph.settings.update { s -> s.copy(providers = s.providers.map { if (it.id == p.id) it.copy(enabled = v) else it }) } } })
                    }
                }
            }
        }
    }

    editing?.let { cfg -> ProviderEditor(cfg, onDismiss = { editing = null }) }
}

@Composable
private fun ProviderEditor(initial: ProviderConfig, onDismiss: () -> Unit) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val exists = graph.settings.current.providers.any { it.id == initial.id }
    var c by remember { mutableStateOf(initial) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CielColors.Deep,
        title = { Text(if (exists) "Edit provider" else "Add provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(c.displayName, { c = c.copy(displayName = it) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(c.baseUrl, { c = c.copy(baseUrl = it.trim()) }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(key, { key = it.trim() }, label = { Text(if (exists && graph.vault.has(KeyVault.providerKeyName(c.id))) "API key (leave blank to keep)" else "API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(c.model, { c = c.copy(model = it.trim()) }, label = { Text("Model") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            message = "Loading models…"
                            try {
                                val k = key.ifBlank { graph.vault.get(KeyVault.providerKeyName(c.id)) ?: "" }
                                models = dev.ciel.ai.ProviderFactory.create(c, k, graph.http).listModels()
                                message = "${models.size} models found"; modelMenu = true
                            } catch (e: ProviderException) { message = e.message }
                        }
                    }) { Text("List") }
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    models.take(150).forEach { m ->
                        DropdownMenuItem(text = { Text(m.id + (if (m.freeTier == FreeTier.FREE) "  · free" else "") + (m.contextWindow?.let { "  · ${it / 1000}k" } ?: "")) }, onClick = {
                            modelMenu = false
                            c = c.copy(model = m.id,
                                contextWindow = m.contextWindow ?: c.contextWindow,
                                supportsVision = m.supportsVision ?: c.supportsVision,
                                supportsTools = m.supportsTools ?: c.supportsTools,
                                freeTier = if (m.freeTier != FreeTier.UNKNOWN) m.freeTier else c.freeTier)
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Protocol: ", color = CielColors.Muted)
                    TextButton(onClick = { c = c.copy(protocol = if (c.protocol == ProtocolKind.OPENAI_CHAT_COMPLETIONS) ProtocolKind.GEMINI_GENERATE_CONTENT else ProtocolKind.OPENAI_CHAT_COMPLETIONS) }) {
                        Text(if (c.protocol == ProtocolKind.OPENAI_CHAT_COMPLETIONS) "OpenAI-compatible" else "Gemini native")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auth: ", color = CielColors.Muted)
                    AuthStyle.values().forEach { a -> TextButton(onClick = { c = c.copy(authStyle = a) }) { Text(a.name.lowercase().replace('_', ' ') + if (c.authStyle == a) " ✓" else "") } }
                }
                if (c.authStyle == AuthStyle.CUSTOM_HEADER) OutlinedTextField(c.authHeaderName ?: "", { c = c.copy(authHeaderName = it.trim().ifBlank { null }) }, label = { Text("Auth header name") }, singleLine = true)
                OutlinedTextField(c.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                    { t -> c = c.copy(extraHeaders = t.lines().mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()) },
                    label = { Text("Extra headers (Name: value per line)") })
                Toggle("Supports tool calling", c.supportsTools) { c = c.copy(supportsTools = it) }
                Toggle("Supports images", c.supportsVision) { c = c.copy(supportsVision = it) }
                Toggle("Stream responses", c.supportsStreaming) { c = c.copy(supportsStreaming = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tier: ", color = CielColors.Muted)
                    CapabilityTier.values().forEach { t -> TextButton(onClick = { c = c.copy(tier = t) }) { Text(t.name.lowercase() + if (c.tier == t) " ✓" else "") } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Free tier: ", color = CielColors.Muted)
                    FreeTier.values().forEach { t -> TextButton(onClick = { c = c.copy(freeTier = t) }) { Text(t.name.lowercase() + if (c.freeTier == t) " ✓" else "") } }
                }
                OutlinedTextField(c.contextWindow?.toString() ?: "", { c = c.copy(contextWindow = it.toIntOrNull()) }, label = { Text("Context window (tokens)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(c.dailyRequestLimit?.toString() ?: "", { c = c.copy(dailyRequestLimit = it.toIntOrNull()) }, label = { Text("Daily request limit (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(c.rateLimitNote ?: "", { c = c.copy(rateLimitNote = it.ifBlank { null }) }, label = { Text("Rate-limit note (from the provider's site)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(c.dataPolicyAcknowledged, { c = c.copy(dataPolicyAcknowledged = it) })
                    Text("I understand that what I send to Ciel (and screen content during automation) is processed by this provider under its own data policy.", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                message?.let { Text(it, color = CielColors.Amber) }
                if (exists) TextButton(onClick = { confirmDelete = true }) { Text("Remove provider and delete its key", color = CielColors.Danger) }
            }
        },
        confirmButton = {
            Button(enabled = c.dataPolicyAcknowledged && c.baseUrl.startsWith("http") && c.model.isNotBlank(), onClick = {
                scope.launch {
                    if (key.isNotBlank()) graph.vault.put(KeyVault.providerKeyName(c.id), key)
                    graph.settings.update { s ->
                        val list = if (exists) s.providers.map { if (it.id == c.id) c else it } else s.providers + c
                        s.copy(providers = list, engine = if (s.engine.preferredProviderId == null) s.engine.copy(preferredProviderId = c.id) else s.engine)
                    }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${c.displayName}?") },
        text = { Text("The encrypted API key is deleted from this phone.") },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                graph.vault.delete(KeyVault.providerKeyName(c.id))
                graph.settings.update { s -> s.copy(providers = s.providers.filter { it.id != c.id },
                    engine = if (s.engine.preferredProviderId == c.id) s.engine.copy(preferredProviderId = null) else s.engine) }
                confirmDelete = false; onDismiss()
            }
        }) { Text("Remove", color = CielColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = CielColors.Text)
        Spacer(Modifier.width(8.dp))
        Switch(value, onChange)
    }
}
