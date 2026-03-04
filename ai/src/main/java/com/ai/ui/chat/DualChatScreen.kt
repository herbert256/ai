package com.ai.ui.chat

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.SelectModelScreen
import com.ai.ui.shared.SelectProviderScreen
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val DUAL_PREFS = "dual_chat_prefs"
private const val KEY_M1_PROVIDER = "model1_provider"
private const val KEY_M1_NAME = "model1_name"
private const val KEY_M1_PARAMS = "model1_params_ids"
private const val KEY_M1_SYSTEM = "model1_system_prompt_id"
private const val KEY_M2_PROVIDER = "model2_provider"
private const val KEY_M2_NAME = "model2_name"
private const val KEY_M2_PARAMS = "model2_params_ids"
private const val KEY_M2_SYSTEM = "model2_system_prompt_id"
private const val KEY_SUBJECT = "subject"
private const val KEY_INTERACTIONS = "interaction_count"
private const val KEY_FIRST_PROMPT = "first_prompt"
private const val KEY_SECOND_PROMPT = "second_prompt"

private data class DualMessage(val modelIndex: Int, val content: String, val providerName: String, val modelName: String)

private fun loadStringList(prefs: android.content.SharedPreferences, key: String): List<String> {
    return try {
        val json = prefs.getString(key, null) ?: return emptyList()
        com.ai.data.createAppGson().fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

private fun saveStringList(prefs: android.content.SharedPreferences, key: String, list: List<String>) {
    prefs.edit().putString(key, com.ai.data.createAppGson().toJson(list)).apply()
}

private fun resolveParamsIds(aiSettings: Settings, ids: List<String>): ChatParameters {
    val merged = aiSettings.mergeParameters(ids) ?: return ChatParameters()
    return ChatParameters(
        temperature = merged.temperature, maxTokens = merged.maxTokens,
        topP = merged.topP, topK = merged.topK,
        frequencyPenalty = merged.frequencyPenalty, presencePenalty = merged.presencePenalty,
        systemPrompt = merged.systemPrompt ?: "", searchEnabled = merged.searchEnabled,
        returnCitations = merged.returnCitations, searchRecency = merged.searchRecency
    )
}

// ===== Setup Screen =====

@Composable
fun DualChatSetupScreen(
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onStartSession: (DualChatConfig) -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(DUAL_PREFS, Context.MODE_PRIVATE) }

    var model1Provider by remember { mutableStateOf(AppService.findById(prefs.getString(KEY_M1_PROVIDER, "") ?: "")) }
    var model1Name by remember { mutableStateOf(prefs.getString(KEY_M1_NAME, "") ?: "") }
    var model1ParamsIds by remember { mutableStateOf(loadStringList(prefs, KEY_M1_PARAMS)) }
    var model1SystemPromptId by remember { mutableStateOf<String?>(prefs.getString(KEY_M1_SYSTEM, null)) }
    var model2Provider by remember { mutableStateOf(AppService.findById(prefs.getString(KEY_M2_PROVIDER, "") ?: "")) }
    var model2Name by remember { mutableStateOf(prefs.getString(KEY_M2_NAME, "") ?: "") }
    var model2ParamsIds by remember { mutableStateOf(loadStringList(prefs, KEY_M2_PARAMS)) }
    var model2SystemPromptId by remember { mutableStateOf<String?>(prefs.getString(KEY_M2_SYSTEM, null)) }
    var subject by remember { mutableStateOf(prefs.getString(KEY_SUBJECT, "") ?: "") }
    var interactionCount by remember { mutableStateOf(prefs.getString(KEY_INTERACTIONS, "10") ?: "10") }
    var firstPrompt by remember { mutableStateOf(prefs.getString(KEY_FIRST_PROMPT, "Let's talk about %subject%") ?: "Let's talk about %subject%") }
    var secondPrompt by remember { mutableStateOf(prefs.getString(KEY_SECOND_PROMPT, "What do you think about: %answer%") ?: "What do you think about: %answer%") }
    var overlayMode by remember { mutableIntStateOf(0) } // 0=none, 1=select m1 provider, 2=select m1 model, 3=select m2 provider, 4=select m2 model

    fun savePrefs() {
        prefs.edit()
            .putString(KEY_M1_PROVIDER, model1Provider?.id ?: "")
            .putString(KEY_M1_NAME, model1Name)
            .putString(KEY_M1_SYSTEM, model1SystemPromptId)
            .putString(KEY_M2_PROVIDER, model2Provider?.id ?: "")
            .putString(KEY_M2_NAME, model2Name)
            .putString(KEY_M2_SYSTEM, model2SystemPromptId)
            .putString(KEY_SUBJECT, subject)
            .putString(KEY_INTERACTIONS, interactionCount)
            .putString(KEY_FIRST_PROMPT, firstPrompt)
            .putString(KEY_SECOND_PROMPT, secondPrompt)
            .apply()
        saveStringList(prefs, KEY_M1_PARAMS, model1ParamsIds)
        saveStringList(prefs, KEY_M2_PARAMS, model2ParamsIds)
    }

    DisposableEffect(Unit) { onDispose { savePrefs() } }

    // Full-screen overlays for model selection
    when (overlayMode) {
        1 -> { SelectProviderScreen(aiSettings = aiSettings, onSelectProvider = { model1Provider = it; model1Name = ""; overlayMode = 2 }, onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome); return }
        2 -> if (model1Provider != null) { SelectModelScreen(provider = model1Provider!!, aiSettings = aiSettings, currentModel = model1Name, onSelectModel = { model1Name = it; overlayMode = 0 }, onBack = { overlayMode = 1 }, onNavigateHome = onNavigateHome); return }
        3 -> { SelectProviderScreen(aiSettings = aiSettings, onSelectProvider = { model2Provider = it; model2Name = ""; overlayMode = 4 }, onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome); return }
        4 -> if (model2Provider != null) { SelectModelScreen(provider = model2Provider!!, aiSettings = aiSettings, currentModel = model2Name, onSelectModel = { model2Name = it; overlayMode = 0 }, onBack = { overlayMode = 3 }, onNavigateHome = onNavigateHome); return }
    }

    val canStart = model1Provider != null && model1Name.isNotBlank() && model2Provider != null && model2Name.isNotBlank() && subject.isNotBlank() && (interactionCount.toIntOrNull() ?: 0) > 0

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Dual AI Chat", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Model 1
            ModelSelectionCard(
                label = "Model 1", providerName = model1Provider?.displayName, modelName = model1Name,
                onSelectClick = { overlayMode = 1 }, color = Color(0xFF4488CC),
                aiSettings = aiSettings, paramsIds = model1ParamsIds, onParamsIdsChange = { model1ParamsIds = it },
                systemPromptId = model1SystemPromptId, onSystemPromptIdChange = { model1SystemPromptId = it }
            )

            // Swap button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = {
                    val tp = model1Provider; val tn = model1Name; val tpi = model1ParamsIds; val tsp = model1SystemPromptId
                    model1Provider = model2Provider; model1Name = model2Name; model1ParamsIds = model2ParamsIds; model1SystemPromptId = model2SystemPromptId
                    model2Provider = tp; model2Name = tn; model2ParamsIds = tpi; model2SystemPromptId = tsp
                }) { Text("\u2B05 Swap \u27A1", maxLines = 1, softWrap = false) }
            }

            // Model 2
            ModelSelectionCard(
                label = "Model 2", providerName = model2Provider?.displayName, modelName = model2Name,
                onSelectClick = { overlayMode = 3 }, color = Color(0xFF44AA66),
                aiSettings = aiSettings, paramsIds = model2ParamsIds, onParamsIdsChange = { model2ParamsIds = it },
                systemPromptId = model2SystemPromptId, onSystemPromptIdChange = { model2SystemPromptId = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = subject, onValueChange = { subject = it },
                    label = { Text("Subject") }, modifier = Modifier.weight(1f),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = interactionCount, onValueChange = { interactionCount = it },
                    label = { Text("Rounds") }, modifier = Modifier.width(80.dp),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }

            OutlinedTextField(
                value = firstPrompt, onValueChange = { firstPrompt = it },
                label = { Text("1st prompt (%subject%)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )
            OutlinedTextField(
                value = secondPrompt, onValueChange = { secondPrompt = it },
                label = { Text("2nd prompt (%answer%)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )
            Text("From 3rd on: previous response is sent directly", fontSize = 12.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                savePrefs()
                val sp1 = model1SystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: ""
                val sp2 = model2SystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: ""
                onStartSession(
                    DualChatConfig(
                        model1Provider = model1Provider!!, model1Name = model1Name,
                        model1SystemPrompt = sp1, model1Params = resolveParamsIds(aiSettings, model1ParamsIds),
                        model2Provider = model2Provider!!, model2Name = model2Name,
                        model2SystemPrompt = sp2, model2Params = resolveParamsIds(aiSettings, model2ParamsIds),
                        subject = subject, interactionCount = interactionCount.toIntOrNull() ?: 10,
                        firstPrompt = firstPrompt, secondPrompt = secondPrompt
                    )
                )
            },
            enabled = canStart, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
        ) { Text("Go", fontSize = 16.sp, maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun ModelSelectionCard(
    label: String,
    providerName: String?,
    modelName: String,
    onSelectClick: () -> Unit,
    color: Color,
    aiSettings: Settings,
    paramsIds: List<String>,
    onParamsIdsChange: (List<String>) -> Unit,
    systemPromptId: String?,
    onSystemPromptIdChange: (String?) -> Unit
) {
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }

    if (showSystemPromptDialog) {
        SystemPromptSelectorDialog(
            aiSettings = aiSettings, selectedId = systemPromptId,
            onSelect = { onSystemPromptIdChange(it); showSystemPromptDialog = false },
            onDismiss = { showSystemPromptDialog = false }
        )
    }
    if (showParamsDialog) {
        ParametersSelectorDialog(
            aiSettings = aiSettings, selectedIds = paramsIds,
            onConfirm = { onParamsIdsChange(it); showParamsDialog = false },
            onDismiss = { showParamsDialog = false }
        )
    }

    val spName = systemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
    val pNames = paramsIds.mapNotNull { aiSettings.getParametersById(it)?.name }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontWeight = FontWeight.Bold, color = color)

            Button(
                onClick = onSelectClick, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.3f))
            ) {
                Text(
                    if (providerName != null && modelName.isNotBlank()) "$providerName / $modelName"
                    else "Select model...",
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showSystemPromptDialog = true }, modifier = Modifier.weight(1f),
                    colors = if (spName != null) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) { Text(spName ?: "System Prompt", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                OutlinedButton(
                    onClick = { showParamsDialog = true }, modifier = Modifier.weight(1f),
                    colors = if (pNames.isNotEmpty()) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) { Text(if (pNames.isNotEmpty()) pNames.joinToString(", ") else "Parameters", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ===== Session Screen =====

@Composable
fun DualChatSessionScreen(
    appViewModel: AppViewModel,
    chatViewModel: ChatViewModel,
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val config = remember { appViewModel.uiState.value.dualChatConfig ?: return@remember null }
    if (config == null) { LaunchedEffect(Unit) { onNavigateBack() }; return }

    val sessionId = remember { "dualchat_${System.currentTimeMillis()}" }
    val messages = remember { mutableStateListOf<DualMessage>() }
    var currentInteraction by remember { mutableIntStateOf(0) }
    var targetInteractions by remember { mutableIntStateOf(config.interactionCount) }
    var isRunning by remember { mutableStateOf(false) }
    var isStopped by remember { mutableStateOf(false) }
    var thinkingModel by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var extraChatsText by remember { mutableStateOf("10") }

    // Cost tracking
    var model1InputTokens by remember { mutableIntStateOf(0) }
    var model1OutputTokens by remember { mutableIntStateOf(0) }
    var model2InputTokens by remember { mutableIntStateOf(0) }
    var model2OutputTokens by remember { mutableIntStateOf(0) }
    val pricing1 = remember { PricingCache.getPricing(context, config.model1Provider, config.model1Name) }
    val pricing2 = remember { PricingCache.getPricing(context, config.model2Provider, config.model2Name) }
    val model1Cost by remember { derivedStateOf { (model1InputTokens * pricing1.promptPrice + model1OutputTokens * pricing1.completionPrice) * 100 } }
    val model2Cost by remember { derivedStateOf { (model2InputTokens * pricing2.promptPrice + model2OutputTokens * pricing2.completionPrice) * 100 } }
    val totalCost by remember { derivedStateOf { model1Cost + model2Cost } }

    fun buildMessagesForModel(modelIndex: Int): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val sp = if (modelIndex == 1) config.model1SystemPrompt else config.model2SystemPrompt
        if (sp.isNotBlank()) result.add(ChatMessage(role = "system", content = sp))
        for (msg in messages) {
            result.add(ChatMessage(role = if (msg.modelIndex == modelIndex) "assistant" else "user", content = msg.content))
        }
        return result
    }

    fun startChatLoop() {
        chatJob = scope.launch {
            isRunning = true; isStopped = false; errorMessage = null
            ApiTracer.currentReportId = sessionId
            try {
                while (currentInteraction < targetInteractions) {
                    // Model 1's turn
                    thinkingModel = 1
                    val m1Messages = buildMessagesForModel(1).toMutableList()
                    if (messages.isEmpty()) {
                        m1Messages.add(ChatMessage(role = "user", content = config.firstPrompt.replace("%subject%", config.subject)))
                    }
                    val apiKey1 = aiSettings.getApiKey(config.model1Provider)
                    val response1 = chatViewModel.sendDualChatMessage(config.model1Provider, apiKey1, config.model1Name, m1Messages, config.model1Params)
                    val inTokens1 = m1Messages.sumOf { AppViewModel.estimateTokens(it.content) }
                    val outTokens1 = AppViewModel.estimateTokens(response1)
                    model1InputTokens += inTokens1; model1OutputTokens += outTokens1
                    messages.add(DualMessage(1, response1, config.model1Provider.displayName, config.model1Name))
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)

                    // Model 2's turn
                    thinkingModel = 2
                    val m2Messages = buildMessagesForModel(2).toMutableList()
                    if (currentInteraction == 0 && m2Messages.lastOrNull()?.role == "user") {
                        val last = m2Messages.removeLast()
                        m2Messages.add(ChatMessage(role = "user", content = config.secondPrompt.replace("%answer%", last.content)))
                    }
                    val apiKey2 = aiSettings.getApiKey(config.model2Provider)
                    val response2 = chatViewModel.sendDualChatMessage(config.model2Provider, apiKey2, config.model2Name, m2Messages, config.model2Params)
                    val inTokens2 = m2Messages.sumOf { AppViewModel.estimateTokens(it.content) }
                    val outTokens2 = AppViewModel.estimateTokens(response2)
                    model2InputTokens += inTokens2; model2OutputTokens += outTokens2
                    messages.add(DualMessage(2, response2, config.model2Provider.displayName, config.model2Name))
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)

                    currentInteraction++
                }
            } catch (_: CancellationException) {
                // User stopped
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
            } finally {
                ApiTracer.currentReportId = null
                thinkingModel = null; isRunning = false; isStopped = true
            }
        }
    }

    DisposableEffect(Unit) { onDispose { chatJob?.cancel() } }
    LaunchedEffect(Unit) { startChatLoop() }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Dual Chat", onBackClick = onNavigateBack, onAiClick = onNavigateHome)

        // Cost row
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2A3A), RoundedCornerShape(8.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CostLabel("Model 1", model1Cost, Color(0xFF4488CC))
            CostLabel("Model 2", model2Cost, Color(0xFF44AA66))
            CostLabel("Total", totalCost, Color(0xFFCCCCCC))
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Interaction $currentInteraction / $targetInteractions — Subject: ${config.subject}", fontSize = 12.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(8.dp))

        // Messages
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.size, key = { "msg_${it}_${messages[it].modelIndex}" }) { index ->
                DualMessageBubble(messages[index])
            }
            if (thinkingModel != null) {
                item(key = "thinking") {
                    val color = if (thinkingModel == 1) Color(0xFF4488CC) else Color(0xFF44AA66)
                    val align = if (thinkingModel == 1) Alignment.CenterStart else Alignment.CenterEnd
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Model $thinkingModel is thinking...", fontSize = 13.sp, color = color)
                            }
                        }
                    }
                }
            }
        }

        // Error
        if (errorMessage != null) {
            Text(errorMessage!!, color = Color(0xFFFF6666), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Controls
        if (isRunning) {
            Button(
                onClick = { chatJob?.cancel(); ApiTracer.currentReportId = null; isRunning = false; isStopped = true; thinkingModel = null },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC4444))
            ) { Text("Stop", maxLines = 1, softWrap = false) }
        } else if (isStopped) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = extraChatsText, onValueChange = { extraChatsText = it },
                    modifier = Modifier.width(120.dp), singleLine = true,
                    label = { Text("Extra chats") }, colors = AppColors.outlinedFieldColors()
                )
                val extraCount = extraChatsText.toIntOrNull() ?: 0
                Button(
                    onClick = {
                        if (extraCount > 0) {
                            targetInteractions = currentInteraction + extraCount
                            startChatLoop()
                        }
                    },
                    enabled = extraCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                ) { Text("Chat $extraCount more", maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
private fun CostLabel(name: String, costCents: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 100.dp)) {
        Text(name, fontSize = 10.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%.4f c".format(costCents), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DualMessageBubble(msg: DualMessage) {
    val isModel1 = msg.modelIndex == 1
    val color = if (isModel1) Color(0xFF4488CC) else Color(0xFF44AA66)
    val align = if (isModel1) Alignment.CenterStart else Alignment.CenterEnd

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "${msg.providerName} / ${msg.modelName}",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(msg.content, fontSize = 14.sp, color = Color.White)
            }
        }
    }
}
