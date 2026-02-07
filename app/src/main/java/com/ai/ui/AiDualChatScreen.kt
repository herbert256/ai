package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.ai.data.AiService
import com.ai.data.PricingCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    var model1Provider by remember { mutableStateOf<AiService?>(null) }
    var model1Name by remember { mutableStateOf("") }
    var model1SystemPrompt by remember { mutableStateOf("") }
    var model2Provider by remember { mutableStateOf<AiService?>(null) }
    var model2Name by remember { mutableStateOf("") }
    var model2SystemPrompt by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var interactionCount by remember { mutableStateOf("10") }

    // Full-screen overlay for model selection
    var selectingModel by remember { mutableStateOf(0) } // 0=none, 1=model1, 2=model2

    if (selectingModel > 0) {
        SelectAllModelsScreen(
            aiSettings = aiSettings,
            onSelectModel = { provider, model ->
                if (selectingModel == 1) {
                    model1Provider = provider
                    model1Name = model
                } else {
                    model2Provider = provider
                    model2Name = model
                }
                selectingModel = 0
            },
            onBack = { selectingModel = 0 },
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
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model 1
            ModelSelectionCard(
                label = "Model 1",
                providerName = model1Provider?.displayName,
                modelName = model1Name,
                systemPrompt = model1SystemPrompt,
                onSystemPromptChange = { model1SystemPrompt = it },
                onSelectClick = { selectingModel = 1 },
                color = Color(0xFF4488CC)
            )

            // Model 2
            ModelSelectionCard(
                label = "Model 2",
                providerName = model2Provider?.displayName,
                modelName = model2Name,
                systemPrompt = model2SystemPrompt,
                onSystemPromptChange = { model2SystemPrompt = it },
                onSelectClick = { selectingModel = 2 },
                color = Color(0xFF44AA66)
            )

            // Subject
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                placeholder = { Text("What should they discuss?") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )

            // Number of interactions
            OutlinedTextField(
                value = interactionCount,
                onValueChange = { interactionCount = it.filter { c -> c.isDigit() } },
                label = { Text("Number of interactions") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )

            Spacer(modifier = Modifier.weight(1f))

            // Go button
            Button(
                onClick = {
                    if (canStart) {
                        onStartSession(
                            DualChatConfig(
                                model1Provider = model1Provider!!,
                                model1Name = model1Name,
                                model1SystemPrompt = model1SystemPrompt,
                                model2Provider = model2Provider!!,
                                model2Name = model2Name,
                                model2SystemPrompt = model2SystemPrompt,
                                subject = subject,
                                interactionCount = interactionCount.toIntOrNull() ?: 10
                            )
                        )
                    }
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4488CC),
                    disabledContainerColor = Color(0xFF333333)
                )
            ) {
                Text("Go", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
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
    color: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A3A4A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
                    if (providerName != null) {
                        Text(
                            "$providerName / $modelName",
                            fontSize = 13.sp,
                            color = Color(0xFFCCCCCC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("Not selected", fontSize = 13.sp, color = Color(0xFF888888))
                    }
                }
                Button(
                    onClick = onSelectClick,
                    colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("Select", color = color)
                }
            }
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text("System prompt (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = color.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
        }
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
    var thinkingModel by remember { mutableStateOf<Int?>(null) } // which model is thinking (1 or 2)
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
                        // First message - introduce the topic
                        model1Messages.add(ChatMessage(role = "user", content = "Let's talk about ${config.subject}"))
                    }

                    val apiKey1 = aiSettings.getApiKey(config.model1Provider)
                    val params1 = ChatParameters(systemPrompt = config.model1SystemPrompt)
                    val response1 = viewModel.sendDualChatMessage(
                        config.model1Provider, apiKey1, config.model1Name, model1Messages, params1
                    )

                    val inputTokens1 = model1Messages.sumOf { AiViewModel.estimateTokens(it.content) }
                    val outputTokens1 = AiViewModel.estimateTokens(response1)
                    model1InputTokens += inputTokens1
                    model1OutputTokens += outputTokens1

                    messages.add(DualMessage(1, response1, config.model1Provider.displayName, config.model1Name))

                    // Scroll to bottom
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }

                    // Model 2's turn
                    thinkingModel = 2
                    val model2Messages = buildMessagesForModel(2)

                    val apiKey2 = aiSettings.getApiKey(config.model2Provider)
                    val params2 = ChatParameters(systemPrompt = config.model2SystemPrompt)
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

                    // Scroll to bottom
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

            // Thinking indicator
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
