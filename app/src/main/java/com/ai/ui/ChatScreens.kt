package com.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import com.ai.data.ChatHistoryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat - Select Provider screen.
 * Shows a table of all AI providers that have an API key configured.
 */
@Composable
fun ChatSelectProviderScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectProvider: (AiService) -> Unit
) {
    BackHandler { onNavigateBack() }

    // Get providers with API keys configured
    val configuredProviders = AiService.entries.filter { service ->
        val hasApiKey = when (service) {
            AiService.OPENAI -> aiSettings.chatGptApiKey.isNotBlank()
            AiService.ANTHROPIC -> aiSettings.claudeApiKey.isNotBlank()
            AiService.GOOGLE -> aiSettings.geminiApiKey.isNotBlank()
            AiService.XAI -> aiSettings.grokApiKey.isNotBlank()
            AiService.GROQ -> aiSettings.groqApiKey.isNotBlank()
            AiService.DEEPSEEK -> aiSettings.deepSeekApiKey.isNotBlank()
            AiService.MISTRAL -> aiSettings.mistralApiKey.isNotBlank()
            AiService.PERPLEXITY -> aiSettings.perplexityApiKey.isNotBlank()
            AiService.TOGETHER -> aiSettings.togetherApiKey.isNotBlank()
            AiService.OPENROUTER -> aiSettings.openRouterApiKey.isNotBlank()
            AiService.SILICONFLOW -> aiSettings.siliconFlowApiKey.isNotBlank()
            AiService.ZAI -> aiSettings.zaiApiKey.isNotBlank()
            AiService.DUMMY -> developerMode && aiSettings.dummyApiKey.isNotBlank()
        }
        hasApiKey
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Chat - Select Provider",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (configuredProviders.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No AI providers configured. Please add API keys in Settings > AI Setup.",
                    color = Color(0xFFAAAAAA),
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    configuredProviders.forEachIndexed { index, provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProvider(provider) }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = provider.displayName,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = ">",
                                color = Color(0xFF6B9BFF),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (index < configuredProviders.size - 1) {
                            HorizontalDivider(color = Color(0xFF444444))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chat - Select Model screen.
 * Shows a table of models from the selected AI provider.
 */
@Composable
fun ChatSelectModelScreen(
    provider: AiService,
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    BackHandler { onNavigateBack() }

    // Get API key and model source for this provider
    val (apiKey, modelSource, manualModels, defaultModel) = remember(provider) {
        when (provider) {
            AiService.OPENAI -> Quadruple(aiSettings.chatGptApiKey, aiSettings.chatGptModelSource, aiSettings.chatGptManualModels, aiSettings.chatGptModel)
            AiService.ANTHROPIC -> Quadruple(aiSettings.claudeApiKey, ModelSource.MANUAL, aiSettings.claudeManualModels, aiSettings.claudeModel)
            AiService.GOOGLE -> Quadruple(aiSettings.geminiApiKey, aiSettings.geminiModelSource, aiSettings.geminiManualModels, aiSettings.geminiModel)
            AiService.XAI -> Quadruple(aiSettings.grokApiKey, aiSettings.grokModelSource, aiSettings.grokManualModels, aiSettings.grokModel)
            AiService.GROQ -> Quadruple(aiSettings.groqApiKey, aiSettings.groqModelSource, aiSettings.groqManualModels, aiSettings.groqModel)
            AiService.DEEPSEEK -> Quadruple(aiSettings.deepSeekApiKey, aiSettings.deepSeekModelSource, aiSettings.deepSeekManualModels, aiSettings.deepSeekModel)
            AiService.MISTRAL -> Quadruple(aiSettings.mistralApiKey, aiSettings.mistralModelSource, aiSettings.mistralManualModels, aiSettings.mistralModel)
            AiService.PERPLEXITY -> Quadruple(aiSettings.perplexityApiKey, ModelSource.MANUAL, aiSettings.perplexityManualModels, aiSettings.perplexityModel)
            AiService.TOGETHER -> Quadruple(aiSettings.togetherApiKey, aiSettings.togetherModelSource, aiSettings.togetherManualModels, aiSettings.togetherModel)
            AiService.OPENROUTER -> Quadruple(aiSettings.openRouterApiKey, aiSettings.openRouterModelSource, aiSettings.openRouterManualModels, aiSettings.openRouterModel)
            AiService.SILICONFLOW -> Quadruple(aiSettings.siliconFlowApiKey, ModelSource.MANUAL, aiSettings.siliconFlowManualModels, aiSettings.siliconFlowModel)
            AiService.ZAI -> Quadruple(aiSettings.zaiApiKey, ModelSource.MANUAL, aiSettings.zaiManualModels, aiSettings.zaiModel)
            AiService.DUMMY -> Quadruple(aiSettings.dummyApiKey, aiSettings.dummyModelSource, aiSettings.dummyManualModels, aiSettings.dummyModel)
        }
    }

    // Search state
    var searchQuery by remember { mutableStateOf("") }

    // Determine which models to show
    val allModels = if (modelSource == ModelSource.API && availableModels.isNotEmpty()) {
        availableModels
    } else if (modelSource == ModelSource.MANUAL || availableModels.isEmpty()) {
        manualModels.ifEmpty { listOf(defaultModel) }
    } else {
        listOf(defaultModel)
    }

    // Filter models based on search query
    val models = if (searchQuery.isBlank()) {
        allModels
    } else {
        allModels.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Chat - Select Model",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Provider: ${provider.displayName}",
            color = Color(0xFF6B9BFF),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search models...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoadingModels) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF6B9BFF))
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn {
                    items(models) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectModel(model) }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = ">",
                                color = Color(0xFF6B9BFF),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        HorizontalDivider(color = Color(0xFF444444))
                    }
                }
            }
        }
    }
}

