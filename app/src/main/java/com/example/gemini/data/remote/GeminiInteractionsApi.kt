package com.example.gemini.data.remote

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public Gemini Interactions API client for Google's managed Antigravity agent. */
class GeminiInteractionsApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.MINUTES)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    data class Session(val interactionId: String, val environmentId: String)
    data class Result(val session: Session, val status: String, val outputText: String)

    fun newCall(apiKey: String, userText: String, session: Session?): Call {
        val inputText = if (session == null) {
            "You are a helpful coding agent. Respond in the same natural language as the user's latest message and keep that language consistent. Preserve code, identifiers, commands, and API names exactly. Be clear about anything you cannot access.\n\nUser request:\n$userText"
        } else userText
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", inputText))
        val payload = JSONObject()
            .put("agent", ANTIGRAVITY_AGENT_ID)
            .put("input", input)
            .put("stream", true)
        if (session == null) {
            payload.put("environment", JSONObject().put("type", "remote"))
        } else {
            payload.put("previous_interaction_id", session.interactionId)
            payload.put("environment", session.environmentId)
        }
        val request = Request.Builder()
            .url(INTERACTIONS_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request)
    }

    /** Consume a streaming response. The caller must execute this on Dispatchers.IO and cancel [call] to stop it. */
    @Throws(IOException::class)
    fun readStream(call: Call, onTextDelta: (String) -> Unit): Result {
        call.execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                val message = body?.string()?.let(::safeApiError).orEmpty()
                throw IOException(message.ifBlank { "Gemini API request failed (HTTP ${response.code}). Check your API key and Google AI Studio access." })
            }

            var interactionId = ""
            var environmentId = ""
            var status = "in_progress"
            val accumulated = StringBuilder()
            var data = StringBuilder()
            val source = body.source()

            fun processEvent(raw: String) {
                val value = raw.trim()
                if (value.isEmpty() || value == "[DONE]") return
                val event = runCatching { JSONObject(value) }.getOrNull() ?: return
                val eventType = event.optString("event_type")
                val interaction = event.optJSONObject("interaction")
                if (interaction != null) {
                    interaction.optString("id").takeIf(String::isNotBlank)?.let { interactionId = it }
                    interaction.optString("environment_id").takeIf(String::isNotBlank)?.let { environmentId = it }
                    interaction.optString("status").takeIf(String::isNotBlank)?.let { status = it }
                }
                event.optString("interaction_id").takeIf(String::isNotBlank)?.let { interactionId = it }
                event.optString("environment_id").takeIf(String::isNotBlank)?.let { environmentId = it }
                event.optString("status").takeIf(String::isNotBlank)?.let { status = it }
                if (eventType == "error") {
                    val error = event.optJSONObject("error")
                    throw IOException(error?.optString("message")?.takeIf(String::isNotBlank) ?: "Gemini managed agent reported an error.")
                }
                val delta = event.optJSONObject("delta")
                if (eventType == "step.delta" && delta?.optString("type") == "text") {
                    val text = delta.optString("text")
                    if (text.isNotEmpty()) {
                        accumulated.append(text)
                        onTextDelta(text)
                    }
                }
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        processEvent(data.toString())
                        data = StringBuilder()
                    }
                    continue
                }
                if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
            if (data.isNotEmpty()) processEvent(data.toString())
            if (interactionId.isBlank()) throw IOException("Gemini API stream ended without an interaction ID. Try again.")

            // Streaming completion events are partial resources. Fetch the completed interaction to recover its
            // environment_id, which is required for the next turn in the same managed sandbox.
            var finalOutput = accumulated.toString()
            if (environmentId.isBlank() || finalOutput.isBlank() || status == "in_progress") {
                val key = call.request().header("x-goog-api-key")
                    ?: throw IOException("Missing Gemini API credential in request context.")
                val detail = fetchInteraction(key, interactionId)
                if (environmentId.isBlank()) environmentId = detail.optString("environment_id")
                detail.optString("status").takeIf(String::isNotBlank)?.let { status = it }
                if (finalOutput.isBlank()) finalOutput = extractOutputText(detail)
            }
            if (environmentId.isBlank()) throw IOException("Gemini API did not return a managed environment ID. Try again.")
            if (status == "failed" || status == "cancelled") throw IOException("Gemini managed agent ended with status: $status.")
            if (finalOutput.isBlank()) throw IOException("Gemini managed agent returned no response text. Try again.")
            return Result(Session(interactionId, environmentId), status, finalOutput)
        }
    }

    private fun fetchInteraction(apiKey: String, interactionId: String): JSONObject {
        val request = Request.Builder()
            .url("$INTERACTIONS_ENDPOINT/${java.net.URLEncoder.encode(interactionId, "UTF-8")}")
            .header("x-goog-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(safeApiError(body).ifBlank { "Could not retrieve the completed Gemini interaction (HTTP ${response.code})." })
            }
            return runCatching { JSONObject(body) }.getOrElse { throw IOException("Gemini API returned an invalid completed interaction.") }
        }
    }

    private fun extractOutputText(interaction: JSONObject): String {
        interaction.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        val steps = interaction.optJSONArray("steps") ?: return ""
        return buildString {
            for (index in 0 until steps.length()) {
                val content = steps.optJSONObject(index)?.optJSONArray("content") ?: continue
                for (itemIndex in 0 until content.length()) {
                    val item = content.optJSONObject(itemIndex) ?: continue
                    if (item.optString("type") == "text") append(item.optString("text"))
                }
            }
        }
    }

    private fun safeApiError(raw: String): String {
        val message = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty()
        return message.takeIf(String::isNotBlank)
            ?: "Gemini API request failed. Check the key, API access and account limits."
    }

    companion object {
        const val ANTIGRAVITY_AGENT_ID = "antigravity-preview-09-2026"
        private const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
