package com.ai.ui.chat

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AppViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// ===== Provider Selection =====

@Composable
fun ChatSelectProviderScreen(
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectProvider: (AppService) -> Unit
) {
    BackHandler { onNavigateBack() }

    val activeProviders = remember(aiSettings) {
        aiSettings.getActiveServices().sortedBy { it.displayName }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Select Provider", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        if (activeProviders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No providers configured.\nGo to AI Setup to add providers.", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn {
                    items(activeProviders, key = { it.id }) { provider ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelectProvider(provider) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(provider.displayName, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                            Text(">", fontSize = 16.sp, color = AppColors.Blue)
                        }
                        HorizontalDivider(color = AppColors.DividerDark)
                    }
                }
            }
        }
    }
}

// ===== Parameters Screen =====

@Composable
fun ChatParametersScreen(
    provider: AppService,
    model: String,
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onStartChat: (ChatParameters) -> Unit
) {
    BackHandler { onNavigateBack() }

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
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }

    if (showParamsDialog) {
        ParametersSelectorDialog(
            aiSettings = aiSettings, selectedIds = selectedParametersIds,
            onConfirm = { selectedParametersIds = it; showParamsDialog = false },
            onDismiss = { showParamsDialog = false }
        )
    }
    if (showSystemPromptDialog) {
        SystemPromptSelectorDialog(
            aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { id ->
                selectedSystemPromptId = id
                if (id != null) systemPrompt = aiSettings.getSystemPromptById(id)?.prompt ?: systemPrompt
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Chat Parameters", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Text("${provider.displayName} / $model", fontSize = 12.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val spName = selectedSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
                OutlinedButton(
                    onClick = { showSystemPromptDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = if (spName != null) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) { Text(spName ?: "System Prompt", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }

                val pNames = selectedParametersIds.mapNotNull { aiSettings.getParametersById(it)?.name }
                OutlinedButton(
                    onClick = { showParamsDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = if (pNames.isNotEmpty()) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) { Text(if (pNames.isNotEmpty()) pNames.joinToString(", ") else "Parameters", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }

            OutlinedTextField(
                value = systemPrompt, onValueChange = { systemPrompt = it; selectedSystemPromptId = null },
                label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 5, colors = AppColors.outlinedFieldColors()
            )
            OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text("Temperature (0.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text("Max tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text("Top P (0.0 - 1.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = topK, onValueChange = { topK = it }, label = { Text("Top K") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = frequencyPenalty, onValueChange = { frequencyPenalty = it }, label = { Text("Frequency penalty (-2.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = presencePenalty, onValueChange = { presencePenalty = it }, label = { Text("Presence penalty (-2.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())

            Row(modifier = Modifier.fillMaxWidth().clickable { searchEnabled = !searchEnabled }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = searchEnabled, onCheckedChange = { searchEnabled = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Enable web search")
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { returnCitations = !returnCitations }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = returnCitations, onCheckedChange = { returnCitations = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Return citations")
            }
            OutlinedTextField(value = searchRecency, onValueChange = { searchRecency = it }, label = { Text("Search recency (day, week, month, year)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val presetParams = aiSettings.mergeParameters(selectedParametersIds)
                val resolvedSp = selectedSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: systemPrompt
                onStartChat(
                    ChatParameters(
                        systemPrompt = resolvedSp,
                        temperature = temperature.toFloatOrNull() ?: presetParams?.temperature,
                        maxTokens = maxTokens.toIntOrNull() ?: presetParams?.maxTokens,
                        topP = topP.toFloatOrNull() ?: presetParams?.topP,
                        topK = topK.toIntOrNull() ?: presetParams?.topK,
                        frequencyPenalty = frequencyPenalty.toFloatOrNull() ?: presetParams?.frequencyPenalty,
                        presencePenalty = presencePenalty.toFloatOrNull() ?: presetParams?.presencePenalty,
                        searchEnabled = searchEnabled || (presetParams?.searchEnabled == true),
                        returnCitations = returnCitations && (presetParams?.returnCitations != false),
                        searchRecency = searchRecency.takeIf { it.isNotBlank() } ?: presetParams?.searchRecency
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Start Chat", fontSize = 16.sp, maxLines = 1, softWrap = false) }
    }
}

// ===== Chat Session =====

@Composable
fun ChatSessionScreen(
    provider: AppService,
    model: String,
    parameters: ChatParameters,
    userName: String,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSendMessage: suspend (List<ChatMessage>, ChatParameters) -> ChatMessage,
    onSendMessageStream: (List<ChatMessage>) -> Flow<String>,
    onRecordStatistics: (Int, Int) -> Unit,
    initialMessages: List<ChatMessage> = emptyList(),
    sessionId: String? = null
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val currentSessionId = remember { sessionId ?: java.util.UUID.randomUUID().toString() }

    var messages by remember { mutableStateOf(initialMessages) }
    var userInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var streamingContent by remember { mutableStateOf("") }
    var totalInputTokens by remember { mutableIntStateOf(0) }
    var totalOutputTokens by remember { mutableIntStateOf(0) }
    var totalCost by remember { mutableDoubleStateOf(0.0) }

    val pricing = remember(provider, model) { PricingCache.getPricing(context, provider, model) }

    fun saveSession(msgs: List<ChatMessage>) {
        ChatHistoryManager.saveSession(
            ChatSession(id = currentSessionId, provider = provider, model = model, messages = msgs, parameters = parameters, updatedAt = System.currentTimeMillis())
        )
    }

    // System prompt initialization
    LaunchedEffect(parameters.systemPrompt) {
        if (parameters.systemPrompt.isNotBlank() && messages.isEmpty()) {
            messages = listOf(ChatMessage(role = "system", content = parameters.systemPrompt))
        }
    }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    // Auto-scroll
    val displayMessages = messages.filter { it.role != "system" }
    val bottomItemCount = displayMessages.size + (if (isStreaming && streamingContent.isNotEmpty()) 1 else 0) + (if (isLoading || (isStreaming && streamingContent.isEmpty())) 1 else 0)
    LaunchedEffect(messages.size, streamingContent) {
        if (bottomItemCount > 0) listState.animateScrollToItem(bottomItemCount - 1)
    }

    fun sendMessage(input: String) {
        val userMessage = ChatMessage(role = "user", content = input)
        messages = messages + userMessage
        userInput = ""; error = null
        saveSession(messages)

        val inputTokens = messages.sumOf { AppViewModel.estimateTokens(it.content) }
        totalInputTokens += inputTokens

        scope.launch {
            isStreaming = true; streamingContent = ""
            val sb = StringBuilder()
            try {
                onSendMessageStream(messages).collect { chunk -> sb.append(chunk); streamingContent = sb.toString() }
                val assistantMsg = ChatMessage(role = "assistant", content = streamingContent)
                messages = messages + assistantMsg
                saveSession(messages)
                val outputTokens = AppViewModel.estimateTokens(streamingContent)
                totalOutputTokens += outputTokens
                totalCost += inputTokens * pricing.promptPrice * 100 + outputTokens * pricing.completionPrice * 100
                onRecordStatistics(inputTokens, outputTokens)
            } catch (e: Exception) {
                error = e.message ?: "Streaming error"
                if (sb.isNotEmpty()) {
                    messages = messages + ChatMessage(role = "assistant", content = "$sb\n\n[Stream interrupted: ${e.message}]")
                    saveSession(messages)
                }
            } finally {
                isStreaming = false; streamingContent = ""
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            title = "Chat", onBackClick = onNavigateBack, onAiClick = onNavigateHome,
            leftContent = {
                TextButton(onClick = onNavigateBack) { Text("< Back", color = Color.White, fontSize = 16.sp, maxLines = 1, softWrap = false) }
                if (totalCost > 0) {
                    Text("%.2fc".format(totalCost), fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
        )
        Text("${provider.displayName} / $model", fontSize = 12.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)
        ) {
            if (displayMessages.isEmpty() && !isStreaming && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start a conversation...", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(displayMessages, key = { it.timestamp }) { msg ->
                        ChatMessageBubble(msg, userName)
                    }
                    if (isStreaming && streamingContent.isNotEmpty()) {
                        item(key = "streaming") { StreamingMessageBubble(streamingContent) }
                    }
                    if (isLoading || (isStreaming && streamingContent.isEmpty())) {
                        item(key = "loading") {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AppColors.Blue, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Thinking...", color = AppColors.TextTertiary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        if (error != null) {
            Text(error!!, color = AppColors.Red, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = userInput, onValueChange = { userInput = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Type a message...") },
                maxLines = 4, colors = AppColors.outlinedFieldColors()
            )
            Button(
                onClick = { if (userInput.isNotBlank() && !isLoading && !isStreaming) sendMessage(userInput.trim()) },
                enabled = userInput.isNotBlank() && !isLoading && !isStreaming,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) { Text("Send", maxLines = 1, softWrap = false) }
        }
    }
}

// ===== Message Bubbles =====

@Composable
private fun ChatMessageBubble(message: ChatMessage, userName: String = "You") {
    val isUser = message.role == "user"
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) AppColors.Purple.copy(alpha = 0.15f) else AppColors.SurfaceDark
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (isUser) userName else "Assistant",
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (isUser) AppColors.Purple else AppColors.Blue
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(message.content, fontSize = 14.sp, color = Color.White)
        }
    }
}

@Composable
private fun StreamingMessageBubble(content: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Assistant", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = AppColors.Blue)
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = AppColors.Blue, strokeWidth = 1.5.dp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            AnimatedTextLines(content)
        }
    }
}

@Composable
private fun AnimatedTextLines(content: String) {
    val lines = content.split("\n")
    var visibleLineCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(lines.size) {
        while (visibleLineCount < lines.size) {
            kotlinx.coroutines.delay(500)
            visibleLineCount = minOf(visibleLineCount + 1, lines.size)
        }
    }
    LaunchedEffect(content.take(50)) {
        if (lines.size < visibleLineCount) visibleLineCount = lines.size
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            val targetAlpha = if (index < visibleLineCount) 1f else 0f
            val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))
            Text(line, fontSize = 14.sp, color = Color.White, modifier = Modifier.alpha(alpha))
        }
    }
}

// ===== Shared Dialogs =====

@Composable
internal fun SystemPromptSelectorDialog(
    aiSettings: Settings,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select System Prompt", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))

                if (aiSettings.systemPrompts.isEmpty()) {
                    Text("No system prompts configured", color = AppColors.TextTertiary, fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        item(key = "__none__") {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedId == null, onClick = { onSelect(null) })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("None (manual)", color = Color.White)
                            }
                            HorizontalDivider(color = AppColors.DividerDark)
                        }
                        items(aiSettings.systemPrompts, key = { it.id }) { sp ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(sp.id) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedId == sp.id, onClick = { onSelect(sp.id) })
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(sp.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(sp.prompt.take(80), color = AppColors.TextTertiary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider(color = AppColors.DividerDark)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun ParametersSelectorDialog(
    aiSettings: Settings,
    selectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(selectedIds.toSet()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Parameter Presets", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))

                if (aiSettings.parameters.isEmpty()) {
                    Text("No parameter presets configured", color = AppColors.TextTertiary, fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(aiSettings.parameters, key = { it.id }) { params ->
                            val isChecked = params.id in current
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    current = if (isChecked) current - params.id else current + params.id
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = isChecked, onCheckedChange = {
                                    current = if (isChecked) current - params.id else current + params.id
                                })
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(params.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    val details = listOfNotNull(
                                        params.temperature?.let { "temp=$it" },
                                        params.maxTokens?.let { "max=$it" },
                                        params.topP?.let { "topP=$it" }
                                    ).joinToString(", ")
                                    if (details.isNotEmpty()) Text(details, color = AppColors.TextTertiary, fontSize = 12.sp)
                                }
                            }
                            HorizontalDivider(color = AppColors.DividerDark)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(current.toList()) }) { Text("OK", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