/**
 * Chat - Parameters screen.
 * Shows only parameters supported by the selected provider.
 */
@Composable
fun ChatParametersScreen(
    provider: AiService,
    model: String,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onStartChat: (ChatParameters) -> Unit
) {
    BackHandler { onNavigateBack() }

    val supportedParams = ALL_AGENT_PARAMETERS

    // Parameter state
    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf("") }
    var topP by remember { mutableStateOf("") }
    var topK by remember { mutableStateOf("") }
    var frequencyPenalty by remember { mutableStateOf("") }
    var presencePenalty by remember { mutableStateOf("") }
    var searchEnabled by remember { mutableStateOf(false) }
    var returnCitations by remember { mutableStateOf(true) }
    var searchRecency by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Chat - Parameters",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Provider: ${provider.displayName}",
            color = Color(0xFF6B9BFF),
            fontSize = 14.sp
        )
        Text(
            text = "Model: $model",
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Start Chat button at top
        Button(
            onClick = {
                onStartChat(ChatParameters(
                    systemPrompt = systemPrompt,
                    temperature = temperature.toFloatOrNull(),
                    maxTokens = maxTokens.toIntOrNull(),
                    topP = topP.toFloatOrNull(),
                    topK = topK.toIntOrNull(),
                    frequencyPenalty = frequencyPenalty.toFloatOrNull(),
                    presencePenalty = presencePenalty.toFloatOrNull(),
                    searchEnabled = searchEnabled,
                    returnCitations = returnCitations,
                    searchRecency = searchRecency.ifBlank { null }
                ))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Start Chat")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // System Prompt (always first if supported)
            if (AiParameter.SYSTEM_PROMPT in supportedParams) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Prompt") },
                    placeholder = { Text("Optional instructions for the AI...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Temperature
            if (AiParameter.TEMPERATURE in supportedParams) {
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Temperature (0.0 - 2.0)") },
                    placeholder = { Text("Default varies by provider") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Max Tokens
            if (AiParameter.MAX_TOKENS in supportedParams) {
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("Max Tokens") },
                    placeholder = { Text("Maximum response length") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Top P
            if (AiParameter.TOP_P in supportedParams) {
                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text("Top P (0.0 - 1.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Top K
            if (AiParameter.TOP_K in supportedParams) {
                OutlinedTextField(
                    value = topK,
                    onValueChange = { topK = it },
                    label = { Text("Top K") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Frequency Penalty
            if (AiParameter.FREQUENCY_PENALTY in supportedParams) {
                OutlinedTextField(
                    value = frequencyPenalty,
                    onValueChange = { frequencyPenalty = it },
                    label = { Text("Frequency Penalty (-2.0 to 2.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Presence Penalty
            if (AiParameter.PRESENCE_PENALTY in supportedParams) {
                OutlinedTextField(
                    value = presencePenalty,
                    onValueChange = { presencePenalty = it },
                    label = { Text("Presence Penalty (-2.0 to 2.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Search Enabled (Grok, Perplexity)
            if (AiParameter.SEARCH_ENABLED in supportedParams) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { searchEnabled = !searchEnabled }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = searchEnabled,
                        onCheckedChange = { searchEnabled = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Web Search", color = Color.White)
                }
            }

            // Return Citations (Perplexity)
            if (AiParameter.RETURN_CITATIONS in supportedParams) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { returnCitations = !returnCitations }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = returnCitations,
                        onCheckedChange = { returnCitations = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Return Citations", color = Color.White)
                }
            }

            // Search Recency (Perplexity)
            if (AiParameter.SEARCH_RECENCY in supportedParams) {
                OutlinedTextField(
                    value = searchRecency,
                    onValueChange = { searchRecency = it },
                    label = { Text("Search Recency (day/week/month/year)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }
        }
    }
}

/**
 * Chat - Session screen.
 * The actual chat interface with message history and streaming support.
 */
@Composable
fun ChatSessionScreen(
    provider: AiService,
    model: String,
    apiKey: String,
    parameters: ChatParameters,
    userName: String = "user",
    initialMessages: List<ChatMessage> = emptyList(),
    sessionId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSendMessage: suspend (List<ChatMessage>, String) -> ChatMessage?,
    onSendMessageStream: ((List<ChatMessage>) -> Flow<String>)? = null
) {
    BackHandler { onNavigateBack() }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Chat state - use initial messages if provided (for continuing a session)
    var messages by remember { mutableStateOf(initialMessages) }
    var userInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Streaming state
    var isStreaming by remember { mutableStateOf(false) }
    var streamingContent by remember { mutableStateOf("") }

    // Current session ID (create new one if not continuing an existing session)
    val currentSessionId = remember { sessionId ?: java.util.UUID.randomUUID().toString() }

    // Function to save the current session
    fun saveSession(msgs: List<ChatMessage>) {
        val session = ChatSession(
            id = currentSessionId,
            provider = provider,
            model = model,
            messages = msgs,
            parameters = parameters,
            updatedAt = System.currentTimeMillis()
        )
        ChatHistoryManager.saveSession(session)
    }

    // Add system prompt as first message if provided and no initial messages
    LaunchedEffect(parameters.systemPrompt) {
        if (parameters.systemPrompt.isNotBlank() && messages.isEmpty()) {
            messages = listOf(ChatMessage(role = "system", content = parameters.systemPrompt))
        }
    }

    // Request focus on the input field when screen opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Auto-scroll to top when new messages arrive or streaming content changes
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        AiTitleBar(
            title = "Chat - Session",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${provider.displayName} / $model",
            color = Color(0xFF6B9BFF),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Chat messages area
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (messages.isEmpty() || (messages.size == 1 && messages[0].role == "system")) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start a conversation...",
                        color = Color(0xFF666666)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Show streaming message at top (newest first layout)
                    if (isStreaming && streamingContent.isNotEmpty()) {
                        item {
                            StreamingMessageBubble(content = streamingContent)
                        }
                    }

                    // Show loading indicator while waiting for first chunk
                    if (isStreaming && streamingContent.isEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF6B9BFF),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Thinking...",
                                    color = Color(0xFF888888),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Non-streaming loading indicator (fallback mode)
                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF6B9BFF),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Thinking...",
                                    color = Color(0xFF888888),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    items(messages.filter { it.role != "system" }.reversed()) { message ->
                        ChatMessageBubble(message = message, userName = userName)
                    }
                }
            }
        }

        // Error message
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input area at the bottom - full width
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                minLines = 1,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (userInput.isNotBlank() && !isLoading && !isStreaming) {
                        val input = userInput.trim()
                        userInput = ""
                        error = null

                        // Add user message
                        val userMessage = ChatMessage(role = "user", content = input)
                        messages = messages + userMessage

                        // Save session with user message
                        saveSession(messages)

                        // Use streaming if available, otherwise fall back to regular
                        if (onSendMessageStream != null) {
                            scope.launch {
                                isStreaming = true
                                streamingContent = ""
                                try {
                                    onSendMessageStream(messages).collect { chunk ->
                                        streamingContent += chunk
                                    }
                                    // Stream complete - add final message
                                    if (streamingContent.isNotEmpty()) {
                                        val assistantMessage = ChatMessage(
                                            role = "assistant",
                                            content = streamingContent
                                        )
                                        messages = messages + assistantMessage
                                        saveSession(messages)
                                    } else {
                                        error = "No response received"
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "Streaming error"
                                    // If we got partial content, still save it
                                    if (streamingContent.isNotEmpty()) {
                                        val assistantMessage = ChatMessage(
                                            role = "assistant",
                                            content = streamingContent + "\n\n[Stream interrupted]"
                                        )
                                        messages = messages + assistantMessage
                                        saveSession(messages)
                                    }
                                } finally {
                                    isStreaming = false
                                    streamingContent = ""
                                }
                            }
                        } else {
                            // Fall back to non-streaming
                            scope.launch {
                                isLoading = true
                                try {
                                    val response = onSendMessage(messages, input)
                                    if (response != null) {
                                        messages = messages + response
                                        saveSession(messages)
                                    } else {
                                        error = "Failed to get response"
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "Unknown error"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                },
                enabled = userInput.isNotBlank() && !isLoading && !isStreaming,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text("Send")
            }
        }
    }
}

/**
 * A streaming message bubble that shows content as it arrives with animated lines.
 */
@Composable
private fun StreamingMessageBubble(content: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Assistant",
                    color = Color(0xFF6B9BFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Typing indicator
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = Color(0xFF6B9BFF),
                    strokeWidth = 1.dp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Animated lines display
            AnimatedTextLines(content = content)
        }
    }
}

/**
 * Displays text content with animated line-by-line appearance.
 * Each line animates in with a 500ms delay.
 */
@Composable
private fun AnimatedTextLines(content: String) {
    val lines = content.split("\n")
    var visibleLineCount by remember { mutableIntStateOf(0) }

    // Animate new lines appearing
    LaunchedEffect(lines.size) {
        while (visibleLineCount < lines.size) {
            delay(500) // 0.5 second per line
            visibleLineCount = minOf(visibleLineCount + 1, lines.size)
        }
    }

    // Reset when content changes significantly (new message)
    LaunchedEffect(content.take(50)) {
        if (lines.size < visibleLineCount) {
            visibleLineCount = lines.size
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            val alpha by animateFloatAsState(
                targetValue = if (index < visibleLineCount) 1f else 0f,
                animationSpec = tween(durationMillis = 300),
                label = "lineAlpha"
            )
            Text(
                text = line,
                color = Color.White.copy(alpha = alpha),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * A single chat message bubble - full width.
 */
@Composable
private fun ChatMessageBubble(message: ChatMessage, userName: String = "You") {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) Color(0xFF8B5CF6) else Color(0xFF2A2A2A)

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = if (isUser) userName else "Assistant",
                color = if (isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF6B9BFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.content,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Chat History screen.
 * Shows all saved chat sessions with pagination.
 */
@Composable
fun ChatHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectSession: (String) -> Unit
) {
    BackHandler { onNavigateBack() }

    var allSessions by remember { mutableStateOf(ChatHistoryManager.getAllSessions()) }
    var currentPage by remember { mutableIntStateOf(0) }

    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Calculate page size based on available height
        // Title bar ~48dp, pagination controls ~48dp, bottom pagination ~48dp, spacing ~32dp = ~176dp overhead
        // Each row is approximately 80dp (with provider, model, date info)
        val availableHeight = maxHeight - 176.dp
        val rowHeight = 80.dp
        val pageSize = maxOf(1, (availableHeight / rowHeight).toInt())

        val totalPages = (allSessions.size + pageSize - 1) / pageSize
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, allSessions.size)
        val currentPageSessions = if (allSessions.isNotEmpty() && startIndex < allSessions.size) {
            allSessions.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Reset to valid page if needed
        LaunchedEffect(pageSize, allSessions.size) {
            if (currentPage >= totalPages && totalPages > 0) {
                currentPage = totalPages - 1
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AiTitleBar(
                title = "Chat History",
                onBackClick = onNavigateBack,
                onAiClick = onNavigateHome
            )

            Spacer(modifier = Modifier.height(16.dp))

        if (allSessions.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No chat history yet. Start a new chat to begin.",
                    color = Color(0xFFAAAAAA),
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            // Pagination controls at top
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Page ${currentPage + 1} of $totalPages (${allSessions.size} chats)",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )

                Row {
                    TextButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Text("< Previous", color = if (currentPage > 0) Color(0xFF6B9BFF) else Color(0xFF555555))
                    }

                    TextButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1
                    ) {
                        Text("Next >", color = if (currentPage < totalPages - 1) Color(0xFF6B9BFF) else Color(0xFF555555))
                    }
                }
            }

            // Chat sessions list
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn {
                    items(currentPageSessions) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(session.id) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.preview + if (session.preview.length >= 50) "..." else "",
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        text = session.provider.displayName,
                                        color = Color(0xFF6B9BFF),
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = " • ${session.model}",
                                        color = Color(0xFF888888),
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = dateFormat.format(Date(session.updatedAt)),
                                    color = Color(0xFF666666),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = ">",
                                color = Color(0xFF6B9BFF),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        HorizontalDivider(color = Color(0xFF444444))
                    }
                }
            }

            // Bottom pagination controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Showing ${startIndex + 1}-$endIndex",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )

                Row {
                    TextButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Text("< Previous", color = if (currentPage > 0) Color(0xFF6B9BFF) else Color(0xFF555555))
                    }

                    TextButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1
                    ) {
                        Text("Next >", color = if (currentPage < totalPages - 1) Color(0xFF6B9BFF) else Color(0xFF555555))
                    }
                }
            }
        }
        }
    }
}

/**
 * Helper class to return 4 values.
 */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

/**
 * AI Chats Hub screen - shows options for starting or continuing chats.
 */
@Composable
fun AiChatsHubScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToAgentSelect: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChatHistory: () -> Unit,
    onNavigateToChatSearch: () -> Unit
) {
    // Check if there are any agents configured (with their own or provider's API key)
    val hasAgents = aiSettings.agents.any { agent ->
        aiSettings.getEffectiveApiKeyForAgent(agent).isNotBlank() && (developerMode || agent.provider != AiService.DUMMY)
    }

    // Check if there are any chat sessions
    val hasChatHistory = remember { ChatHistoryManager.getSessionCount() > 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AiTitleBar(
            title = "AI Chat",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // New chat based on an AI Agent
            ChatHubCard(
                icon = "\uD83E\uDD16",
                title = "New chat based on an AI Agent",
                description = "Start a chat using a pre-configured agent",
                onClick = onNavigateToAgentSelect,
                enabled = hasAgents
            )

            // New chat - configure on the fly
            ChatHubCard(
                icon = "\u2699\uFE0F",
                title = "New chat – configure on the fly",
                description = "Select provider, model, and parameters",
                onClick = onNavigateToNewChat,
                enabled = true
            )

            // Continue with an existing chat
            ChatHubCard(
                icon = "\uD83D\uDCAC",
                title = "Continue with an existing chat",
                description = "Resume a previous conversation",
                onClick = onNavigateToChatHistory,
                enabled = hasChatHistory
            )

            // Search in existing chats
            ChatHubCard(
                icon = "\uD83D\uDD0D",
                title = "Search in existing chats",
                description = "Find messages in your chat history",
                onClick = onNavigateToChatSearch,
                enabled = hasChatHistory
            )
        }
    }
}

/**
 * Card component for the AI Chats Hub screen.
 */
@Composable
private fun ChatHubCard(
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable { onClick() }
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFF2A3A4A) else Color(0xFF3A3A3A)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 32.sp,
                modifier = if (enabled) Modifier else Modifier.alpha(0.5f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else Color(0xFF888888)
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = if (enabled) Color(0xFFAAAAAA) else Color(0xFF666666)
                )
            }
            if (enabled) {
                Text(
                    text = ">",
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

/**
 * Screen to select an AI Agent for chat.
 */
@Composable
fun ChatAgentSelectScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectAgent: (String) -> Unit
) {
    // Filter agents - only show those with API keys configured (their own or provider's)
    val availableAgents = aiSettings.agents.filter { agent ->
        aiSettings.getEffectiveApiKeyForAgent(agent).isNotBlank() && (developerMode || agent.provider != AiService.DUMMY)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AiTitleBar(
            title = "Select Agent",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        if (availableAgents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No agents configured.\nGo to AI Setup > Agents to create one.",
                    color = Color(0xFF888888),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableAgents) { agent ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAgent(agent.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A3A4A)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = agent.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        text = agent.provider.displayName,
                                        color = Color(0xFF6B9BFF),
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = " • ${agent.model.ifBlank { agent.provider.defaultModel }}",
                                        color = Color(0xFF888888),
                                        fontSize = 13.sp
                                    )
                                }
                                // Show system prompt preview if configured
                                agent.parameters.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = prompt.take(80) + if (prompt.length > 80) "..." else "",
                                        color = Color(0xFF666666),
                                        fontSize = 12.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                            Text(
                                text = ">",
                                color = Color(0xFF6B9BFF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen to search in existing chats.
 */
@Composable
fun ChatSearchScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectSession: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ChatSearchResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the search field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AiTitleBar(
            title = "Search Chats",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Search in messages...", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFF6B9BFF)
                ),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                searchResults = searchInChats(searchQuery)
                                hasSearched = true
                            }
                        },
                        enabled = searchQuery.isNotBlank()
                    ) {
                        Text("Search", color = if (searchQuery.isNotBlank()) Color(0xFF6B9BFF) else Color(0xFF555555))
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Results
            if (!hasSearched) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Enter a search term to find messages",
                        color = Color(0xFF888888)
                    )
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matches found for \"$searchQuery\"",
                        color = Color(0xFF888888)
                    )
                }
            } else {
                Text(
                    text = "${searchResults.size} match${if (searchResults.size != 1) "es" else ""} found",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults) { result ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(result.sessionId) },
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF2A3A4A)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                // Session info
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = result.sessionTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = dateFormat.format(Date(result.messageTimestamp)),
                                        color = Color(0xFF666666),
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                // Message role
                                Text(
                                    text = result.messageRole.replaceFirstChar { it.uppercase() },
                                    color = if (result.messageRole == "user") Color(0xFF6B9BFF) else Color(0xFF4CAF50),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // Message preview with highlighted search term
                                Text(
                                    text = result.messagePreview,
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 13.sp,
                                    maxLines = 3
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search result for chat search.
 */
private data class ChatSearchResult(
    val sessionId: String,
    val sessionTitle: String,
    val messageRole: String,
    val messagePreview: String,
    val messageTimestamp: Long
)

/**
 * Search in all chat sessions for a query.
 */
private fun searchInChats(query: String): List<ChatSearchResult> {
    val results = mutableListOf<ChatSearchResult>()
    val sessions = ChatHistoryManager.getAllSessions()
    val lowerQuery = query.lowercase()

    for (session in sessions) {
        val fullSession = ChatHistoryManager.loadSession(session.id) ?: continue
        for (message in fullSession.messages) {
            if (message.content.lowercase().contains(lowerQuery)) {
                // Create a preview with context around the match
                val content = message.content
                val matchIndex = content.lowercase().indexOf(lowerQuery)
                val start = (matchIndex - 40).coerceAtLeast(0)
                val end = (matchIndex + query.length + 40).coerceAtMost(content.length)
                val preview = (if (start > 0) "..." else "") +
                        content.substring(start, end) +
                        (if (end < content.length) "..." else "")

                results.add(
                    ChatSearchResult(
                        sessionId = session.id,
                        sessionTitle = fullSession.preview.ifBlank { "Chat with ${session.provider.displayName}" },
                        messageRole = message.role,
                        messagePreview = preview,
                        messageTimestamp = session.updatedAt
                    )
                )
            }
        }
    }

    return results.sortedByDescending { it.messageTimestamp }
}
