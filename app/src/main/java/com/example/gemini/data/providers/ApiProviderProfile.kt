package com.example.gemini.data.providers

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ApiProviderKind {
    OPENCODE_ZEN,
    GROK,
    OPENROUTER,
    CUSTOM
}

/** Non-secret provider configuration. API keys are stored separately in ProviderApiKeyStore. */
@Serializable
data class ApiProviderProfile(
    val id: String,
    val name: String,
    val kind: ApiProviderKind,
    val baseUrl: String,
    val modelIds: List<String> = emptyList(),
    val defaultModelId: String = ""
) {
    companion object {
        const val GEMINI_MANAGED_ID = "gemini-antigravity"

        fun preset(kind: ApiProviderKind): ApiProviderProfile = when (kind) {
            ApiProviderKind.OPENCODE_ZEN -> ApiProviderProfile(
                id = "opencode-zen",
                name = "OpenCode Zen",
                kind = kind,
                baseUrl = "https://opencode.ai/inference/openai/v1"
            )
            ApiProviderKind.GROK -> ApiProviderProfile(
                id = "xai-grok",
                name = "Grok (xAI)",
                kind = kind,
                baseUrl = "https://api.x.ai/v1"
            )
            ApiProviderKind.OPENROUTER -> ApiProviderProfile(
                id = "openrouter",
                name = "OpenRouter",
                kind = kind,
                baseUrl = "https://openrouter.ai/api/v1"
            )
            ApiProviderKind.CUSTOM -> ApiProviderProfile(
                id = "custom-${UUID.randomUUID()}",
                name = "Custom provider",
                kind = kind,
                baseUrl = "https://api.example.com/v1"
            )
        }
    }
}
