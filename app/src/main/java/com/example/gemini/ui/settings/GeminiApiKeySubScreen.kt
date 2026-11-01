package com.example.gemini.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.gemini.data.preferences.GeminiApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GeminiApiKeySubScreen(
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var hasSavedKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasSavedKey = withContext(Dispatchers.IO) { GeminiApiKeyStore.hasKey(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Gemini developer API",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                Text(
                    text = if (hasSavedKey) "A key is saved on this device. Enter a new one to replace it." else "Add your own Google AI Studio API key to use the Antigravity managed agent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Gemini API key") },
                    placeholder = { Text("Paste your API key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showKey) "Hide API key" else "Show API key"
                            )
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = keyInput.isNotBlank() && !isBusy,
                        onClick = {
                            val candidate = keyInput.trim()
                            isBusy = true
                            status = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { GeminiApiKeyStore.save(context, candidate) }
                                }
                                isBusy = false
                                if (result.isSuccess) {
                                    keyInput = ""
                                    hasSavedKey = true
                                    status = "API key encrypted and saved on this device."
                                } else {
                                    status = "Could not save the API key securely. Try again."
                                }
                            }
                        }
                    ) {
                        Text(if (isBusy) "Saving…" else if (hasSavedKey) "Replace key" else "Save key")
                    }
                    if (hasSavedKey) {
                        OutlinedButton(
                            enabled = !isBusy,
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    withContext(Dispatchers.IO) { GeminiApiKeyStore.clear(context) }
                                    keyInput = ""
                                    hasSavedKey = false
                                    isBusy = false
                                    status = "Saved API key removed from this device."
                                }
                            }
                        ) { Text("Remove") }
                    }
                }
                if (status != null) {
                    Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create or manage a key", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { openAiStudio(context) }) {
                    Text("Open Google AI Studio")
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "This uses Google's Gemini developer API, not Google-account OAuth for the Antigravity IDE product. Google API usage and billing limits apply to your key. The key is encrypted with Android Keystore and is not bundled in the APK, but a key used on a device can still be extracted. Google recommends a backend proxy for production apps; treat any key entered on-device as exposed if the device is compromised or rooted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun openAiStudio(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
    }
}
