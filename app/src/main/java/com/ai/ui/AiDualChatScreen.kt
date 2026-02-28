package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.ai.data.AiService
import com.ai.data.PricingCache
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val DUAL_CHAT_PREFS = "dual_chat_prefs"
private const val KEY_MODEL1_PROVIDER = "model1_provider"
private const val KEY_MODEL1_NAME = "model1_name"
private const val KEY_MODEL1_SYSTEM = "model1_system"
private const val KEY_MODEL1_PARAMS = "model1_params"
private const val KEY_MODEL2_PROVIDER = "model2_provider"
private const val KEY_MODEL2_NAME = "model2_name"
private const val KEY_MODEL2_SYSTEM = "model2_system"
private const val KEY_MODEL2_PARAMS = "model2_params"
private const val KEY_SUBJECT = "subject"
private const val KEY_INTERACTION_COUNT = "interaction_count"
private const val KEY_MODEL1_SYSTEM_PROMPT_ID = "model1_system_prompt_id"
private const val KEY_MODEL2_SYSTEM_PROMPT_ID = "model2_system_prompt_id"
private const val KEY_FIRST_PROMPT = "first_prompt"
private const val KEY_SECOND_PROMPT = "second_prompt"
private const val DEFAULT_FIRST_PROMPT = "Let's talk about %subject%"
private const val DEFAULT_SECOND_PROMPT = "What do you think about: %answer%"

private val gson = Gson()

private fun loadChatParams(prefs: android.content.SharedPreferences, key: String): ChatParameters {
    val json = prefs.getString(key, null) ?: return ChatParameters()
    return try { gson.fromJson(json, ChatParameters::class.java) } catch (e: Exception) { ChatParameters() }
}

/** Summarize non-default params for display. */
private fun ChatParameters.summary(): String {
    val parts = mutableListOf<String>()
    temperature?.let { parts.add("temp=%.1f".format(it)) }
    maxTokens?.let { parts.add("max=$it") }
    topP?.let { parts.add("topP=%.1f".format(it)) }
    topK?.let { parts.add("topK=$it") }
    frequencyPenalty?.let { parts.add("freq=%.1f".format(it)) }
    presencePenalty?.let { parts.add("pres=%.1f".format(it)) }
    return if (parts.isEmpty()) "Default" else parts.joinToString(", ")
}

/**
 * Setup screen for dual AI chat - select two models and a subject.
 */
