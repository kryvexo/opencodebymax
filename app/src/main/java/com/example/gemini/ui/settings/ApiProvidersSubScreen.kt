package com.example.gemini.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.gemini.data.providers.ApiProviderKind
import com.example.gemini.data.providers.ApiProviderProfile
import com.example.gemini.data.providers.ApiProviderRegistry
import com.example.gemini.data.providers.ProviderApiKeyStore
import com.example.gemini.data.remote.OpenAiCompatibleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ApiProvidersSubScreen(
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val profiles by ApiProviderRegistry.profiles.collectAsState()
    val defaultId by ApiProviderRegistry.defaultProviderId.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ApiProviderProfile?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { ApiProviderRegistry.ensureLoaded(context) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Choose the default for new chats, then add a provider profile. API keys are stored separately from profile data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Gemini managed agent", fontWeight = FontWeight.SemiBold)
                    Text("Official Gemini developer API · configured in Gemini API Key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RadioButton(selected = defaultId == ApiProviderProfile.GEMINI_MANAGED_ID, onClick = {
                    ApiProviderRegistry.setDefaultProvider(context, ApiProviderProfile.GEMINI_MANAGED_ID)
                    status = "Default provider updated for new chats."
                })
            }
        }

        profiles.forEach { profile ->
            val hasKey = remember(profile.id, profiles) { ProviderApiKeyStore.hasKey(context, profile.id) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = cardBorder
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${profile.modelIds.size} model${if (profile.modelIds.size == 1) "" else "s"} · ${if (hasKey) "API key saved" else "API key needed"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(profile.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    RadioButton(selected = defaultId == profile.id, onClick = {
                        ApiProviderRegistry.setDefaultProvider(context, profile.id)
                        status = "${profile.name} is the default for new chats."
                    })
                    IconButton(onClick = { editing = profile }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${profile.name}") }
                    IconButton(onClick = {
                        ApiProviderRegistry.delete(context, profile.id)
                        status = "${profile.name} removed from this device."
                    }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove ${profile.name}") }
                }
            }
        }

        Text("Add a provider", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        ApiProviderKind.entries.forEach { kind ->
            val preset = ApiProviderProfile.preset(kind)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val existing = profiles.firstOrNull { it.id == preset.id }
                    editing = existing ?: preset
                }
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("  ${when (kind) {
                    ApiProviderKind.OPENCODE_ZEN -> "OpenCode Zen"
                    ApiProviderKind.GROK -> "xAI Grok"
                    ApiProviderKind.OPENROUTER -> "OpenRouter"
                    ApiProviderKind.CUSTOM -> "Custom OpenAI-compatible API"
                }}")
            }
        }

        if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(
            "MAXCODE sends requests directly from this device to the provider you select. Do not share your API key. Android Keystore encryption protects saved keys at rest, but a rooted or compromised device can still expose credentials.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    editing?.let { profile ->
        ProviderProfileEditor(
            initial = profile,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onDismiss = { editing = null },
            onSave = { updated, key ->
                ApiProviderRegistry.save(context, updated, key)
                status = "${updated.name} saved securely on this device."
                editing = null
            }
        )
    }
}

@Composable
private fun ProviderProfileEditor(
    initial: ApiProviderProfile,
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: BorderStroke,
    onDismiss: () -> Unit,
    onSave: (ApiProviderProfile, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var baseUrl by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var modelText by remember(initial.id) { mutableStateOf(initial.modelIds.joinToString("\n")) }
    var defaultModel by remember(initial.id) { mutableStateOf(initial.defaultModelId) }
    var key by remember(initial.id) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.modelIds.isEmpty()) "Configure ${initial.name}" else "Edit ${initial.name}") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Provider name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; message = null },
                    label = { Text("API key") },
                    placeholder = { Text("Paste key · leave blank to keep saved key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(Icons.Outlined.Key, contentDescription = "Toggle API key visibility") } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    modelText,
                    { modelText = it },
                    label = { Text("Model IDs · one per line") },
                    placeholder = { Text("gpt-4o-mini\nprovider/model-id") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(defaultModel, { defaultModel = it }, label = { Text("Default model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val candidate = key.trim().takeIf(String::isNotBlank)
                                        ?: ProviderApiKeyStore.read(context, initial.id)
                                        ?: error("Enter an API key before fetching models.")
                                    val draft = initial.copy(name = name, baseUrl = baseUrl)
                                    val ids = OpenAiCompatibleApi().fetchModelIds(draft, candidate)
                                    Triple(ids, candidate, draft)
                                }
                            }
                            busy = false
                            if (result.isSuccess) {
                                val (ids, candidate, draft) = result.getOrThrow()
                                modelText = ids.joinToString("\n")
                                if (defaultModel.isBlank()) defaultModel = ids.firstOrNull().orEmpty()
                                key = candidate
                                message = "Fetched ${ids.size} models. Save to keep this profile."
                                ApiProviderRegistry.save(context, draft.copy(modelIds = ids, defaultModelId = defaultModel.ifBlank { ids.first() }), candidate)
                            } else {
                                message = result.exceptionOrNull()?.message ?: "Could not fetch models."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text(if (busy) "  Fetching…" else "  Fetch models")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    val models = modelText.lines().map(String::trim).filter(String::isNotBlank).distinct()
                    val updated = initial.copy(
                        name = name.trim(),
                        baseUrl = baseUrl.trim(),
                        modelIds = models,
                        defaultModelId = defaultModel.trim().ifBlank { models.firstOrNull().orEmpty() }
                    )
                    onSave(updated, key.trim().takeIf(String::isNotBlank))
                }
            ) { Text("Save profile") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
