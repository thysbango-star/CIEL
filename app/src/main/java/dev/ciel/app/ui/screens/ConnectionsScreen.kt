@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ciel.app.ui.screens

import androidx.compose.foundation.layout.FlowRow

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.ai.ConnectResult
import dev.ciel.ai.ConnectionTestResult
import dev.ciel.ai.ProviderPresets
import dev.ciel.app.CielApplication
import dev.ciel.app.security.KeyVault
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.theme.CielColors
import kotlinx.coroutines.launch
import java.util.UUID

/** Provider choices shown to the user — names only, no technical settings. */
private val choices = listOf(
    null to "Not sure — detect it",
    "gemini" to "Google Gemini",
    "openai" to "OpenAI",
    "openrouter" to "OpenRouter",
    "groq" to "Groq",
    "nvidia" to "NVIDIA",
    "ollama" to "Ollama (my own AI)",
    "nim" to "NVIDIA NIM (self-hosted)",
    "custom" to "Other / my own server",
)

/**
 * Connections: pick a provider (or let Ciel detect it), paste the key, press Connect.
 * Ciel validates the key, finds the models, picks fast/reasoning/vision models and tests them.
 */
@Composable
fun ConnectionsScreen(onDeveloper: () -> Unit) {
    val graph = CielApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(settings.providers.isEmpty()) }
    var preset by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ConnectResult?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val checks = remember { mutableStateOf(mapOf<String, String>()) }

    val connections = settings.providers.groupBy { it.id.substringBefore(':') }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connections", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)

        connections.forEach { (connId, configs) ->
            val first = configs.first()
            CielPanel(title = first.displayName.substringBefore(" · ")) {
                Text(configs.joinToString("\n") { "• ${it.displayName.substringAfter(" · ", "model")}: ${it.model}" }, color = CielColors.Text, style = MaterialTheme.typography.bodyMedium)
                Text(if (configs.any { it.enabled }) "Connected" else "Paused", color = if (configs.any { it.enabled }) CielColors.Green else CielColors.Amber, style = MaterialTheme.typography.labelSmall)
                checks.value[connId]?.let { Text(it, color = if (it.startsWith("✓")) CielColors.Green else CielColors.Amber) }
                FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        checks.value = checks.value + (connId to "Checking…")
                        scope.launch {
                            val r = graph.gateway.provider(first).testConnection()
                            checks.value = checks.value + (connId to when (r) {
                                is ConnectionTestResult.Success -> "✓ Working (${r.latencyMillis} ms)"
                                is ConnectionTestResult.Failure -> "✗ ${r.reason}"
                            })
                        }
                    }) { Text("Check") }
                    TextButton(onClick = { confirmRemove = connId }) { Text("Disconnect", color = CielColors.Danger) }
                }
            }
        }

        if (!adding) Button(onClick = { adding = true; result = null; status = null }, modifier = Modifier.fillMaxWidth()) {
            Text(if (connections.isEmpty()) "Connect an AI provider" else "Add another provider")
        } else CielPanel(title = "Connect an AI provider") {
            Text("Choose a provider, paste your API key, press Connect. Ciel sets up everything else.", color = CielColors.Muted)
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.chunked(2).forEach { row ->
                    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (k, label) -> FilterChip(selected = preset == k, onClick = { preset = k; result = null }, label = { Text(label) }) }
                    }
                }
            }
            val keyUrl = preset?.let { ProviderPresets.byKey(it)?.keyUrl }?.takeIf { it.isNotBlank() }
            if (keyUrl != null) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl))) }) { Text("Get a key from ${choices.first { it.first == preset }.second} ↗") }
            if (preset == "ollama") OllamaFinder(customUrl) { customUrl = it }
            if (preset == "custom" || preset == "nim") OutlinedTextField(customUrl, { customUrl = it.trim() },
                label = { Text(if (preset == "nim") "NIM address (e.g. http://192.168.1.10:8000/v1)" else "Server address (e.g. http://192.168.1.10:1234/v1)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (preset != "ollama") OutlinedTextField(
                key, { key = it.trim() }, label = { Text(if (preset == "custom" || preset == "nim") "API key (optional)" else "API key") }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showKey) "Hide key" else "Show key") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Your key is encrypted on this phone and sent only to the provider it belongs to. What you ask Ciel is processed by that provider under its own privacy policy.",
                color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && (key.isNotBlank() || ((preset == "custom" || preset == "nim" || preset == "ollama") && customUrl.isNotBlank())), onClick = {
                    busy = true; result = null
                    scope.launch {
                        val connId = UUID.randomUUID().toString().take(8)
                        // Never let a connection problem crash the app: every error becomes a readable notice.
                        val r = try {
                            graph.connectionWizard.connect(key, connId, preset, customUrl) { s -> status = s }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            ConnectResult.Failure("Connection failed.", e.message ?: e::class.simpleName ?: "Unknown error")
                        }
                        if (r is ConnectResult.Success) {
                            r.configs.forEach { c -> if (key.isNotBlank()) graph.vault.put(KeyVault.providerKeyName(c.id), key) }
                            graph.settings.update { s ->
                                s.copy(providers = s.providers + r.configs,
                                    engine = s.engine.copy(preferredProviderId = s.engine.preferredProviderId ?: r.configs.first().id, autoSelect = true))
                            }
                            key = ""; adding = false
                        }
                        result = r; busy = false; status = null
                    }
                }) { Text("Connect") }
                if (busy) { CircularProgressIndicator(Modifier.padding(start = 12.dp)); }
                if (connections.isNotEmpty()) TextButton(onClick = { adding = false }) { Text("Cancel") }
            }
            status?.let { Text(it, color = CielColors.Cyan) }
        }

        when (val r = result) {
            is ConnectResult.Success -> CielPanel(title = "Report", accent = CielColors.Green) {
                Text(r.message, color = CielColors.Green, style = MaterialTheme.typography.titleMedium)
                r.details.forEach { Text(it, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium) }
            }
            is ConnectResult.NeedsProvider -> CielPanel(title = "Notice", accent = CielColors.Amber) {
                Text(r.reason, color = CielColors.Text)
                Text("Pick the provider above, then press Connect again.", color = CielColors.Muted)
            }
            is ConnectResult.Failure -> CielPanel(title = "Notice", accent = CielColors.Danger) {
                Text(r.message, color = CielColors.Text, style = MaterialTheme.typography.titleMedium)
                Text("How to fix: ${r.howToFix}", color = CielColors.Muted)
            }
            null -> Unit
        }

        if (settings.developerMode) TextButton(onClick = onDeveloper) { Text("Developer: edit models, URLs and headers", color = CielColors.Muted) }
        else Text("Advanced provider settings appear here when Developer mode is on (Settings → Developer).", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
    }

    confirmRemove?.let { connId ->
        AlertDialog(onDismissRequest = { confirmRemove = null }, title = { Text("Disconnect?") },
            text = { Text("The encrypted key is deleted from this phone.") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    val ids = graph.settings.current.providers.filter { it.id.substringBefore(':') == connId }.map { it.id }
                    ids.forEach { graph.vault.delete(KeyVault.providerKeyName(it)) }
                    graph.settings.update { s -> s.copy(providers = s.providers.filter { it.id !in ids },
                        engine = if (s.engine.preferredProviderId in ids) s.engine.copy(preferredProviderId = null) else s.engine) }
                    confirmRemove = null
                }
            }) { Text("Disconnect", color = CielColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } })
    }
}

