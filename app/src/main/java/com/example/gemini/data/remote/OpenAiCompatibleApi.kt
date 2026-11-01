package com.example.gemini.data.remote

import com.example.gemini.data.providers.ApiProviderKind
import com.example.gemini.data.providers.ApiProviderProfile
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI-compatible chat completions transport. Keys are supplied only at runtime and never logged. */
class OpenAiCompatibleApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    data class Message(val role: String, val content: String)

    fun newStreamingCall(
        profile: ApiProviderProfile,
        apiKey: String,
        modelId: String,
        messages: List<Message>
    ): Call {
        require(apiKey.isNotBlank()) { "Add an API key for ${profile.name} in Settings → AI Providers." }
        require(modelId.isNotBlank()) { "Choose a model for ${profile.name} in the model picker." }
        val payload = JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray().apply {
                messages.forEach { message -> put(JSONObject().put("role", message.role).put("content", message.content)) }
            })
            .put("stream", true)
        val request = Request.Builder()
            .url(endpoint(profile.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .apply {
                if (profile.kind == ApiProviderKind.OPENROUTER) {
                    header("X-OpenRouter-Title", "MAXCODE")
                }
            }
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(request)
    }

    /** Executes an SSE request on a worker thread and emits only user-visible text deltas. */
    fun readStream(call: Call, onDelta: (String) -> Unit): String {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val detail = runCatching {
                    val root = JSONObject(raw)
                    root.optJSONObject("error")?.optString("message")
                        ?: root.optString("message").takeIf(String::isNotBlank)
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    buildString {
                        append("Provider request failed (HTTP ${response.code}).")
                        if (detail.isNotBlank()) append(" ").append(detail.take(400))
                    }
                )
            }
            val source = response.body?.source() ?: throw IllegalStateException("Provider returned an empty response stream.")
            val output = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isBlank()) continue
                val delta = runCatching { extractTextDelta(JSONObject(data)) }.getOrNull().orEmpty()
                if (delta.isNotEmpty()) {
                    output.append(delta)
                    onDelta(delta)
                }
            }
            if (output.isEmpty()) throw IllegalStateException("Provider ended without returning text. Check the model ID and endpoint compatibility.")
            return output.toString()
        }
    }

    /** Optional /models discovery; callers should keep manual model IDs available as a fallback. */
    fun fetchModelIds(profile: ApiProviderProfile, apiKey: String): List<String> {
        require(apiKey.isNotBlank()) { "Add an API key before fetching models." }
        val request = Request.Builder()
            .url(endpoint(profile.baseUrl, "models"))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .apply { if (profile.kind == ApiProviderKind.OPENROUTER) header("X-OpenRouter-Title", "MAXCODE") }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val root = JSONObject(raw)
                    root.optJSONObject("error")?.optString("message") ?: root.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException("Could not fetch provider models (HTTP ${response.code}). ${detail.take(250)}")
            }
            val root = JSONObject(raw)
            val rows = root.optJSONArray("data") ?: root.optJSONArray("models")
                ?: throw IllegalStateException("This provider did not return a standard model list. Enter model IDs manually.")
            val ids = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                row.optString("id").takeIf(String::isNotBlank)
            }.distinct().take(500)
            if (ids.isEmpty()) throw IllegalStateException("The provider returned no model IDs. Enter model IDs manually.")
            return ids
        }
    }

    private fun extractTextDelta(root: JSONObject): String {
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message")
            if (message.isNotBlank()) throw IllegalStateException(message.take(400))
        }
        val choices = root.optJSONArray("choices") ?: return ""
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return ""
        val content = delta.opt("content") ?: return ""
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (item.optString("type") == "text") append(item.optString("text"))
                }
            }
            else -> ""
        }
    }

    private fun endpoint(baseUrl: String, suffix: String): String =
        "${baseUrl.trim().trimEnd('/')}/$suffix"
}
