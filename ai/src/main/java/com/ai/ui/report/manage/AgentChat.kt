package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ChatMessage
import com.ai.data.ChatParameters
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalAgentChat
import com.ai.ui.shared.LocalNavigateHome
import com.ai.ui.shared.ParametersSelectScreen
import com.ai.ui.shared.SystemPromptSelectScreen
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------
// In-report "refine this answer" chat (🗣️). Anchored to one agent's
// answer (Model response) or one fan-out pair (Fan-out response). The
// conversation is persisted by the host (onSaveMessages); tapping Apply
// on an assistant reply folds it back into the report (onApply). Spend
// goes to global AI Usage, not the report cost table. See the plan +
// [com.ai.ui.shared.AgentChatBridge].
// ---------------------------------------------------------------------

/**
 * @param initialMessages persisted conversation (user/assistant turns only,
 *        no system message) — seeded by the host on first open.
 * @param initialParams resolved params for the agent (carries the system
 *        prompt as text); the 🎭/🌡️ icons override it live.
 * @param onSaveMessages persist the full conversation after each turn.
 * @param onApply overwrite the report response with a chosen reply.
 */
@Composable
internal fun AgentChatScreen(
    titleBarSubject: String,
    service: AppService,
    model: String,
    agentIdForKey: String?,
    initialMessages: List<ChatMessage>,
    initialParams: ChatParameters,
    aiSettings: Settings,
    onSaveMessages: suspend (List<ChatMessage>) -> Unit,
    onApply: suspend (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val navigateHome = LocalNavigateHome.current
    val bridge = LocalAgentChat.current
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }
    var params by remember { mutableStateOf(initialParams) }
    var selectedSystemPromptId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedParamsIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var showSystemPromptPicker by rememberSaveable { mutableStateOf(false) }
    var showParamsPicker by rememberSaveable { mutableStateOf(false) }

    var userInput by rememberSaveable { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var appliedTick by remember { mutableStateOf(0) }

    // ----- 🎭 system prompt picker -----
    if (showSystemPromptPicker) {
        SystemPromptSelectScreen(
            aiSettings = aiSettings,
            selectedId = selectedSystemPromptId,
            onSelect = { id ->
                selectedSystemPromptId = id
                params = params.copy(systemPrompt = id?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: "")
            },
            onBack = { showSystemPromptPicker = false },
            onNavigateHome = navigateHome
        )
        return
    }
    // ----- 🌡️ parameters picker (numeric params; keeps the chosen system prompt) -----
    if (showParamsPicker) {
        ParametersSelectScreen(
            aiSettings = aiSettings,
            selectedIds = selectedParamsIds,
            onConfirm = { ids ->
                selectedParamsIds = ids
                val m = aiSettings.mergeParameters(ids)
                params = params.copy(
                    temperature = m?.temperature, maxTokens = m?.maxTokens,
                    topP = m?.topP, topK = m?.topK,
                    frequencyPenalty = m?.frequencyPenalty, presencePenalty = m?.presencePenalty,
                    searchEnabled = m?.searchEnabled ?: false,
                    returnCitations = m?.returnCitations ?: true,
                    searchRecency = m?.searchRecency,
                    webSearchTool = m?.webSearchTool ?: false,
                    reasoningEffort = m?.reasoningEffort
                )
            },
            onBack = { showParamsPicker = false },
            onNavigateHome = navigateHome
        )
        return
    }

    fun sendTurn() {
        val text = userInput.trim()
        if (text.isBlank() || isStreaming || bridge == null) return
        userInput = ""
        messages.add(ChatMessage(role = "user", content = text))
        scope.launch(Dispatchers.IO) { onSaveMessages(messages.toList()) }
        // Outgoing call = optional system message + the full conversation.
        val outgoing = buildList {
            if (params.systemPrompt.isNotBlank()) add(ChatMessage(role = "system", content = params.systemPrompt))
            addAll(messages)
        }
        isStreaming = true
        streamingText = ""
        val sb = StringBuilder()
        streamJob = scope.launch {
            try {
                bridge.send(service, model, agentIdForKey, outgoing, params).collect { chunk ->
                    sb.append(chunk); streamingText = sb.toString()
                }
                val reply = sb.toString().trim()
                if (reply.isNotBlank()) {
                    messages.add(ChatMessage(role = "assistant", content = reply))
                    withContext(Dispatchers.IO) { onSaveMessages(messages.toList()) }
                    bridge.recordUsage(
                        service, model,
                        bridge.estimateTokens(outgoing.joinToString("\n") { it.content }),
                        bridge.estimateTokens(reply)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Navigation / scope cancellation isn't a model failure — rethrow
                // so no spurious failure bubble is appended. See audit bug 18.
                throw e
            } catch (_: Exception) {
                messages.add(ChatMessage(role = "assistant", content = "${com.ai.data.MetadataIconsHolder.current.statusWarning} The model call failed. Try again."))
                // Persist the failure bubble too (the success path does), so it
                // survives reopening the chat. See audit bug 19.
                withContext(Dispatchers.IO) { onSaveMessages(messages.toList()) }
            } finally {
                isStreaming = false; streamingText = ""; streamJob = null
            }
        }
    }

    val listState = rememberLazyListState()
    // Keep the newest message in view as the conversation grows / streams.
    LaunchedEffect(messages.size, streamingText) {
        val count = messages.size + if (isStreaming) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_agent_chat",
            title = "Refine answer",
            subject = titleBarSubject,
            onBackClick = onBack,
            onSystemPrompt = { showSystemPromptPicker = true },
            onParameters = { showParamsPicker = true }
        )
        Text(
            "Chat to refine this answer (e.g. \"be more verbose\"). Tap Apply on a reply to replace the report response.",
            fontSize = 11.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages.size) { i ->
                val msg = messages[i]
                AgentChatBubble(
                    msg = msg,
                    onApply = if (msg.role == "assistant" && !isStreaming) {
                        { scope.launch { withContext(Dispatchers.IO) { onApply(msg.content) }; appliedTick++ } }
                    } else null
                )
            }
            if (isStreaming) {
                item {
                    AgentChatBubble(
                        msg = ChatMessage(role = "assistant", content = streamingText.ifBlank { "…" }),
                        onApply = null
                    )
                }
            }
        }

        if (appliedTick > 0) {
            Text(
                "${com.ai.data.MetadataIconsHolder.current.checkMark} Report response updated.",
                fontSize = 11.sp, color = AppColors.SuccessAccent,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = userInput, onValueChange = { userInput = it },
                placeholder = { Text("Ask for a change…") },
                modifier = Modifier.weight(1f),
                enabled = !isStreaming,
                colors = AppColors.outlinedFieldColors(),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            if (isStreaming) {
                Text(com.ai.data.MetadataIconsHolder.current.statusStopped, fontSize = 26.sp, modifier = Modifier.clickable { streamJob?.cancel() }.padding(6.dp))
            } else {
                val canSend = userInput.isNotBlank() && bridge != null
                Text(
                    com.ai.data.MetadataIconsHolder.current.arrowSubmit, fontSize = 26.sp,
                    color = if (canSend) AppColors.SuccessAccent else AppColors.TextDim,
                    modifier = Modifier.clickable(enabled = canSend) { sendTurn() }.padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun AgentChatBubble(msg: ChatMessage, onApply: (() -> Unit)?) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) AppColors.InfoAccent.copy(alpha = 0.20f) else AppColors.CardBackground)
                .padding(10.dp)
        ) {
            Text(msg.content, fontSize = 13.sp, color = AppColors.TextPrimary)
        }
        if (onApply != null) {
            Text(
                "Apply ▶",
                fontSize = 12.sp, color = AppColors.SuccessAccent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onApply() }.padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 2.dp)
            )
        }
    }
}
