package com.example.gemini.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/**
 * Compatibility shim for the upstream GPL port. Google Antigravity product OAuth is intentionally unsupported.
 * The official Gemini developer API key flow is implemented separately in GeminiApiKeyStore.
 * This class contains no OAuth client credentials, does not open a callback server, and performs no network calls.
 */
@Deprecated("Use GeminiApiKeyStore and the public Gemini Interactions API instead.")
class GoogleOAuthManager {
    val isServerListening = MutableStateFlow(false)

    data class PkcePair(
        val codeVerifier: String,
        val codeChallenge: String,
        val authUrl: String
    )

    fun generatePkce(): PkcePair = PkcePair("", "", "")

    suspend fun startLocalCallbackServer(onCodeReceived: (String) -> Unit) {
        isServerListening.value = false
    }

    fun stopLocalCallbackServer() {
        isServerListening.value = false
    }

    @Serializable
    data class TokenResponse(
        val access_token: String = "",
        val refresh_token: String? = null,
        val expires_in: Long? = null,
        val token_type: String? = null
    )

    @Serializable
    data class UserInfoResponse(
        val email: String? = null,
        val name: String? = null
    )

    suspend fun exchangeCodeForToken(code: String, verifier: String): Result<TokenResponse> = unsupported()

    suspend fun refreshToken(refreshToken: String): Result<TokenResponse> = unsupported()

    suspend fun fetchUserInfo(accessToken: String): Result<UserInfoResponse> = unsupported()

    private fun <T> unsupported(): Result<T> = Result.failure(
        UnsupportedOperationException("Google Antigravity product OAuth is not supported. Add a Gemini developer API key in Settings.")
    )
}
