package com.ai.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== Provider Selection =====

@Composable
fun ChatSelectProviderScreen(
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectProvider: (AppService) -> Unit
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current

    val installedLocalLlms = remember { com.ai.data.LocalLlm.availableLlms(context) }
    val activeProviders = remember(aiSettings, installedLocalLlms) {
        val remote = aiSettings.getActiveServices()
        // LOCAL surfaces here only when at least one .task model is
        // installed — otherwise picking it would land on an empty
        // model list with no way forward.
        val withLocal = if (installedLocalLlms.isNotEmpty()) remote + AppService.LOCAL else remote
        withLocal.sortedBy { it.displayName }
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

            // Web search lives on the chat session screen as a per-turn 🌐
            // chip — removed from this setup screen so the user only has
            // one place to toggle it. Parameter presets can still flip
            // searchEnabled on; the runtime chip is OR'd with it at send
            // time.
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
                        searchEnabled = presetParams?.searchEnabled == true,
                        returnCitations = returnCitations && (presetParams?.returnCitations != false),
                        searchRecency = searchRecency.takeIf { it.isNotBlank() } ?: presetParams?.searchRecency
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Start Chat", fontSize = 16.sp, maxLines = 1, softWrap = false) }
    }
}

/** State carried into the "input flagged" dialog. The trace filename
 *  is captured at moderation-call time so the dialog can offer a 🐞
 *  button that opens the recorded request/response — useful for
 *  spotting why a particular input fired (which categories, scores). */
private data class FlaggedState(
    val input: String,
    val result: com.ai.data.ModerationInputResult,
    val img: Pair<String, String>?,
    val traceFilename: String?
)

/** Saver that lets the flagged-dialog state survive a navigation
 *  round-trip (e.g. tapping the 🐞 trace icon and pressing back).
 *  Encodes the fired-categories list directly — scores aren't shown
 *  in the dialog so we drop them. */
private val FlaggedStateSaver = androidx.compose.runtime.saveable.Saver<FlaggedState?, java.util.ArrayList<Any?>>(
    save = { state ->
        if (state == null) java.util.ArrayList()
        else java.util.ArrayList<Any?>().apply {
            add(state.input)
            add(java.util.ArrayList(state.result.firedCategories))
            add(state.img?.first)
            add(state.img?.second)
            add(state.traceFilename)
        }
    },
    restore = { list ->
        if (list.isEmpty()) null
        else {
            @Suppress("UNCHECKED_CAST")
            val fired = (list[1] as java.util.ArrayList<String>).toList()
            val mime = list[2] as? String
            val b64 = list[3] as? String
            FlaggedState(
                input = list[0] as String,
                result = com.ai.data.ModerationInputResult(
                    flagged = true,
                    categories = fired.associateWith { true },
                    scores = emptyMap()
                ),
                img = if (mime != null && b64 != null) mime to b64 else null,
                traceFilename = list[4] as? String
            )
        }
    }
)

// ===== Chat Session =====

