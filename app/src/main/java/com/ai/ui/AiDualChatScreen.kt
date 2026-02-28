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
private const val KEY_MODEL1_PARAMS_IDS = "model1_params_ids"
private const val KEY_MODEL2_PROVIDER = "model2_provider"
private const val KEY_MODEL2_NAME = "model2_name"
private const val KEY_MODEL2_PARAMS_IDS = "model2_params_ids"
private const val KEY_SUBJECT = "subject"
private const val KEY_INTERACTION_COUNT = "interaction_count"
private const val KEY_MODEL1_SYSTEM_PROMPT_ID = "model1_system_prompt_id"
private const val KEY_MODEL2_SYSTEM_PROMPT_ID = "model2_system_prompt_id"
private const val KEY_FIRST_PROMPT = "first_prompt"
private const val KEY_SECOND_PROMPT = "second_prompt"
private const val DEFAULT_FIRST_PROMPT = "Let's talk about %subject%"
private const val DEFAULT_SECOND_PROMPT = "What do you think about: %answer%"

private val gson = Gson()

private fun loadStringList(prefs: android.content.SharedPreferences, key: String): List<String> {
    val json = prefs.getString(key, null) ?: return emptyList()
    return try {
        gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

private fun resolveParamsIds(aiSettings: AiSettings, ids: List<String>): ChatParameters {
    val merged = aiSettings.mergeParameters(ids) ?: return ChatParameters()
    return ChatParameters(
        temperature = merged.temperature,
        maxTokens = merged.maxTokens,
        topP = merged.topP,
        topK = merged.topK,
        frequencyPenalty = merged.frequencyPenalty,
        presencePenalty = merged.presencePenalty,
        searchEnabled = merged.searchEnabled,
        returnCitations = merged.returnCitations,
        searchRecency = merged.searchRecency
    )
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
    var model1ParamsIds by remember { mutableStateOf(loadStringList(prefs, KEY_MODEL1_PARAMS_IDS)) }
    var model1SystemPromptId by remember { mutableStateOf(prefs.getString(KEY_MODEL1_SYSTEM_PROMPT_ID, null)) }
    var model2Provider by remember { mutableStateOf(prefs.getString(KEY_MODEL2_PROVIDER, null)?.let { AiService.findById(it) }) }
    var model2Name by remember { mutableStateOf(prefs.getString(KEY_MODEL2_NAME, "") ?: "") }
    var model2ParamsIds by remember { mutableStateOf(loadStringList(prefs, KEY_MODEL2_PARAMS_IDS)) }
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
            .putString(KEY_MODEL1_PARAMS_IDS, gson.toJson(model1ParamsIds))
            .putString(KEY_MODEL1_SYSTEM_PROMPT_ID, model1SystemPromptId)
            .putString(KEY_MODEL2_PROVIDER, model2Provider?.id)
            .putString(KEY_MODEL2_NAME, model2Name)
            .putString(KEY_MODEL2_PARAMS_IDS, gson.toJson(model2ParamsIds))
            .putString(KEY_MODEL2_SYSTEM_PROMPT_ID, model2SystemPromptId)
            .putString(KEY_SUBJECT, subject)
            .putString(KEY_INTERACTION_COUNT, interactionCount)
            .putString(KEY_FIRST_PROMPT, firstPrompt)
            .putString(KEY_SECOND_PROMPT, secondPrompt)
            .apply()
    }

    // Full-screen overlay state: 0=none, 1=select model1, 2=select model2
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
                providerName = model1Provider?.displayName,
                modelName = model1Name,
                onSelectClick = { overlayMode = 1 },
                aiSettings = aiSettings,
                paramsIds = model1ParamsIds,
                onParamsIdsChange = { model1ParamsIds = it; savePrefs() },
                systemPromptId = model1SystemPromptId,
                onSystemPromptIdChange = { model1SystemPromptId = it; savePrefs() }
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
                        val tmpParamsIds = model1ParamsIds
                        val tmpSystemPromptId = model1SystemPromptId
                        model1Provider = model2Provider
                        model1Name = model2Name
                        model1ParamsIds = model2ParamsIds
                        model1SystemPromptId = model2SystemPromptId
                        model2Provider = tmpProvider
                        model2Name = tmpName
                        model2ParamsIds = tmpParamsIds
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
                providerName = model2Provider?.displayName,
                modelName = model2Name,
                onSelectClick = { overlayMode = 2 },
                aiSettings = aiSettings,
                paramsIds = model2ParamsIds,
                onParamsIdsChange = { model2ParamsIds = it; savePrefs() },
                systemPromptId = model2SystemPromptId,
                onSystemPromptIdChange = { model2SystemPromptId = it; savePrefs() }
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
                    val resolved1 = model1SystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: ""
                    val resolved2 = model2SystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: ""
                    onStartSession(
                        DualChatConfig(
                            model1Provider = model1Provider!!,
                            model1Name = model1Name,
                            model1SystemPrompt = resolved1,
                            model1Params = resolveParamsIds(aiSettings, model1ParamsIds),
                            model2Provider = model2Provider!!,
                            model2Name = model2Name,
                            model2SystemPrompt = resolved2,
                            model2Params = resolveParamsIds(aiSettings, model2ParamsIds),
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
    providerName: String?,
    modelName: String,
    onSelectClick: () -> Unit,
    aiSettings: AiSettings,
    paramsIds: List<String>,
    onParamsIdsChange: (List<String>) -> Unit,
    systemPromptId: String?,
    onSystemPromptIdChange: (String?) -> Unit
) {
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }
    val systemPromptName = systemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
    val paramsNames = paramsIds.mapNotNull { id -> aiSettings.parameters.find { it.id == id }?.name }
    val cardColor = Color(0xFF4488CC)

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A3A4A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onSelectClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = cardColor.copy(alpha = 0.3f)),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { showSystemPromptDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (systemPromptId != null) Color(0xFF6C5CE7) else cardColor.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        systemPromptName ?: "System",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { showParamsDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (paramsIds.isNotEmpty()) Color(0xFF8B5CF6) else cardColor.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (paramsNames.isNotEmpty()) paramsNames.joinToString(", ") else "Params",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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

    if (showParamsDialog) {
        ParametersSelectorDialog(
            aiSettings = aiSettings,
            selectedParametersIds = paramsIds,
            onParamsSelected = { ids ->
                onParamsIdsChange(ids)
                showParamsDialog = false
            },
            onDismiss = { showParamsDialog = false }
        )
    }
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
