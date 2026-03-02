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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ai.data.PricingCache
import androidx.compose.ui.platform.LocalContext
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
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectProvider: (AiService) -> Unit
) {
    BackHandler { onNavigateBack() }

    // Get active providers (status "ok"), sorted by display name
    val configuredProviders = aiSettings.getActiveServices()
        .sortedBy { it.displayName.lowercase() }

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
                colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No AI providers configured. Please add API keys in Settings > AI Setup.",
                    color = AiColors.TextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark),
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
                                color = AiColors.Blue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (index < configuredProviders.size - 1) {
                            HorizontalDivider(color = AiColors.BorderUnfocused)
                        }
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
    aiSettings: AiSettings = AiSettings(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onStartChat: (ChatParameters) -> Unit
) {
    BackHandler { onNavigateBack() }

    val supportedParams = ALL_AGENT_PARAMETERS

    // Parameter state
    var systemPrompt by remember { mutableStateOf("") }
    var selectedSystemPromptId by remember { mutableStateOf<String?>(null) }
    var selectedParametersIds by remember { mutableStateOf<List<String>>(emptyList()) }
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
            color = AiColors.Blue,
            fontSize = 14.sp
        )
        Text(
            text = "Model: $model",
            color = AiColors.TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Start Chat button at top
        Button(
            onClick = {
                // Resolve system prompt: selected preset overrides manual text
                val effectiveSystemPrompt = selectedSystemPromptId?.let {
                    aiSettings.getSystemPromptById(it)?.prompt
                } ?: systemPrompt

                // Resolve parameter presets and merge with manual overrides
                val presetParams = aiSettings.mergeParameters(selectedParametersIds)

                onStartChat(ChatParameters(
                    systemPrompt = effectiveSystemPrompt.ifBlank {
                        presetParams?.systemPrompt ?: ""
                    },
                    temperature = temperature.toFloatOrNull() ?: presetParams?.temperature,
                    maxTokens = maxTokens.toIntOrNull() ?: presetParams?.maxTokens,
                    topP = topP.toFloatOrNull() ?: presetParams?.topP,
                    topK = topK.toIntOrNull() ?: presetParams?.topK,
                    frequencyPenalty = frequencyPenalty.toFloatOrNull() ?: presetParams?.frequencyPenalty,
                    presencePenalty = presencePenalty.toFloatOrNull() ?: presetParams?.presencePenalty,
                    searchEnabled = searchEnabled || (presetParams?.searchEnabled == true),
                    returnCitations = returnCitations,
                    searchRecency = searchRecency.ifBlank { presetParams?.searchRecency }
                ))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Start Chat")
        }

        // System prompt + Parameters selectors
        var showParamsDialog by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SystemPromptSelector(
                    aiSettings = aiSettings,
                    selectedSystemPromptId = selectedSystemPromptId,
                    onSystemPromptSelected = { id -> selectedSystemPromptId = id }
                )
            }
            Button(
                onClick = { showParamsDialog = true },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AiColors.Indigo)
            ) {
                Text(
                    if (selectedParametersIds.isNotEmpty()) "⚙ Parameters" else "Parameters",
                    fontSize = 14.sp, maxLines = 1
                )
            }
        }
        if (showParamsDialog) {
            ParametersSelectorDialog(
                aiSettings = aiSettings,
                selectedParametersIds = selectedParametersIds,
                onParamsSelected = { ids ->
                    selectedParametersIds = ids
                    showParamsDialog = false
                },
                onDismiss = { showParamsDialog = false }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                    label = { Text("System Prompt (manual override)") },
                    placeholder = { Text("Optional instructions for the AI...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
                        focusedBorderColor = AiColors.Blue,
                        unfocusedBorderColor = AiColors.BorderUnfocused
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
    parameters: ChatParameters,
    userName: String = "user",
    initialMessages: List<ChatMessage> = emptyList(),
    sessionId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSendMessage: suspend (List<ChatMessage>, String) -> ChatMessage,
    onSendMessageStream: ((List<ChatMessage>) -> Flow<String>)? = null,
    onRecordStatistics: (Int, Int) -> Unit = { _, _ -> }
) {
    BackHandler { onNavigateBack() }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    // Chat state - use initial messages if provided (for continuing a session)
    var messages by remember { mutableStateOf(initialMessages) }
    var userInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Streaming state
    var isStreaming by remember { mutableStateOf(false) }
    var streamingContent by remember { mutableStateOf("") }

    // Cost tracking state
    var totalInputTokens by remember { mutableIntStateOf(0) }
    var totalOutputTokens by remember { mutableIntStateOf(0) }
    var totalCost by remember { mutableDoubleStateOf(0.0) }

    // Get pricing for this model
    val pricing = remember(provider, model) {
        PricingCache.getPricing(context, provider, model)
    }

    // Helper to estimate tokens from text
    fun estimateTokens(text: String): Int = AiViewModel.estimateTokens(text)

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

    // Auto-scroll to bottom when new messages arrive or streaming content changes
    val displayMessages = messages.filter { it.role != "system" }
    val bottomItemCount = displayMessages.size +
        (if (isStreaming && streamingContent.isNotEmpty()) 1 else 0) +
        (if (isStreaming && streamingContent.isEmpty()) 1 else 0) +
        (if (isLoading) 1 else 0)
    LaunchedEffect(messages.size, streamingContent) {
        if (bottomItemCount > 0) {
            listState.animateScrollToItem(bottomItemCount - 1)
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${provider.displayName} / $model",
                color = AiColors.Blue,
                fontSize = 14.sp
            )
            if (totalCost > 0) {
                Text(
                    text = "${formatCents(totalCost)} ¢",
                    color = AiColors.Green,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

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
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(
                        text = "Start a conversation...",
                        color = Color(0xFF666666),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
                ) {
                    // Messages in order (oldest first, newest at bottom)
                    items(displayMessages, key = { it.timestamp }) { message ->
                        ChatMessageBubble(message = message, userName = userName)
                    }

                    // Show streaming message at bottom
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
                                    color = AiColors.Blue,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Thinking...",
                                    color = AiColors.TextTertiary,
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
                                    color = AiColors.Blue,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Thinking...",
                                    color = AiColors.TextTertiary,
                                    fontSize = 14.sp
                                )
                            }
                        }
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
                    focusedBorderColor = AiColors.Blue,
                    unfocusedBorderColor = AiColors.BorderUnfocused
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

                        // Track input tokens (user message + all previous messages for context)
                        val inputTokensForThisRequest = messages.sumOf { estimateTokens(it.content) }
                        totalInputTokens += inputTokensForThisRequest

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

                                        // Track output tokens and calculate cost
                                        val outputTokens = estimateTokens(streamingContent)
                                        totalOutputTokens += outputTokens
                                        totalCost += (inputTokensForThisRequest * pricing.promptPrice) +
                                                (outputTokens * pricing.completionPrice)
                                        // Record statistics
                                        onRecordStatistics(inputTokensForThisRequest, outputTokens)
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
                                    messages = messages + response
                                    saveSession(messages)

                                    // Track output tokens and calculate cost
                                    val outputTokens = estimateTokens(response.content)
                                    totalOutputTokens += outputTokens
                                    totalCost += (inputTokensForThisRequest * pricing.promptPrice) +
                                            (outputTokens * pricing.completionPrice)
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
        colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Assistant",
                    color = AiColors.Blue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Typing indicator
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = AiColors.Blue,
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
    val backgroundColor = if (isUser) Color(0xFF8B5CF6) else AiColors.SurfaceDark

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
                color = if (isUser) Color.White.copy(alpha = 0.7f) else AiColors.Blue,
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
    var currentPage by rememberSaveable { mutableStateOf(0) }

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
                colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No chat history yet. Start a new chat to begin.",
                    color = AiColors.TextSecondary,
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
                    color = AiColors.TextSecondary,
                    fontSize = 12.sp
                )

                Row {
                    TextButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Text("< Previous", color = if (currentPage > 0) AiColors.Blue else Color(0xFF555555))
                    }

                    TextButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1
                    ) {
                        Text("Next >", color = if (currentPage < totalPages - 1) AiColors.Blue else Color(0xFF555555))
                    }
                }
            }

            // Chat sessions list
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn {
                    items(currentPageSessions, key = { it.id }) { session ->
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
                                        color = AiColors.Blue,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = " • ${session.model}",
                                        color = AiColors.TextTertiary,
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
                                color = AiColors.Blue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        HorizontalDivider(color = AiColors.BorderUnfocused)
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
                    color = AiColors.TextTertiary,
                    fontSize = 12.sp
                )

                Row {
                    TextButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Text("< Previous", color = if (currentPage > 0) AiColors.Blue else Color(0xFF555555))
                    }

                    TextButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1
                    ) {
                        Text("Next >", color = if (currentPage < totalPages - 1) AiColors.Blue else Color(0xFF555555))
                    }
                }
            }
        }
        }
    }
}