@Composable
fun ChatSessionScreen(
    provider: AppService,
    model: String,
    parameters: ChatParameters,
    userName: String,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSendMessageStream: (List<ChatMessage>, Boolean, String?, List<String>) -> Flow<String>,
    onRecordStatistics: suspend (Int, Int) -> Unit,
    aiSettings: Settings,
    initialMessages: List<ChatMessage> = emptyList(),
    sessionId: String? = null,
    isVisionCapable: Boolean = false,
    onNavigateToTraceFile: (String) -> Unit = {},
    /** Optional pre-fill for the input box, threaded through from
     *  the share-target chooser when the user picked "New Chat". */
    initialUserInput: String? = null,
    /** Optional pre-attached vision image for the first user turn.
     *  Set by the AI Chat hub's "📸 Start with photo" entry (and
     *  any future flow that wants to drop a chat session in with
     *  an image already attached). When non-null both fields must
     *  be set; the screen seeds [attachedImage] on first composition. */
    initialUserImageBase64: String? = null,
    initialUserImageMime: String? = null,
    /** Fires once when [initialUserInput] / [initialUserImageBase64]
     *  / [initialUserImageMime] have been consumed so the staged
     *  values can be cleared from UiState. */
    onConsumeStarter: () -> Unit = {}
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val currentSessionId = remember { sessionId ?: java.util.UUID.randomUUID().toString() }

    var messages by remember { mutableStateOf(initialMessages) }
    // Pre-fill the input box with text staged by the share-target
    // chooser, then drop the staged value so leaving + returning
    // doesn't re-stuff it.
    val starter = remember { initialUserInput }
    val starterImage = remember {
        val mime = initialUserImageMime
        val b64 = initialUserImageBase64
        if (mime != null && b64 != null) mime to b64 else null
    }
    LaunchedEffect(Unit) { onConsumeStarter() }
    var userInput by remember { mutableStateOf(starter ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    val streamingContentState = remember { mutableStateOf("") }
    var totalCost by remember { mutableDoubleStateOf(0.0) }
    // (mime, base64) of an image attached to the next user message.
    var attachedImage by remember { mutableStateOf<Pair<String, String>?>(starterImage) }
    var useWebSearch by remember { mutableStateOf(parameters.webSearchTool) }
    // Per-turn reasoning-effort hint. "" = no hint; "low"/"medium"/"high"
    // map to the same OpenAI Responses-API / Gemini thinking field
    // ParametersScreen exposes. Initialized from the configure-on-the-
    // fly preset so a "high" preset starts that way; user can change
    // per turn via the pulldown next to the web-search chip.
    var reasoningEffort by remember { mutableStateOf(parameters.reasoningEffort ?: "") }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }
    // Cheap layered detection — LiteLLM, then models.dev. Null from
    // both = no info; we fall back to "show the pulldown" when the
    // model id contains common reasoning-family markers, otherwise hide.
    val supportsReasoning = remember(provider, model) {
        com.ai.data.PricingCache.liteLLMSupportsReasoning(provider, model)
            ?: com.ai.data.PricingCache.modelsDevSupportsReasoning(provider, model)
            ?: run {
                val id = model.lowercase()
                id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
                    id.startsWith("gpt-5") || "thinking" in id || "reasoning" in id
            }
    }
    // Per-session moderation: when set, every user input is run through
    // the chosen moderation model before being sent to the chat model.
    // null = disabled. Picker overlay below sets it.
    var moderationModel by remember { mutableStateOf<Pair<AppService, String>?>(null) }
    var showModerationPicker by remember { mutableStateOf(false) }
    // Result of the pre-send moderation call: when non-null and flagged,
    // the proceed/cancel dialog renders. The pending input + image are
    // captured so Proceed can fire the actual send without re-typing.
    // rememberSaveable so the dialog survives a round-trip to the
    // trace viewer — without this, `var by remember` reinitialises on
    // pop-back and the warning disappears once the user has seen the
    // trace, leaving them with no way to choose Proceed / Cancel.
    var pendingFlagged by rememberSaveable(stateSaver = FlaggedStateSaver) {
        mutableStateOf<FlaggedState?>(null)
    }
    var moderationError by remember { mutableStateOf<String?>(null) }
    var isModerating by remember { mutableStateOf(false) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    attachedImage = mime to Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                error = "Failed to attach image: ${e.message}"
            }
        }
    }

    val pricing = remember(provider, model) { PricingCache.getPricing(context, provider, model) }

    // Read the persisted pinned flag once on entry so subsequent saves
    // preserve it. Toggled below by the 📌 pill next to the model line.
    var pinned by remember(currentSessionId) {
        mutableStateOf(ChatHistoryManager.loadSession(currentSessionId)?.pinned == true)
    }
    // Knowledge bases attached to this session — read once on entry,
    // toggled by the 📚 chip below the model line. Persisted with
    // every saveSession so resume keeps the attachment.
    var attachedKnowledgeBaseIds by remember(currentSessionId) {
        mutableStateOf(ChatHistoryManager.loadSession(currentSessionId)?.knowledgeBaseIds.orEmpty())
    }
    var showKbDialog by remember { mutableStateOf(false) }
    val availableKbs = remember { com.ai.data.KnowledgeStore.listKnowledgeBases(context) }

    fun saveSession(msgs: List<ChatMessage>) {
        ChatHistoryManager.saveSession(
            ChatSession(id = currentSessionId, provider = provider, model = model, messages = msgs, parameters = parameters, updatedAt = System.currentTimeMillis(), pinned = pinned, knowledgeBaseIds = attachedKnowledgeBaseIds)
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

    // Auto-scroll — parent only reads messages.size and isStreaming (booleans); chunk updates
    // are observed via snapshotFlow so the parent doesn't recompose per chunk.
    val displayMessages = messages.filter { it.role != "system" }
    val bottomItemCount = displayMessages.size + (if (isStreaming) 1 else 0)
    LaunchedEffect(bottomItemCount) {
        if (bottomItemCount > 0) listState.animateScrollToItem(bottomItemCount - 1)
    }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            snapshotFlow { streamingContentState.value.length }.collect {
                if (bottomItemCount > 0) listState.animateScrollToItem(bottomItemCount - 1)
            }
        }
    }

    // Actual chat send — extracted so the moderation flow can call it
    // after a clean classification or after the user clicks Proceed on a
    // flagged-input dialog. [img] is captured at validation time so the
    // attached image isn't lost while moderation runs.
    fun actuallySend(input: String, img: Pair<String, String>?) {
        val userMessage = ChatMessage(
            role = "user",
            content = input,
            imageBase64 = img?.second,
            imageMime = img?.first
        )
        messages = messages + userMessage
        userInput = ""; error = null
        attachedImage = null
        saveSession(messages)

        // Add LiteLLM-reported tool_use overhead when web-search is on so
        // the client-side cost estimate isn't 5–10× under the actual bill
        // for tool-using turns (Claude with web_search adds ~3-4k system
        // tokens; the conversation text alone misses that).
        val toolOverhead = if (useWebSearch) (PricingCache.liteLLMToolUseOverhead(provider, model) ?: 0) else 0
        val inputTokens = messages.sumOf { AppViewModel.estimateTokens(it.content) } + toolOverhead

        scope.launch {
            isStreaming = true; streamingContentState.value = ""
            val sb = StringBuilder()
            val previousCategory = com.ai.data.ApiTracer.currentCategory
            com.ai.data.ApiTracer.currentCategory = "Chat"
            try {
                onSendMessageStream(messages, useWebSearch, reasoningEffort, attachedKnowledgeBaseIds).collect { chunk -> sb.append(chunk); streamingContentState.value = sb.toString() }
                val assistantMsg = ChatMessage(role = "assistant", content = streamingContentState.value)
                messages = messages + assistantMsg
                saveSession(messages)
                val outputTokens = AppViewModel.estimateTokens(streamingContentState.value)
                totalCost += inputTokens * pricing.promptPrice * 100 + outputTokens * pricing.completionPrice * 100
                onRecordStatistics(inputTokens, outputTokens)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User left the screen / closed the app. Don't persist a
                // "[Stream interrupted]" line into the saved session — the
                // partial chunks weren't really an error from the user's
                // perspective. Re-throw so structured cancellation works.
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Streaming error"
                if (sb.isNotEmpty()) {
                    messages = messages + ChatMessage(role = "assistant", content = "$sb\n\n[Stream interrupted: ${e.message}]")
                    saveSession(messages)
                }
            } finally {
                com.ai.data.ApiTracer.currentCategory = previousCategory
                isStreaming = false; streamingContentState.value = ""
            }
        }
    }

    // Pre-send moderation. If no moderation model is selected, falls
    // straight through to actuallySend. Otherwise runs the input
    // through callModerationApi; on a clean classification the message
    // proceeds, on flagged we pop a dialog with categories and let the
    // user choose Proceed or Cancel. API errors surface in [moderationError]
    // and the message is sent anyway (fail-open) so a temporary network
    // blip doesn't block conversation.
    fun trySend(input: String) {
        val mod = moderationModel
        val img = attachedImage
        if (mod == null) { actuallySend(input, img); return }
        scope.launch {
            isModerating = true
            val previousCategory = com.ai.data.ApiTracer.currentCategory
            com.ai.data.ApiTracer.currentCategory = "Chat validate input"
            try {
                val (modProvider, modModelId) = mod
                val apiKey = aiSettings.getApiKey(modProvider)
                // Snapshot the wall clock just before the call so we can
                // pick out the resulting trace by "model match + timestamp
                // ≥ this value" — avoids grabbing an earlier trace of the
                // same model from a previous turn.
                val callStart = System.currentTimeMillis()
                val (results, apiResult) = com.ai.data.callModerationApi(modProvider, apiKey, modModelId, listOf(input))
                val r = results?.firstOrNull()
                if (apiResult.errorMessage != null || r == null) {
                    moderationError = apiResult.errorMessage ?: "No moderation result"
                    actuallySend(input, img)
                } else if (r.flagged) {
                    val traceFilename = withContext(Dispatchers.IO) {
                        com.ai.data.ApiTracer.getTraceFiles()
                            .filter { it.reportId == null && it.model == modModelId && it.timestamp >= callStart }
                            .minByOrNull { it.timestamp }
                            ?.filename
                    }
                    pendingFlagged = FlaggedState(input, r, img, traceFilename)
                } else {
                    actuallySend(input, img)
                }
            } finally {
                com.ai.data.ApiTracer.currentCategory = previousCategory
                isModerating = false
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${provider.displayName} / $model", fontSize = 12.sp, color = AppColors.TextTertiary,
                modifier = Modifier.weight(1f))
            // Knowledge attach chip — tap opens a multi-select
            // dialog over saved KBs. Shown only when at least one
            // KB exists. Per-turn injection happens in
            // ChatViewModel.{sendChatMessageStream,sendLocalLlmStream}.
            if (availableKbs.isNotEmpty()) {
                val kbLabel = if (attachedKnowledgeBaseIds.isEmpty()) "📚 Knowledge"
                    else "📚 ${attachedKnowledgeBaseIds.size}"
                Text(kbLabel, fontSize = 11.sp,
                    color = if (attachedKnowledgeBaseIds.isNotEmpty()) AppColors.Green else AppColors.TextTertiary,
                    modifier = Modifier
                        .clickable { showKbDialog = true }
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text(
                if (pinned) "📌 Pinned" else "📌 Pin",
                fontSize = 11.sp,
                color = if (pinned) AppColors.Orange else AppColors.TextTertiary,
                modifier = Modifier
                    .clickable {
                        pinned = !pinned
                        // Touch the persisted record immediately so the
                        // hub picks the new state up without waiting for
                        // the next message save.
                        ChatHistoryManager.setSessionPinned(currentSessionId, pinned)
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (showKbDialog) {
            com.ai.ui.knowledge.KnowledgeAttachDialog(
                knowledgeBases = availableKbs,
                initialSelectedIds = attachedKnowledgeBaseIds.toSet(),
                onDismiss = { showKbDialog = false },
                onConfirm = { selected ->
                    attachedKnowledgeBaseIds = selected.toList()
                    saveSession(messages)
                    showKbDialog = false
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)
        ) {
            if (displayMessages.isEmpty() && !isStreaming) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start a conversation...", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            } else {
                // Bottom-anchor: short conversations sit just above the
                // input row instead of pinned to the top with empty space
                // below (and getting pushed out of view when the keyboard
                // shrinks the viewport). Long conversations behave normally
                // — items scroll past the top edge as usual.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                ) {
                    items(displayMessages.size, key = { "${displayMessages[it].role}_${displayMessages[it].timestamp}_$it" }) { idx ->
                        val msg = displayMessages[idx]
                        ChatMessageBubble(msg, userName, model, onNavigateToTraceFile)
                    }
                    if (isStreaming) {
                        item(key = "streaming") {
                            val content = streamingContentState.value
                            if (content.isEmpty()) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AppColors.Blue, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Thinking...", color = AppColors.TextTertiary, fontSize = 13.sp)
                                }
                            } else {
                                StreamingMessageBubble(content)
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

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = useWebSearch,
                onClick = { useWebSearch = !useWebSearch },
                label = { Text("🌐 Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Blue.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Blue
                )
            )
            // Reasoning-effort pulldown — only on models that LiteLLM /
            // models.dev say support it (or whose id matches a known
            // reasoning family). The chip shows 🧠 + current level
            // ("none" when unset). Tapping opens a small menu with
            // none / low / medium / high.
            if (supportsReasoning) {
                Box {
                    val levelLabel = if (reasoningEffort.isBlank()) "none"
                        else reasoningEffort.replaceFirstChar { it.uppercase() }
                    FilterChip(
                        selected = reasoningEffort.isNotBlank(),
                        onClick = { reasoningMenuExpanded = true },
                        label = { Text("🧠 $levelLabel", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Purple.copy(alpha = 0.2f),
                            selectedLabelColor = AppColors.Purple
                        )
                    )
                    DropdownMenu(
                        expanded = reasoningMenuExpanded,
                        onDismissRequest = { reasoningMenuExpanded = false },
                        modifier = Modifier.background(Color(0xFF2D2D2D))
                    ) {
                        listOf("" to "None", "low" to "Low", "medium" to "Medium", "high" to "High").forEach { (value, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(label, fontSize = 13.sp,
                                        color = if (reasoningEffort == value) AppColors.Blue else Color.White)
                                },
                                onClick = { reasoningEffort = value; reasoningMenuExpanded = false }
                            )
                        }
                    }
                }
            }
            // Toggle: clicking when off opens the moderation model picker;
            // when on, taps clear the selection (off again). The chip
            // shows a 🛡 icon and either the model name or "Validate input".
            FilterChip(
                selected = moderationModel != null,
                onClick = {
                    if (moderationModel == null) showModerationPicker = true
                    else moderationModel = null
                },
                label = {
                    val label = moderationModel?.let { (p, m) -> "🛡 $m" } ?: "🛡 Validate input"
                    Text(label, fontSize = 12.sp, maxLines = 1)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Orange.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Orange
                )
            )
            if (isModerating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AppColors.Orange, strokeWidth = 2.dp)
            }
        }
        if (moderationError != null) {
            Text(
                "Moderation: ${moderationError}",
                fontSize = 11.sp, color = AppColors.Orange,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        attachedImage?.let { (mime, b64) ->
            val bmp = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Image attached (${mime.substringAfter('/')})", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                TextButton(onClick = { attachedImage = null }) { Text("Remove", color = AppColors.Red, fontSize = 12.sp) }
            }
            if (!isVisionCapable) {
                Text(
                    "⚠ Model is not flagged vision-capable in Model Info. The request may fail.",
                    fontSize = 11.sp, color = AppColors.Red, modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedButton(
                onClick = { pickImageLauncher.launch("image/*") },
                enabled = !isStreaming,
                colors = AppColors.outlinedButtonColors()
            ) { Text("📎", fontSize = 18.sp, maxLines = 1, softWrap = false) }
            OutlinedTextField(
                value = userInput, onValueChange = { userInput = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Type a message...") },
                maxLines = 4, colors = AppColors.outlinedFieldColors()
            )
            Button(
                onClick = { if ((userInput.isNotBlank() || attachedImage != null) && !isStreaming && !isModerating) trySend(userInput.trim()) },
                enabled = (userInput.isNotBlank() || attachedImage != null) && !isStreaming && !isModerating,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) { Text("Send", maxLines = 1, softWrap = false) }
        }
    }

    // Moderation model picker — single-pick by design. Filtered to
    // ModelType.MODERATION by default; toggle-able to widen if the
    // moderation model isn't in the user's catalog under that type.
    if (showModerationPicker) {
        com.ai.ui.report.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick moderation model",
            modelTypeFilter = com.ai.data.ModelType.MODERATION,
            onConfirm = { pick ->
                moderationModel = pick
                showModerationPicker = false
            },
            onBack = { showModerationPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    val flagged = pendingFlagged
    if (flagged != null) {
        AlertDialog(
            onDismissRequest = { pendingFlagged = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Input flagged by moderation", modifier = Modifier.weight(1f))
                    if (flagged.traceFilename != null) {
                        Text(
                            "🐞", fontSize = 18.sp,
                            modifier = Modifier
                                .clickable {
                                    // Don't clear pendingFlagged — the dialog is
                                    // saved across the navigation round-trip via
                                    // rememberSaveable, so it reappears on back
                                    // and the user can still choose Proceed /
                                    // Cancel after inspecting the trace.
                                    onNavigateToTraceFile(flagged.traceFilename)
                                }
                                .padding(start = 8.dp, end = 4.dp)
                        )
                    }
                }
            },
            text = {
                Column {
                    Text(
                        "The chosen moderation model flagged this input under: " +
                            flagged.result.firedCategories.joinToString(", "),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sending it to the chat model anyway may produce unsafe output or violate the chat provider's policy.",
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = flagged
                    pendingFlagged = null
                    actuallySend(s.input, s.img)
                }) { Text("Proceed anyway", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFlagged = null }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

// ===== Message Bubbles =====

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    userName: String = "You",
    model: String = "",
    onNavigateToTraceFile: (String) -> Unit = {}
) {
    val isUser = message.role == "user"
    // For assistant messages, look up the trace file recorded by the
    // OkHttp interceptor for this session's model. Match heuristic:
    // same model, no reportId (chat traces aren't tagged with one),
    // closest timestamp to the message. Off the UI thread because
    // ApiTracer.getTraceFiles parses every JSON.
    val traceFilenameState = if (isUser || model.isBlank()) null
        else produceState<String?>(initialValue = null, message.timestamp, model) {
            value = withContext(Dispatchers.IO) {
                com.ai.data.ApiTracer.getTraceFiles()
                    .filter { it.reportId == null && it.model == model }
                    .minByOrNull { kotlin.math.abs(it.timestamp - message.timestamp) }
                    ?.filename
            }
        }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) AppColors.Purple.copy(alpha = 0.15f) else AppColors.SurfaceDark
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isUser) userName else "Assistant",
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = if (isUser) AppColors.Purple else AppColors.Blue,
                    modifier = Modifier.weight(1f)
                )
                // 🐞 button — opens the trace for this assistant turn.
                // Only rendered when a matching trace exists; the lookup
                // result is null on user messages and on assistant
                // responses where tracing was off at call time.
                val traceFilename = traceFilenameState?.value
                if (traceFilename != null) {
                    Text(
                        "🐞", fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { onNavigateToTraceFile(traceFilename) }
                            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp, end = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            message.imageBase64?.let { b64 ->
                val bmp = remember(b64) {
                    try {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp))
                    )
                    if (message.content.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                }
            }
            if (message.content.isNotBlank()) {
                Text(message.content, fontSize = 14.sp, color = Color.White)
            }
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

    LaunchedEffect(content) {
        if (lines.size < visibleLineCount) visibleLineCount = lines.size
        while (visibleLineCount < lines.size) {
            kotlinx.coroutines.delay(500)
            visibleLineCount = minOf(visibleLineCount + 1, lines.size)
        }
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