@Composable
fun DualChatSetupScreen(
    aiSettings: AiSettings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onStartSession: (DualChatConfig) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(DUAL_CHAT_PREFS, Context.MODE_PRIVATE) }

    var model1Provider by remember { mutableStateOf(prefs.getString(KEY_MODEL1_PROVIDER, null)?.let { AiService.findById(it) }) }
    var model1Name by remember { mutableStateOf(prefs.getString(KEY_MODEL1_NAME, "") ?: "") }
    var model1SystemPrompt by remember { mutableStateOf(prefs.getString(KEY_MODEL1_SYSTEM, "") ?: "") }
    var model1Params by remember { mutableStateOf(loadChatParams(prefs, KEY_MODEL1_PARAMS)) }
    var model2Provider by remember { mutableStateOf(prefs.getString(KEY_MODEL2_PROVIDER, null)?.let { AiService.findById(it) }) }
    var model2Name by remember { mutableStateOf(prefs.getString(KEY_MODEL2_NAME, "") ?: "") }
    var model2SystemPrompt by remember { mutableStateOf(prefs.getString(KEY_MODEL2_SYSTEM, "") ?: "") }
    var model2Params by remember { mutableStateOf(loadChatParams(prefs, KEY_MODEL2_PARAMS)) }
    var model1SystemPromptId by remember { mutableStateOf(prefs.getString(KEY_MODEL1_SYSTEM_PROMPT_ID, null)) }
    var model2SystemPromptId by remember { mutableStateOf(prefs.getString(KEY_MODEL2_SYSTEM_PROMPT_ID, null)) }
    var subject by remember { mutableStateOf(prefs.getString(KEY_SUBJECT, "") ?: "") }
    var interactionCount by remember { mutableStateOf(prefs.getString(KEY_INTERACTION_COUNT, "10") ?: "10") }
    var firstPrompt by remember { mutableStateOf(prefs.getString(KEY_FIRST_PROMPT, DEFAULT_FIRST_PROMPT) ?: DEFAULT_FIRST_PROMPT) }
    var secondPrompt by remember { mutableStateOf(prefs.getString(KEY_SECOND_PROMPT, DEFAULT_SECOND_PROMPT) ?: DEFAULT_SECOND_PROMPT) }

    // Save all values to prefs
    fun savePrefs() {
        prefs.edit()
            .putString(KEY_MODEL1_PROVIDER, model1Provider?.id)
            .putString(KEY_MODEL1_NAME, model1Name)
            .putString(KEY_MODEL1_SYSTEM, model1SystemPrompt)
            .putString(KEY_MODEL1_PARAMS, gson.toJson(model1Params))
            .putString(KEY_MODEL2_PROVIDER, model2Provider?.id)
            .putString(KEY_MODEL2_NAME, model2Name)
            .putString(KEY_MODEL2_SYSTEM, model2SystemPrompt)
            .putString(KEY_MODEL2_PARAMS, gson.toJson(model2Params))
            .putString(KEY_MODEL1_SYSTEM_PROMPT_ID, model1SystemPromptId)
            .putString(KEY_MODEL2_SYSTEM_PROMPT_ID, model2SystemPromptId)
            .putString(KEY_SUBJECT, subject)
            .putString(KEY_INTERACTION_COUNT, interactionCount)
            .putString(KEY_FIRST_PROMPT, firstPrompt)
            .putString(KEY_SECOND_PROMPT, secondPrompt)
            .apply()
    }

    // Full-screen overlay state: 0=none, 1=select model1, 2=select model2, 3=params model1, 4=params model2
    var overlayMode by remember { mutableStateOf(0) }

    // Model selection overlays
    if (overlayMode == 1 || overlayMode == 2) {
        SelectAllModelsScreen(
            aiSettings = aiSettings,
            onSelectModel = { provider, model ->
                if (overlayMode == 1) {
                    model1Provider = provider
                    model1Name = model
                } else {
                    model2Provider = provider
                    model2Name = model
                }
                overlayMode = 0
                savePrefs()
            },
            onBack = { overlayMode = 0 },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Parameters editing overlays
    if (overlayMode == 3 || overlayMode == 4) {
        val isModel1 = overlayMode == 3
        val params = if (isModel1) model1Params else model2Params
        val modelLabel = if (isModel1) "Model 1" else "Model 2"
        val color = if (isModel1) Color(0xFF4488CC) else Color(0xFF44AA66)
        DualChatParamsScreen(
            title = "$modelLabel Parameters",
            initialParams = params,
            color = color,
            onBack = { overlayMode = 0 },
            onNavigateHome = onNavigateHome,
            onApply = { newParams ->
                if (isModel1) model1Params = newParams else model2Params = newParams
                overlayMode = 0
                savePrefs()
            }
        )
        return
    }

    val canStart = model1Provider != null && model1Name.isNotBlank() &&
            model2Provider != null && model2Name.isNotBlank() &&
            subject.isNotBlank() &&
            (interactionCount.toIntOrNull() ?: 0) > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AiTitleBar(
            title = "AI Dual Chat",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Model 1
            ModelSelectionCard(
                label = "Model 1",
                providerName = model1Provider?.displayName,
                modelName = model1Name,
                systemPrompt = model1SystemPrompt,
                onSystemPromptChange = { model1SystemPrompt = it; savePrefs() },
                onSelectClick = { overlayMode = 1 },
                onParamsClick = { overlayMode = 3 },
                paramsSummary = model1Params.summary(),
                aiSettings = aiSettings,
                systemPromptId = model1SystemPromptId,
                onSystemPromptIdChange = { model1SystemPromptId = it; savePrefs() },
                color = Color(0xFF4488CC)
            )

            // Swap button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        val tmpProvider = model1Provider
                        val tmpName = model1Name
                        val tmpSystem = model1SystemPrompt
                        val tmpParams = model1Params
                        val tmpSystemPromptId = model1SystemPromptId
                        model1Provider = model2Provider
                        model1Name = model2Name
                        model1SystemPrompt = model2SystemPrompt
                        model1Params = model2Params
                        model1SystemPromptId = model2SystemPromptId
                        model2Provider = tmpProvider
                        model2Name = tmpName
                        model2SystemPrompt = tmpSystem
                        model2Params = tmpParams
                        model2SystemPromptId = tmpSystemPromptId
                        savePrefs()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Text("⇅ Swap", fontSize = 12.sp, color = Color.White)
                }
            }

            // Model 2
            ModelSelectionCard(
                label = "Model 2",
                providerName = model2Provider?.displayName,
                modelName = model2Name,
                systemPrompt = model2SystemPrompt,
                onSystemPromptChange = { model2SystemPrompt = it; savePrefs() },
                onSelectClick = { overlayMode = 2 },
                onParamsClick = { overlayMode = 4 },
                paramsSummary = model2Params.summary(),
                aiSettings = aiSettings,
                systemPromptId = model2SystemPromptId,
                onSystemPromptIdChange = { model2SystemPromptId = it; savePrefs() },
                color = Color(0xFF44AA66)
            )

            // Subject + Interactions on one row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it; savePrefs() },
                    label = { Text("Subject") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF555555)
                    )
                )
                OutlinedTextField(
                    value = interactionCount,
                    onValueChange = { interactionCount = it.filter { c -> c.isDigit() }; savePrefs() },
                    label = { Text("Rounds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF555555)
                    )
                )
            }

            // First prompt template
            OutlinedTextField(
                value = firstPrompt,
                onValueChange = { firstPrompt = it; savePrefs() },
                label = { Text("1st prompt (%subject%)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF4488CC),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )

            // Second prompt template
            OutlinedTextField(
                value = secondPrompt,
                onValueChange = { secondPrompt = it; savePrefs() },
                label = { Text("2nd prompt (%answer%)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF44AA66),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )

            Text(
                "From 3rd on: previous response is sent directly",
                fontSize = 11.sp,
                color = Color(0xFF666666)
            )
        }

        // Go button pinned at bottom
        Button(
            onClick = {
                if (canStart) {
                    savePrefs()
                    val resolved1 = model1SystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: model1SystemPrompt
                    val resolved2 = model2SystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: model2SystemPrompt
                    onStartSession(
                        DualChatConfig(
                            model1Provider = model1Provider!!,
                            model1Name = model1Name,
                            model1SystemPrompt = resolved1,
                            model1Params = model1Params,
                            model2Provider = model2Provider!!,
                            model2Name = model2Name,
                            model2SystemPrompt = resolved2,
                            model2Params = model2Params,
                            subject = subject,
                            interactionCount = interactionCount.toIntOrNull() ?: 10,
                            firstPrompt = firstPrompt,
                            secondPrompt = secondPrompt
                        )
                    )
                }
            },
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4488CC),
                disabledContainerColor = Color(0xFF333333)
            )
        ) {
            Text("Go", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ModelSelectionCard(
    label: String,
    providerName: String?,
    modelName: String,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    onSelectClick: () -> Unit,
    onParamsClick: () -> Unit,
    paramsSummary: String,
    aiSettings: AiSettings,
    systemPromptId: String?,
    onSystemPromptIdChange: (String?) -> Unit,
    color: Color
) {
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    val systemPromptName = systemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A3A4A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
                Text("  $paramsSummary", fontSize = 11.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { showSystemPromptDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (systemPromptId != null) Color(0xFF6C5CE7) else color.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (systemPromptName != null) systemPromptName else "System",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onParamsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("Params", color = Color.White, fontSize = 12.sp)
                }
            }
            Button(
                onClick = onSelectClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.3f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (providerName != null) "$providerName / $modelName" else "Select model",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text("System prompt (optional)", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = color.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
        }
    }

    if (showSystemPromptDialog) {
        SystemPromptSelectorDialog(
            aiSettings = aiSettings,
            selectedSystemPromptId = systemPromptId,
            onSystemPromptSelected = { id ->
                onSystemPromptIdChange(id)
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false }
        )
    }
}

/**
 * Full-screen overlay for editing chat parameters for a dual chat model.
 */
@Composable
private fun DualChatParamsScreen(
    title: String,
    initialParams: ChatParameters,
    color: Color,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onApply: (ChatParameters) -> Unit
) {
    var temperature by remember { mutableStateOf(initialParams.temperature?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(initialParams.maxTokens?.toString() ?: "") }
    var topP by remember { mutableStateOf(initialParams.topP?.toString() ?: "") }
    var topK by remember { mutableStateOf(initialParams.topK?.toString() ?: "") }
    var frequencyPenalty by remember { mutableStateOf(initialParams.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember { mutableStateOf(initialParams.presencePenalty?.toString() ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = title,
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onApply(ChatParameters(
                    temperature = temperature.toFloatOrNull(),
                    maxTokens = maxTokens.toIntOrNull(),
                    topP = topP.toFloatOrNull(),
                    topK = topK.toIntOrNull(),
                    frequencyPenalty = frequencyPenalty.toFloatOrNull(),
                    presencePenalty = presencePenalty.toFloatOrNull()
                ))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = color)
        ) {
            Text("Apply", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ParamTextField("Temperature (0.0 - 2.0)", temperature, color) { temperature = it }
            ParamTextField("Max Tokens", maxTokens, color) { maxTokens = it }
            ParamTextField("Top P (0.0 - 1.0)", topP, color) { topP = it }
            ParamTextField("Top K", topK, color) { topK = it }
            ParamTextField("Frequency Penalty (-2.0 - 2.0)", frequencyPenalty, color) { frequencyPenalty = it }
            ParamTextField("Presence Penalty (-2.0 - 2.0)", presencePenalty, color) { presencePenalty = it }
        }
    }
}

@Composable
private fun ParamTextField(label: String, value: String, color: Color, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = color,
            unfocusedBorderColor = Color(0xFF444444),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        )
    )
}

/**
 * A single message in the dual chat conversation with model attribution.
 */
private data class DualMessage(
    val modelIndex: Int, // 1 or 2
    val content: String,
    val providerName: String,
    val modelName: String
)

/**
 * Session screen for dual AI chat - shows the conversation as it happens.
 */
@Composable
fun DualChatSessionScreen(
    viewModel: AiViewModel,
    aiSettings: AiSettings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val config = viewModel.uiState.collectAsState().value.dualChatConfig ?: return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Conversation state
    val messages = remember { mutableStateListOf<DualMessage>() }
    var currentInteraction by remember { mutableIntStateOf(0) }
    var targetInteractions by remember { mutableIntStateOf(config.interactionCount) }
    var isRunning by remember { mutableStateOf(true) }
    var isStopped by remember { mutableStateOf(false) }
    var thinkingModel by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var chatJob by remember { mutableStateOf<Job?>(null) }

    // Extra chats input
    var extraChatsText by remember { mutableStateOf("10") }

    // Cost tracking
    var model1InputTokens by remember { mutableIntStateOf(0) }
    var model1OutputTokens by remember { mutableIntStateOf(0) }
    var model2InputTokens by remember { mutableIntStateOf(0) }
    var model2OutputTokens by remember { mutableIntStateOf(0) }

    val pricing1 = remember { PricingCache.getPricing(context, config.model1Provider, config.model1Name) }
    val pricing2 = remember { PricingCache.getPricing(context, config.model2Provider, config.model2Name) }

    val model1Cost = (model1InputTokens * pricing1.promptPrice + model1OutputTokens * pricing1.completionPrice) * 100
    val model2Cost = (model2InputTokens * pricing2.promptPrice + model2OutputTokens * pricing2.completionPrice) * 100
    val totalCost = model1Cost + model2Cost

    // Build messages for a specific model's perspective
    fun buildMessagesForModel(modelIndex: Int): List<ChatMessage> {
        val systemPrompt = if (modelIndex == 1) config.model1SystemPrompt else config.model2SystemPrompt
        val chatMessages = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            chatMessages.add(ChatMessage(role = "system", content = systemPrompt))
        }
        for (msg in messages) {
            val role = if (msg.modelIndex == modelIndex) "assistant" else "user"
            chatMessages.add(ChatMessage(role = role, content = msg.content))
        }
        return chatMessages
    }

    // Chat loop function
    fun startChatLoop() {
        chatJob = scope.launch {
            isRunning = true
            isStopped = false
            errorMessage = null

            try {
                while (currentInteraction < targetInteractions) {
                    // Model 1's turn
                    thinkingModel = 1
                    val model1Messages = buildMessagesForModel(1).toMutableList()
                    if (messages.isEmpty()) {
                        val prompt = config.firstPrompt.replace("%subject%", config.subject)
                        model1Messages.add(ChatMessage(role = "user", content = prompt))
                    }

                    val apiKey1 = aiSettings.getApiKey(config.model1Provider)
                    val params1 = config.model1Params.copy(systemPrompt = config.model1SystemPrompt)
                    val response1 = viewModel.sendDualChatMessage(
                        config.model1Provider, apiKey1, config.model1Name, model1Messages, params1
                    )

                    val inputTokens1 = model1Messages.sumOf { AiViewModel.estimateTokens(it.content) }
                    val outputTokens1 = AiViewModel.estimateTokens(response1)
                    model1InputTokens += inputTokens1
                    model1OutputTokens += outputTokens1

                    messages.add(DualMessage(1, response1, config.model1Provider.displayName, config.model1Name))
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }

                    // Model 2's turn
                    thinkingModel = 2
                    val model2Messages = buildMessagesForModel(2).let { msgs ->
                        if (currentInteraction == 0 && msgs.isNotEmpty()) {
                            val last = msgs.last()
                            if (last.role == "user") {
                                val prompt = config.secondPrompt.replace("%answer%", last.content)
                                msgs.dropLast(1) + last.copy(content = prompt)
                            } else msgs
                        } else msgs
                    }

                    val apiKey2 = aiSettings.getApiKey(config.model2Provider)
                    val params2 = config.model2Params.copy(systemPrompt = config.model2SystemPrompt)
                    val response2 = viewModel.sendDualChatMessage(
                        config.model2Provider, apiKey2, config.model2Name, model2Messages, params2
                    )

                    val inputTokens2 = model2Messages.sumOf { AiViewModel.estimateTokens(it.content) }
                    val outputTokens2 = AiViewModel.estimateTokens(response2)
                    model2InputTokens += inputTokens2
                    model2OutputTokens += outputTokens2

                    messages.add(DualMessage(2, response2, config.model2Provider.displayName, config.model2Name))

                    currentInteraction++
                    thinkingModel = null
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                }
            } catch (e: CancellationException) {
                // User stopped
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
            } finally {
                thinkingModel = null
                isRunning = false
                isStopped = true
            }
        }
    }

    // Start the chat loop on first composition
    LaunchedEffect(Unit) {
        startChatLoop()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AiTitleBar(
            title = "AI Dual Chat",
            onBackClick = {
                chatJob?.cancel()
                onNavigateBack()
            },
            onAiClick = {
                chatJob?.cancel()
                onNavigateHome()
            }
        )

        // Cost row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A2A3A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CostLabel(config.model1Name, model1Cost, Color(0xFF4488CC))
            CostLabel(config.model2Name, model2Cost, Color(0xFF44AA66))
            CostLabel("Total", totalCost, Color(0xFFCCCCCC))
        }

        // Progress
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Interaction $currentInteraction / $targetInteractions",
                fontSize = 13.sp,
                color = Color(0xFFAAAAAA)
            )
            Text(
                "Subject: ${config.subject}",
                fontSize = 13.sp,
                color = Color(0xFF888888),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                textAlign = TextAlign.End
            )
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages.size) { index ->
                val msg = messages[index]
                DualMessageBubble(msg)
            }

            if (thinkingModel != null) {
                item {
                    val thinkingName = if (thinkingModel == 1) config.model1Name else config.model2Name
                    val thinkingColor = if (thinkingModel == 1) Color(0xFF4488CC) else Color(0xFF44AA66)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (thinkingModel == 1) Arrangement.Start else Arrangement.End
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = thinkingColor.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "$thinkingName is thinking...",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 13.sp,
                                color = thinkingColor
                            )
                        }
                    }
                }
            }
        }

        // Error message
        if (errorMessage != null) {
            Text(
                "Error: $errorMessage",
                color = Color(0xFFFF6666),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Bottom controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRunning) {
                Button(
                    onClick = {
                        chatJob?.cancel()
                        isRunning = false
                        isStopped = true
                        thinkingModel = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC4444))
                ) {
                    Text("Stop", fontWeight = FontWeight.Bold)
                }
            } else if (isStopped) {
                OutlinedTextField(
                    value = extraChatsText,
                    onValueChange = { extraChatsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Extra chats") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF555555)
                    )
                )
                val extraCount = extraChatsText.toIntOrNull() ?: 0
                Button(
                    onClick = {
                        if (extraCount > 0) {
                            targetInteractions = currentInteraction + extraCount
                            errorMessage = null
                            startChatLoop()
                        }
                    },
                    enabled = extraCount > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4488CC),
                        disabledContainerColor = Color(0xFF333333)
                    )
                ) {
                    Text("Chat $extraCount more", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CostLabel(name: String, costCents: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            name,
            fontSize = 11.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 100.dp)
        )
        Text(
            "%.4f c".format(costCents),
            fontSize = 12.sp,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DualMessageBubble(msg: DualMessage) {
    val isModel1 = msg.modelIndex == 1
    val bgColor = if (isModel1) Color(0xFF4488CC).copy(alpha = 0.15f) else Color(0xFF44AA66).copy(alpha = 0.15f)
    val labelColor = if (isModel1) Color(0xFF4488CC) else Color(0xFF44AA66)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isModel1) Arrangement.Start else Arrangement.End
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "${msg.providerName} / ${msg.modelName}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    msg.content,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }
    }
}