/**
 * AI Chats Hub screen - shows options for starting or continuing chats.
 */
@Composable
fun AiChatsHubScreen(
    aiSettings: AiSettings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToAgentSelect: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChatHistory: () -> Unit,
    onNavigateToChatSearch: () -> Unit,
    onNavigateToDualChat: () -> Unit = {}
) {
    // Check if there are any agents with an active provider
    val hasAgents = aiSettings.agents.any { agent ->
        aiSettings.getEffectiveApiKeyForAgent(agent).isNotBlank() && aiSettings.isProviderActive(agent.provider)
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
                title = "New chat - start with an AI Agent",
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

            // Let 2 AI models chat
            ChatHubCard(
                icon = "\uD83E\uDD1C\uD83E\uDD1B",
                title = "Let 2 AI models chat",
                description = "Watch two models converse on a topic",
                onClick = onNavigateToDualChat,
                enabled = true
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
                    color = if (enabled) Color.White else AiColors.TextTertiary
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = if (enabled) AiColors.TextSecondary else Color(0xFF666666)
                )
            }
            if (enabled) {
                Text(
                    text = ">",
                    color = AiColors.Blue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
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
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ChatSearchResult>>(emptyList()) }
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the search field and restore search results if query was saved
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (searchQuery.isNotBlank() && hasSearched) {
            searchResults = searchInChats(searchQuery)
        }
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
                placeholder = { Text("Search in messages...", color = AiColors.TextTertiary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = AiColors.Blue,
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = AiColors.Blue
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
                        Text("Search", color = if (searchQuery.isNotBlank()) AiColors.Blue else Color(0xFF555555))
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
                        color = AiColors.TextTertiary
                    )
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matches found for \"$searchQuery\"",
                        color = AiColors.TextTertiary
                    )
                }
            } else {
                Text(
                    text = "${searchResults.size} match${if (searchResults.size != 1) "es" else ""} found",
                    color = AiColors.TextTertiary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults, key = { "${it.sessionId}:${it.messageTimestamp}" }) { result ->
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
                                    color = if (result.messageRole == "user") AiColors.Blue else AiColors.Green,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // Message preview with highlighted search term
                                Text(
                                    text = result.messagePreview,
                                    color = AiColors.TextSecondary,
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