/** Ollama: search this phone and the Wi-Fi network for a server, or type its address. */
@Composable
private fun OllamaFinder(address: String, onAddress: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ollama runs on your PC (or on this phone). No key needed.", color = CielColors.Muted)
        Button(enabled = !scanning, onClick = {
            scanning = true; found = null
            scope.launch {
                val client = dev.ciel.ai.OllamaClient(CielApplication.graph.http)
                val hosts = dev.ciel.ai.OllamaDiscovery.candidates(phoneIpv4(context), listOf(address))
                found = runCatching {
                    dev.ciel.ai.OllamaDiscovery.scan(client, hosts) { d, t -> progress = "Searching… $d / $t" }
                }.getOrDefault(emptyList())
                found?.firstOrNull()?.let(onAddress)
                scanning = false; progress = ""
            }
        }) { Text(if (scanning) "Searching your network…" else "Find Ollama on my Wi-Fi") }
        if (scanning) Text(progress, color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall)
        found?.let { list ->
            if (list.isEmpty()) Text("No Ollama server answered. On the PC set OLLAMA_HOST=0.0.0.0, restart Ollama, allow port 11434 in the firewall, keep both on the same Wi-Fi — or type the PC's address below.",
                color = CielColors.Amber, style = MaterialTheme.typography.bodyMedium)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { h -> FilterChip(selected = address == h, onClick = { onAddress(h) }, label = { Text(h.removePrefix("http://")) }) }
            }
        }
        OutlinedTextField(address, { onAddress(it.trim()) }, label = { Text("PC address (e.g. 192.168.1.10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** This phone's IPv4 address on the current network, used to search the local subnet. */
private fun phoneIpv4(context: android.content.Context): String? = runCatching {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()
