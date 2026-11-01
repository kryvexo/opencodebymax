package com.example.gemini.data.providers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI

/** App-process registry for API provider profiles. It never stores provider secrets. */
object ApiProviderRegistry {
    private const val PREFS = "maxcode_api_providers"
    private const val PROFILES_KEY = "profiles_v1"
    private const val DEFAULT_KEY = "default_provider_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _profiles = MutableStateFlow<List<ApiProviderProfile>>(emptyList())
    val profiles: StateFlow<List<ApiProviderProfile>> = _profiles.asStateFlow()
    private val _defaultProviderId = MutableStateFlow(ApiProviderProfile.GEMINI_MANAGED_ID)
    val defaultProviderId: StateFlow<String> = _defaultProviderId.asStateFlow()
    @Volatile private var loaded = false

    @Synchronized
    fun refresh(context: Context) {
        val prefs = prefs(context)
        _profiles.value = runCatching {
            json.decodeFromString(ListSerializer(ApiProviderProfile.serializer()), prefs.getString(PROFILES_KEY, "[]") ?: "[]")
        }.getOrDefault(emptyList()).filter { validStoredProfile(it) }.distinctBy { it.id }
        val requestedDefault = prefs.getString(DEFAULT_KEY, ApiProviderProfile.GEMINI_MANAGED_ID)
            ?: ApiProviderProfile.GEMINI_MANAGED_ID
        _defaultProviderId.value = if (requestedDefault == ApiProviderProfile.GEMINI_MANAGED_ID || _profiles.value.any { it.id == requestedDefault }) {
            requestedDefault
        } else ApiProviderProfile.GEMINI_MANAGED_ID
        loaded = true
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (!loaded) refresh(context)
    }

    fun getProfile(context: Context, providerId: String): ApiProviderProfile? {
        ensureLoaded(context)
        return _profiles.value.firstOrNull { it.id == providerId }
    }

    @Synchronized
    fun save(context: Context, profile: ApiProviderProfile, apiKey: String?) {
        ensureLoaded(context)
        val clean = profile.copy(
            name = profile.name.trim().take(64),
            baseUrl = normalizeAndValidateBaseUrl(profile.baseUrl),
            modelIds = profile.modelIds.map(String::trim).filter(String::isNotBlank).distinct().take(500),
            defaultModelId = profile.defaultModelId.trim()
        )
        require(clean.id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid provider ID." }
        require(clean.name.isNotBlank()) { "Enter a provider name." }
        apiKey?.trim()?.takeIf(String::isNotBlank)?.let { ProviderApiKeyStore.save(context, clean.id, it) }
        val next = (_profiles.value.filterNot { it.id == clean.id } + clean).sortedBy { it.name.lowercase() }
        _profiles.value = next
        persist(context)
    }

    @Synchronized
    fun delete(context: Context, providerId: String) {
        ensureLoaded(context)
        _profiles.value = _profiles.value.filterNot { it.id == providerId }
        ProviderApiKeyStore.clear(context, providerId)
        if (_defaultProviderId.value == providerId) {
            _defaultProviderId.value = ApiProviderProfile.GEMINI_MANAGED_ID
            prefs(context).edit().putString(DEFAULT_KEY, _defaultProviderId.value).apply()
        }
        persist(context)
    }

    @Synchronized
    fun setDefaultProvider(context: Context, providerId: String) {
        ensureLoaded(context)
        require(providerId == ApiProviderProfile.GEMINI_MANAGED_ID || _profiles.value.any { it.id == providerId }) {
            "Select a configured provider."
        }
        _defaultProviderId.value = providerId
        prefs(context).edit().putString(DEFAULT_KEY, providerId).apply()
    }

    fun hasCredential(context: Context, providerId: String): Boolean = when (providerId) {
        ApiProviderProfile.GEMINI_MANAGED_ID -> com.example.gemini.data.preferences.GeminiApiKeyStore.hasKey(context)
        else -> ProviderApiKeyStore.hasKey(context, providerId)
    }

    private fun persist(context: Context) {
        prefs(context).edit()
            .putString(PROFILES_KEY, json.encodeToString(ListSerializer(ApiProviderProfile.serializer()), _profiles.value))
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun validStoredProfile(profile: ApiProviderProfile): Boolean = runCatching {
        profile.id.matches(Regex("[A-Za-z0-9._-]{1,100}")) && profile.name.isNotBlank() && normalizeAndValidateBaseUrl(profile.baseUrl).isNotBlank()
    }.getOrDefault(false)

    fun normalizeAndValidateBaseUrl(raw: String): String {
        var value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "Enter a provider base URL." }
        value = value.removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Provider URL must use HTTPS (HTTP is allowed for local/self-hosted servers)."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Enter a base URL without credentials, query parameters, or fragments."
        }
        return value
    }
}
