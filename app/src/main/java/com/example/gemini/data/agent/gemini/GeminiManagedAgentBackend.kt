package com.example.gemini.data.agent.gemini

import android.content.Context
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.preferences.GeminiApiKeyStore
import com.example.gemini.data.remote.GeminiInteractionsApi
import com.example.gemini.data.providers.ApiProviderProfile
import com.example.gemini.data.providers.ApiProviderRegistry
import com.example.gemini.data.providers.ProviderApiKeyStore
import com.example.gemini.data.remote.OpenAiCompatibleApi
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.UUID

/** Antigravity managed-agent chat over the public Gemini developer API. */
class GeminiManagedAgentBackend(
    context: Context,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    private val api: GeminiInteractionsApi = GeminiInteractionsApi()
) : AgentChatBackend {
    private val appContext = context.applicationContext
    private val history = ManagedAgentHistoryStore(appContext)
    private val openAiApi = OpenAiCompatibleApi()
    override val kind: AgentKind = AgentKind.AGY

    override val conversations: StateFlow<List<Conversation>> = store.conversations
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation
    override val messages: StateFlow<List<ChatMessage>> = store.messages
    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()
    private val _conversationError = store.conversationError
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _availableModels = MutableStateFlow(
        listOf(
            AiModel.fromApi(
                GeminiInteractionsApi.ANTIGRAVITY_AGENT_ID,
                "Antigravity Managed Agent",
                "Google managed agent via the Gemini developer API"
            )
        )
    )
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()
    private val _selectedModelId = MutableStateFlow(GeminiInteractionsApi.ANTIGRAVITY_AGENT_ID)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()
    private val _selectedProviderId = MutableStateFlow(ApiProviderProfile.GEMINI_MANAGED_ID)
    val selectedProviderId: StateFlow<String> = _selectedProviderId.asStateFlow()
    val providerProfiles: StateFlow<List<ApiProviderProfile>> = ApiProviderRegistry.profiles
    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()
    private var activeSession: GeminiInteractionsApi.Session? = null
    private var streamJob: Job? = null
    @Volatile private var activeCall: Call? = null

    init {
        ApiProviderRegistry.ensureLoaded(appContext)
        updateProviderModels(ApiProviderRegistry.defaultProviderId.value, "")
        backendScope.launch {
            combine(store.currentConversation, ApiProviderRegistry.profiles, ApiProviderRegistry.defaultProviderId) { conversation, _, defaultId ->
                val providerId = conversation?.providerId?.takeIf(String::isNotBlank) ?: defaultId
                providerId to conversation?.modelId.orEmpty()
            }.distinctUntilChanged().collect { (providerId, modelId) ->
                updateProviderModels(providerId, modelId)
            }
        }
    }

    fun restoreHistory() {
        backendScope.launch(Dispatchers.IO) {
            val entries = history.loadAll()
            withContext(Dispatchers.Main) {
                val existing = store.conversations.value.filter { it.agent != AgentKind.AGY }
                store.conversations.value = (entries.map { it.conversation } + existing)
                    .distinctBy { it.id }
                    .sortedByDescending { it.updatedAt }
                if (store.currentConversation.value == null) {
                    val first = entries.firstOrNull()
                    if (first != null) {
                        store.currentConversation.value = first.conversation
                        store.messages.value = first.messages
                        activeSession = sessionFrom(first)
                    }
                }
            }
        }
    }

    override fun startNewChat() {
        stopStreaming()
        ApiProviderRegistry.ensureLoaded(appContext)
        val providerId = ApiProviderRegistry.defaultProviderId.value
        val modelId = initialModelIdFor(providerId)
        updateProviderModels(providerId, modelId)
        val activePath = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path.orEmpty()
        val workspace = activePath.takeIf(String::isNotBlank)?.let { if (it.startsWith("file://")) it else "file://$it" }.orEmpty()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = modelId,
            providerId = providerId,
            sessionId = UUID.randomUUID().toString(),
            workspaceUri = workspace,
            agent = AgentKind.AGY
        )
        store.currentConversation.value = conversation
        store.messages.value = emptyList()
        store.conversationError.value = null
        activeSession = null
    }

    override fun selectConversation(id: String) {
        stopStreaming()
        _isLoadingConversation.value = true
        store.conversationError.value = null
        backendScope.launch(Dispatchers.IO) {
            val entry = history.load(id)
            withContext(Dispatchers.Main) {
                if (entry == null) {
                    _isLoadingConversation.value = false
                    store.conversationError.value = "This saved Gemini API chat could not be loaded."
                    return@withContext
                }
                store.currentConversation.value = entry.conversation
                store.messages.value = entry.messages
                activeSession = sessionFrom(entry)
                val providerId = entry.conversation.providerId.ifBlank { ApiProviderProfile.GEMINI_MANAGED_ID }
                updateProviderModels(providerId, entry.conversation.modelId)
                _isLoadingConversation.value = false
            }
        }
    }

    override fun sendMessage(content: String) {
        val prompt = content.trim()
        if (prompt.isEmpty() || _isStreaming.value) return
        val conversation = store.currentConversation.value ?: run {
            startNewChat()
            store.currentConversation.value ?: return
        }
        val providerId = conversation.providerId.ifBlank { ApiProviderProfile.GEMINI_MANAGED_ID }
        val conversationId = conversation.id
        val priorMessages = store.messages.value.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val userMessage = ChatMessage(conversationId = conversationId, role = MessageRole.USER, content = prompt)
        val assistantMessage = ChatMessage(conversationId = conversationId, role = MessageRole.ASSISTANT, content = "", isStreaming = true)
        val allMessages = store.messages.value + userMessage + assistantMessage
        val newTitle = if (conversation.title == "New Chat") prompt.replace('\n', ' ').take(54).ifBlank { "AI Chat" } else conversation.title
        val updatedConversation = conversation.copy(
            title = newTitle,
            modelId = _selectedModelId.value,
            providerId = providerId,
            updatedAt = System.currentTimeMillis(),
            isRunning = true,
            notFullyIdle = true,
            hasActivity = true,
            runStatus = "CASCADE_RUN_STATUS_RUNNING"
        )
        store.currentConversation.value = updatedConversation
        store.conversations.value = (listOf(updatedConversation) + store.conversations.value.filterNot { it.id == conversationId })
            .sortedByDescending { it.updatedAt }
        store.messages.value = allMessages
        store.conversationError.value = null
        _isStreaming.value = true

        val savedEntry = history.load(conversationId)
        streamJob = backendScope.launch {
            try {
                var interactionId: String? = null
                var environmentId: String? = null
                val outputText = if (providerId == ApiProviderProfile.GEMINI_MANAGED_ID) {
                    val session = activeSession ?: savedEntry?.let(::sessionFrom)
                    val userText = if (session == null && priorMessages.isNotEmpty()) {
                        buildString {
                            appendLine("Conversation so far:")
                            priorMessages.forEach { message ->
                                appendLine("${if (message.role == MessageRole.USER) "User" else "Assistant"}: ${message.content}")
                                appendLine()
                            }
                            appendLine("Latest user request:")
                            append(prompt)
                        }
                    } else prompt
                    val apiKey = withContext(Dispatchers.IO) { GeminiApiKeyStore.read(appContext) }
                    if (apiKey.isNullOrBlank()) {
                        throw IllegalStateException("Add your Gemini API key in Settings → Gemini API Key before chatting.")
                    }
                    val call = api.newCall(apiKey, userText, session)
                    activeCall = call
                    val result = withContext(Dispatchers.IO) {
                        api.readStream(call) { delta -> appendAssistant(conversationId, assistantMessage.id, delta) }
                    }
                    activeSession = result.session
                    interactionId = result.session.interactionId
                    environmentId = result.session.environmentId
                    result.outputText
                } else {
                    val profile = ApiProviderRegistry.getProfile(appContext, providerId)
                        ?: throw IllegalStateException("This chat's provider is no longer configured. Add it again in Settings → AI Providers.")
                    val apiKey = withContext(Dispatchers.IO) { ProviderApiKeyStore.read(appContext, providerId) }
                    if (apiKey.isNullOrBlank()) {
                        throw IllegalStateException("Add an API key for ${profile.name} in Settings → AI Providers.")
                    }
                    if (updatedConversation.modelId.isBlank()) {
                        throw IllegalStateException("Add or fetch a model ID for ${profile.name} in Settings → AI Providers.")
                    }
                    val requestMessages = buildList {
                        add(OpenAiCompatibleApi.Message("system", SYSTEM_PROMPT))
                        priorMessages.forEach { message ->
                            val role = when (message.role) {
                                MessageRole.USER -> "user"
                                MessageRole.ASSISTANT -> "assistant"
                                else -> null
                            }
                            if (role != null && message.content.isNotBlank()) add(OpenAiCompatibleApi.Message(role, message.content))
                        }
                        add(OpenAiCompatibleApi.Message("user", prompt))
                    }
                    activeSession = null
                    val call = openAiApi.newStreamingCall(profile, apiKey, updatedConversation.modelId, requestMessages)
                    activeCall = call
                    withContext(Dispatchers.IO) {
                        openAiApi.readStream(call) { delta -> appendAssistant(conversationId, assistantMessage.id, delta) }
                    }
                }
                finishAssistant(conversationId, assistantMessage.id, outputText)
                val finalConversation = store.conversations.value.firstOrNull { it.id == conversationId }
                    ?: updatedConversation.copy(isRunning = false, notFullyIdle = false, updatedAt = System.currentTimeMillis())
                val finalMessages = if (store.currentConversation.value?.id == conversationId) store.messages.value else {
                    allMessages.map { if (it.id == assistantMessage.id) it.copy(content = outputText, isStreaming = false) else it }
                }
                history.save(
                    ManagedAgentHistoryStore.Entry(
                        conversation = finalConversation.copy(isRunning = false, notFullyIdle = false),
                        messages = finalMessages,
                        interactionId = interactionId,
                        environmentId = environmentId
                    )
                )
            } catch (cancelled: CancellationException) {
                markStopped(conversationId, assistantMessage.id)
                throw cancelled
            } catch (error: Exception) {
                val safeMessage = error.message?.takeIf(String::isNotBlank) ?: "Provider request failed. Check your connection, API key, and model ID."
                failAssistant(conversationId, assistantMessage.id, safeMessage)
                val finalConversation = store.conversations.value.firstOrNull { it.id == conversationId }
                    ?: updatedConversation
                val finalMessages = if (store.currentConversation.value?.id == conversationId) store.messages.value else allMessages
                history.save(
                    ManagedAgentHistoryStore.Entry(
                        conversation = finalConversation.copy(isRunning = false, notFullyIdle = false),
                        messages = finalMessages,
                        interactionId = if (providerId == ApiProviderProfile.GEMINI_MANAGED_ID) activeSession?.interactionId else null,
                        environmentId = if (providerId == ApiProviderProfile.GEMINI_MANAGED_ID) activeSession?.environmentId else null
                    )
                )
            } finally {
                activeCall = null
                _isStreaming.value = false
                streamJob = null
            }
        }
    }

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        store.currentConversation.value?.let { current ->
            val updated = current.copy(modelId = modelId)
            store.currentConversation.value = updated
            store.conversations.value = store.conversations.value.map { if (it.id == current.id) updated else it }
        }
    }

    /** Switches the provider for the open AGY chat; provider defaults for future chats live in Settings. */
    fun selectProvider(providerId: String) {
        if (providerId != ApiProviderProfile.GEMINI_MANAGED_ID && ApiProviderRegistry.getProfile(appContext, providerId) == null) return
        val conversation = store.currentConversation.value
        val modelId = initialModelIdFor(providerId)
        updateProviderModels(providerId, modelId)
        if (conversation != null) {
            val updated = conversation.copy(providerId = providerId, modelId = modelId, updatedAt = System.currentTimeMillis())
            store.currentConversation.value = updated
            store.conversations.value = store.conversations.value.map { if (it.id == conversation.id) updated else it }
            activeSession = null
        }
    }

    fun refreshSelectedProviderModels() {
        val providerId = _selectedProviderId.value
        if (providerId == ApiProviderProfile.GEMINI_MANAGED_ID) return
        val profile = ApiProviderRegistry.getProfile(appContext, providerId) ?: return
        val apiKey = ProviderApiKeyStore.read(appContext, providerId) ?: return
        if (_isRefreshingModels.value) return
        backendScope.launch {
            _isRefreshingModels.value = true
            try {
                val ids = withContext(Dispatchers.IO) { openAiApi.fetchModelIds(profile, apiKey) }
                ApiProviderRegistry.save(appContext, profile.copy(modelIds = ids, defaultModelId = profile.defaultModelId.ifBlank { ids.first() }), null)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, "Loaded ${ids.size} models from ${profile.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, error.message ?: "Could not load provider models.", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    private fun initialModelIdFor(providerId: String): String {
        if (providerId == ApiProviderProfile.GEMINI_MANAGED_ID) return GeminiInteractionsApi.ANTIGRAVITY_AGENT_ID
        val profile = ApiProviderRegistry.getProfile(appContext, providerId) ?: return ""
        return profile.defaultModelId.takeIf { it.isNotBlank() } ?: profile.modelIds.firstOrNull().orEmpty()
    }

    private fun updateProviderModels(providerId: String, savedModelId: String) {
        _selectedProviderId.value = providerId
        if (providerId == ApiProviderProfile.GEMINI_MANAGED_ID) {
            _availableModels.value = listOf(AiModel.fromApi(
                GeminiInteractionsApi.ANTIGRAVITY_AGENT_ID,
                "Antigravity Managed Agent",
                "Google managed agent via the Gemini developer API"
            ))
            _selectedModelId.value = savedModelId.takeIf(String::isNotBlank) ?: GeminiInteractionsApi.ANTIGRAVITY_AGENT_ID
            return
        }
        val profile = ApiProviderRegistry.profiles.value.firstOrNull { it.id == providerId }
        val modelIds = (profile?.modelIds.orEmpty() + listOfNotNull(profile?.defaultModelId?.takeIf(String::isNotBlank))).distinct()
        val models = modelIds.map { id -> AiModel.fromApi(id, id, profile?.name ?: "OpenAI-compatible model") }
        _availableModels.value = models
        val preferred = savedModelId.takeIf { it.isNotBlank() && (models.isEmpty() || models.any { model -> model.id == it }) }
        _selectedModelId.value = preferred ?: profile?.defaultModelId?.takeIf(String::isNotBlank)
            ?: models.firstOrNull()?.id.orEmpty()
    }

    fun retryLastMessage() {
        val messages = store.messages.value
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER } ?: return
        store.messages.value = messages.filterNot { it.role == MessageRole.ASSISTANT && it.content.isBlank() }
        sendMessage(lastUser.content)
    }

    override fun stopStreaming() {
        activeCall?.cancel()
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        val conversationId = store.currentConversation.value?.id ?: return
        val lastAssistant = store.messages.value.lastOrNull { it.conversationId == conversationId && it.role == MessageRole.ASSISTANT && it.isStreaming }
        if (lastAssistant != null) markStopped(conversationId, lastAssistant.id)
    }

    override fun deleteConversation(id: String) {
        backendScope.launch(Dispatchers.IO) {
            history.delete(id)
            withContext(Dispatchers.Main) {
                store.conversations.value = store.conversations.value.filterNot { it.id == id }
                if (store.currentConversation.value?.id == id) {
                    store.currentConversation.value = null
                    store.messages.value = emptyList()
                    activeSession = null
                    startNewChat()
                }
            }
        }
    }

    override fun forkConversation(id: String) {
        backendScope.launch(Dispatchers.IO) {
            val original = history.load(id) ?: return@launch
            val newId = UUID.randomUUID().toString()
            val forkedConversation = original.conversation.copy(
                id = newId,
                title = "${original.conversation.title} (copy)".take(54),
                sessionId = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isRunning = false,
                notFullyIdle = false,
                parentConversationId = original.conversation.id
            )
            val forkedMessages = original.messages.map { it.copy(id = UUID.randomUUID().toString(), conversationId = newId, isStreaming = false) }
            history.save(ManagedAgentHistoryStore.Entry(forkedConversation, forkedMessages, null, null))
            withContext(Dispatchers.Main) {
                store.conversations.value = (listOf(forkedConversation) + store.conversations.value).distinctBy { it.id }
                    .sortedByDescending { it.updatedAt }
                store.currentConversation.value = forkedConversation
                store.messages.value = forkedMessages
                activeSession = null
            }
        }
    }

    override fun retryConnections() {
        if (store.currentConversation.value?.agent == AgentKind.AGY) {
            store.conversationError.value = null
        }
    }

    override fun onAppForegrounded() = Unit

    private fun sessionFrom(entry: ManagedAgentHistoryStore.Entry): GeminiInteractionsApi.Session? =
        if (!entry.interactionId.isNullOrBlank() && !entry.environmentId.isNullOrBlank()) {
            GeminiInteractionsApi.Session(entry.interactionId, entry.environmentId)
        } else null

    private fun appendAssistant(conversationId: String, assistantId: String, delta: String) {
        if (store.currentConversation.value?.id != conversationId) return
        store.messages.value = store.messages.value.map { message ->
            if (message.id == assistantId) message.copy(content = message.content + delta, isStreaming = true) else message
        }
    }

    private fun finishAssistant(conversationId: String, assistantId: String, fullText: String) {
        val entry = history.load(conversationId)
        val updatedMessages = if (store.currentConversation.value?.id == conversationId) {
            store.messages.value.map { message ->
                if (message.id == assistantId) message.copy(content = fullText, isStreaming = false) else message
            }
        } else {
            entry?.messages.orEmpty().map { message ->
                if (message.id == assistantId) message.copy(content = fullText, isStreaming = false) else message
            }
        }
        if (store.currentConversation.value?.id == conversationId) store.messages.value = updatedMessages
        val current = store.conversations.value.firstOrNull { it.id == conversationId } ?: return
        val completed = current.copy(updatedAt = System.currentTimeMillis(), isRunning = false, notFullyIdle = false, hasActivity = true, runStatus = "CASCADE_RUN_STATUS_IDLE")
        store.conversations.value = store.conversations.value.map { if (it.id == conversationId) completed else it }
        if (store.currentConversation.value?.id == conversationId) store.currentConversation.value = completed
    }

    private fun failAssistant(conversationId: String, assistantId: String, error: String) {
        val localMessages = store.messages.value
        if (store.currentConversation.value?.id == conversationId) {
            store.messages.value = localMessages.filterNot { it.id == assistantId && it.content.isBlank() }
            store.conversationError.value = error
        }
        updateConversationStopped(conversationId)
    }

    private fun markStopped(conversationId: String, assistantId: String) {
        if (store.currentConversation.value?.id == conversationId) {
            store.messages.value = store.messages.value.map { message ->
                if (message.id == assistantId) message.copy(isStreaming = false) else message
            }
        }
        updateConversationStopped(conversationId)
    }

    private fun updateConversationStopped(conversationId: String) {
        val stopped = store.conversations.value.map { conversation ->
            if (conversation.id == conversationId) conversation.copy(isRunning = false, notFullyIdle = false, updatedAt = System.currentTimeMillis()) else conversation
        }
        store.conversations.value = stopped
        if (store.currentConversation.value?.id == conversationId) {
            store.currentConversation.value = stopped.firstOrNull { it.id == conversationId }
        }
    }

    private companion object {
        const val SYSTEM_PROMPT = "You are MAXCODE, a helpful AI coding assistant. Reply in the same language as the user's latest message. Be accurate, explain assumptions briefly, and use fenced code blocks for code when helpful. Do not claim to run tools or edit files unless the app actually performed that action."
    }
}
