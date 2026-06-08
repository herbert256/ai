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
import com.ai.ui.shared.modelInfoClickable
import com.ai.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

    var systemPrompt by rememberSaveable { mutableStateOf("") }
    var selectedSystemPromptId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedParametersIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var temperature by rememberSaveable { mutableStateOf("") }
    var maxTokens by rememberSaveable { mutableStateOf("") }
    var topP by rememberSaveable { mutableStateOf("") }
    var topK by rememberSaveable { mutableStateOf("") }
    var frequencyPenalty by rememberSaveable { mutableStateOf("") }
    var presencePenalty by rememberSaveable { mutableStateOf("") }
    var returnCitations by rememberSaveable { mutableStateOf(true) }
    var searchRecency by rememberSaveable { mutableStateOf("") }
    var showParamsDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

    if (showParamsDialog) {
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings, selectedIds = selectedParametersIds,
            onConfirm = { selectedParametersIds = it },
            onBack = { showParamsDialog = false }, onNavigateHome = onNavigateHome
        )
        return
    }
    if (showSystemPromptDialog) {
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { id ->
                selectedSystemPromptId = id
                if (id != null) systemPrompt = aiSettings.getSystemPromptById(id)?.prompt ?: systemPrompt
            },
            onBack = { showSystemPromptDialog = false }, onNavigateHome = onNavigateHome
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "chat_parameters", title = "Chat Parameters", subject = "Set model & options before chatting", onBackClick = onNavigateBack,
            onParameters = { showParamsDialog = true }, onSystemPrompt = { showSystemPromptDialog = true })
        Text(com.ai.ui.shared.modelLabel(provider.id, model, separator = " / "),
            fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.modelInfoClickable(provider, model))
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // System-prompt / parameters preset selectors now live on the
            // bottom-bar 🎭 / 🌡️ icons (wired on the TitleBar above).
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

        OutlinedButton(
            onClick = {
                val presetParams = aiSettings.mergeParameters(selectedParametersIds)
                val resolvedSp = selectedSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: systemPrompt
                onStartChat(
                    ChatParameters(
                        systemPrompt = resolvedSp,
                        // Comma→dot before parse: the Decimal keyboard offers a
                        // comma on comma-decimal locales (nl-NL) and toFloatOrNull
                        // is dot-only, so "0,7" would silently drop the value.
                        temperature = temperature.replace(',', '.').toFloatOrNull() ?: presetParams?.temperature,
                        maxTokens = maxTokens.toIntOrNull() ?: presetParams?.maxTokens,
                        topP = topP.replace(',', '.').toFloatOrNull() ?: presetParams?.topP,
                        topK = topK.toIntOrNull() ?: presetParams?.topK,
                        frequencyPenalty = frequencyPenalty.replace(',', '.').toFloatOrNull() ?: presetParams?.frequencyPenalty,
                        presencePenalty = presencePenalty.replace(',', '.').toFloatOrNull() ?: presetParams?.presencePenalty,
                        searchEnabled = presetParams?.searchEnabled == true,
                        returnCitations = returnCitations && (presetParams?.returnCitations != false),
                        searchRecency = searchRecency.takeIf { it.isNotBlank() } ?: presetParams?.searchRecency,
                        webSearchTool = presetParams?.webSearchTool == true,
                        reasoningEffort = presetParams?.reasoningEffort
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()
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

private fun chatMessageListKey(message: ChatMessage, index: Int): String =
    message.id ?: "legacy_${message.role}_${message.timestamp}_${message.content.hashCode()}_$index"

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

/** Saver for the (mime, base64) attached-image pair so a rotation
 *  or process-recreation between the user picking an image and
 *  tapping Send doesn't drop the attachment. The base64 string can
 *  be sizeable (a few MB) but is bounded by the picker's downscale —
 *  Bundle has a ~1 MB practical limit, larger images may not survive
 *  process death; this still covers rotation and the common case. */
private val AttachedImageSaver = androidx.compose.runtime.saveable.Saver<Pair<String, String>?, java.util.ArrayList<String>>(
    save = { pair ->
        if (pair == null) java.util.ArrayList()
        else java.util.ArrayList<String>().apply {
            add(pair.first)
            add(pair.second)
        }
    },
    restore = { list ->
        if (list.size < 2) null
        else list[0] to list[1]
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
    /** Repository used for the background `chat_title` AI call. Threaded
     *  through from AppNavHost so the screen can fire a single-shot
     *  `analyzeWithAgent` after the first assistant response without
     *  pulling in an entire AppViewModel reference. */
    repository: AnalysisRepository,
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
    onConsumeStarter: () -> Unit = {},
    /** Master experimental-features gate. When false the Knowledge
     *  attach chip is hidden unless this session already has a KB
     *  attached, so existing RAG context remains visible/removable. */
    experimentalFeatures: Boolean = false
) {
    // Outer BackHandler: only active when no overlay (moderation
    // picker / flagged-input dialog) is up. Without the conditional
    // guard the chat session's own back-handler took precedence over
    // the picker's onBack and the flagged dialog's dismiss path,
    // because Compose registers BackHandlers in declaration order
    // and the outermost one was previously unconditional.
    val context = LocalContext.current
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // rememberSaveable: a new-chat (null sessionId) must keep its minted UUID
    // across recreation, else rotation orphans the prior save under the old id.
    val currentSessionId = rememberSaveable { sessionId ?: java.util.UUID.randomUUID().toString() }

    // Load the persisted session ONCE, off the main thread. Parsing an
    // image-heavy session's multi-MB JSON in a plain remember{} blocked first
    // composition, and it used to be parsed twice (messages here + metadata
    // below). Everything below now seeds from this single object; a short spinner
    // shows until the IO load resolves. See audit chat bugs 1 + 2.
    val sessionLoad = produceState<Pair<Boolean, ChatSession?>>(false to null, currentSessionId) {
        val loaded = withContext(Dispatchers.IO) { ChatHistoryManager.loadSession(currentSessionId) }
        value = true to loaded
    }
    val (sessionLoaded, persistedSession) = sessionLoad.value
    if (!sessionLoaded) {
        Box(Modifier.fillMaxSize().background(AppColors.AppBackground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.PrimaryAccent)
        }
        return
    }

    // Seed from the loaded session keyed on the (saveable) session id, NOT a
    // plain remember of initialMessages — otherwise recreation resets the UI to
    // the stale snapshot and the next saveSession overwrites the on-disk session
    // with that truncated set. Re-reading on recreation recovers turns saved so far.
    val initialMessagesForSession = remember(currentSessionId) {
        persistedSession?.messages ?: initialMessages
    }
    var messages by remember(currentSessionId) { mutableStateOf(initialMessagesForSession) }
    // Pre-fill the input box with text staged by the share-target
    // chooser, then drop the staged value so leaving + returning
    // doesn't re-stuff it.
    val starter = remember { initialUserInput }
    val starterImage = remember {
        val mime = initialUserImageMime
        val b64 = initialUserImageBase64
        if (mime != null && b64 != null) mime to b64 else null
    }
    // Gate the one-time starter seed on a saveable flag so a second
    // composition (config change before the parent clears the staged
    // starter from UiState) can't re-seed and overwrite typed text.
    var starterConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!starterConsumed) { onConsumeStarter(); starterConsumed = true }
    }
    // rememberSaveable so a rotation / process-recreation between
    // staging the prompt (or an attached image) and tapping Send
    // doesn't drop the user's typed text and image. Once the starter is
    // consumed the saved state takes over; the seed only applies on the
    // very first composition.
    var userInput by rememberSaveable { mutableStateOf(if (starterConsumed) "" else (starter ?: "")) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    val streamingContentState = remember { mutableStateOf("") }
    // Accumulate per-turn token counts rather than a baked-in cost sum so the
    // running total is always priced at the CURRENT pricing tier. This fixes
    // the cold-pricing window: turns that completed before PricingCache primed
    // used to stay frozen at default rates in the accumulator; now they re-price
    // once `pricing` recomputes (matching DualChatScreen's convention).
    val initialTokenTotals = remember(currentSessionId) {
        estimatePersistedChatTokenTotals(initialMessagesForSession)
    }
    var totalInputTokens by remember(currentSessionId) { mutableIntStateOf(initialTokenTotals.first) }
    var totalOutputTokens by remember(currentSessionId) { mutableIntStateOf(initialTokenTotals.second) }
    var totalCostHasUpperBoundEstimate by remember(currentSessionId) { mutableStateOf(false) }
    // (mime, base64) of an image attached to the next user message.
    // rememberSaveable via a Saver so a rotation / process-recreation
    // between picking the image and tapping Send doesn't drop it.
    var attachedImage by rememberSaveable(stateSaver = AttachedImageSaver) {
        mutableStateOf(starterImage)
    }
    var useWebSearch by remember { mutableStateOf(parameters.webSearchTool) }
    // Per-turn reasoning-effort hint. "" = no hint; "low"/"medium"/"high"
    // map to the same OpenAI Responses-API / Gemini thinking field
    // ParametersScreen exposes. Initialized from the configure-on-the-
    // fly preset so a "high" preset starts that way; user can change
    // per turn via the pulldown next to the web-search chip.
    var reasoningEffort by remember { mutableStateOf(parameters.reasoningEffort ?: "") }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }
    var chipPersistencePrimed by remember(currentSessionId) { mutableStateOf(false) }
    // Clamp the persisted reasoning level against the active model's
    // supported set on first composition. A session resumed from a
    // model that supports `max` against a model that only supports
    // low/medium/high used to ship the unsupported value through to
    // the API.
    LaunchedEffect(provider, model) {
        val supported = aiSettings.getProvider(provider).modelCapabilities[model]?.reasoningEffortLevels
        if (!supported.isNullOrEmpty() && reasoningEffort.isNotBlank() && reasoningEffort !in supported) {
            reasoningEffort = ""
        }
    }
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
    var moderationError by rememberSaveable { mutableStateOf<String?>(null) }
    var isModerating by remember { mutableStateOf(false) }
    var sendInFlight by rememberSaveable { mutableStateOf(false) }

    // Conditional outer BackHandler — disabled while the moderation
    // picker overlay or the flagged-input dialog is up so back-press
    // dismisses those instead of the whole session. Replaces the
    // unconditional BackHandler that used to live at the top of the
    // function.
    BackHandler(enabled = !showModerationPicker && pendingFlagged == null) {
        onNavigateBack()
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val pair = withContext(Dispatchers.IO) {
                    runCatching { com.ai.data.loadImageAsBase64(context, uri) }.getOrNull()
                }
                if (pair != null) attachedImage = pair
                else error = "Failed to attach image"
            }
        }
    }

    // Recompute pricing whenever PricingCache fully primes (its
    // preloadCompleted flag flips). Without that, an unkeyed
    // remember(provider, model) latched DEFAULT_PRICING on first
    // composition during the cold-load window, and chat cost banners
    // stayed at $0.00 for the entire session even after the catalog
    // finished loading. Same pattern as DualChatScreen.
    val pricingTick = com.ai.ui.shared.resumeRefreshTick()
    val pricing = remember(provider, model, pricingTick) { PricingCache.getPricing(context, provider, model) }
    // Running cost in cents, always priced at the current tier (re-derives when
    // pricing primes or token totals change).
    val totalCost by remember(pricing) {
        derivedStateOf {
            (totalInputTokens * pricing.promptPrice + totalOutputTokens * pricing.completionPrice) * 100
        }
    }
    val totalCostSubject = when {
        totalCost <= 0.0 -> null
        totalCostHasUpperBoundEstimate && totalCost < 0.01 -> "≤0.01c"
        totalCostHasUpperBoundEstimate -> "≤%.2fc".format(Locale.US, totalCost)
        totalCost < 0.01 -> "<0.01c"
        else -> "%.2fc".format(Locale.US, totalCost)
    }

    // persistedSession is loaded once, off-main, at the top of this composable.
    // Read the persisted pinned flag once on entry so subsequent saves
    // preserve it. Toggled below by the 📌 pill next to the model line.
    var pinned by remember(currentSessionId) {
        mutableStateOf(persistedSession?.pinned == true)
    }
    // Knowledge bases attached to this session — read once on entry,
    // toggled by the 📚 chip below the model line. Persisted with
    // every saveSession so resume keeps the attachment.
    var attachedKnowledgeBaseIds by remember(currentSessionId) {
        mutableStateOf(persistedSession?.knowledgeBaseIds.orEmpty())
    }
    var showKbDialog by remember { mutableStateOf(false) }
    val kbRefreshTick = com.ai.ui.shared.resumeRefreshTick()
    val availableKbs = remember(kbRefreshTick) { com.ai.data.KnowledgeStore.listKnowledgeBases(context) }

    // Display title. Seeded from a previously persisted value so a
    // resumed session keeps whatever title the AI generated last
    // time. Blank for fresh sessions; populated on first send with
    // the first 10 words of the user's prompt and then replaced
    // asynchronously by the chat_title internal prompt.
    var sessionTitle by rememberSaveable(currentSessionId) {
        mutableStateOf(persistedSession?.title.orEmpty())
    }

    fun saveSession(msgs: List<ChatMessage>) {
        // Persist the current chip state alongside the original
        // params object — the screen's local useWebSearch and
        // reasoningEffort vars are what the user sees and what the
        // dispatch layer actually used; saving the pristine
        // `parameters` would silently revert those toggles when the
        // user reopens the session.
        val persistedParams = parameters.copy(
            webSearchTool = useWebSearch,
            reasoningEffort = reasoningEffort.ifBlank { null }
        )
        // Build the record on the (cheap) caller thread but push the
        // atomic file write off the main thread — a large image-heavy
        // session JSON write otherwise blocks the UI on every send /
        // system-prompt change / pin-or-KB toggle.
        val session = ChatSession(id = currentSessionId, provider = provider, model = model, messages = msgs, parameters = persistedParams, updatedAt = System.currentTimeMillis(), pinned = pinned, knowledgeBaseIds = attachedKnowledgeBaseIds, title = sessionTitle)
        scope.launch(Dispatchers.IO) { ChatHistoryManager.saveSession(session) }
    }

    LaunchedEffect(useWebSearch, reasoningEffort) {
        if (!chipPersistencePrimed) {
            chipPersistencePrimed = true
            return@LaunchedEffect
        }
        if (messages.none { it.role == "user" }) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        saveSession(messages)
    }

    // System prompt initialization. Replace any existing system
    // message in-place so a parameter swap mid-session (e.g.
    // configure-on-the-fly Chat that recomputes a preset) actually
    // takes effect on the next call. The previous flow only seeded
    // when `messages` was empty, so a new system prompt arriving
    // after the first user/assistant turn was silently ignored.
    LaunchedEffect(parameters.systemPrompt) {
        val sp = parameters.systemPrompt
        val current = messages.firstOrNull { it.role == "system" }
        var changed = false
        when {
            sp.isBlank() -> if (current != null) {
                messages = messages.filter { it.role != "system" }; changed = true
            }
            current == null -> {
                messages = listOf(ChatMessage(role = "system", content = sp)) + messages; changed = true
            }
            current.content != sp -> {
                messages = messages.map { if (it.role == "system") it.copy(content = sp) else it }
                changed = true
            }
        }
        // Persist the system-prompt merge only once the session has a real user
        // turn. Otherwise, entering a configure-on-the-fly chat that carries a
        // system prompt and leaving without sending wrote a system-message-only
        // "Empty chat" to history (audit chat#10). The in-memory merge still
        // applies; actuallySend saves it together with the first user message.
        if (changed && messages.any { it.role == "user" }) saveSession(messages)
    }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    // Auto-scroll — parent only reads messages.size and isStreaming (booleans); chunk updates
    // are observed via snapshotFlow so the parent doesn't recompose per chunk.
    val displayMessages = remember(messages) { messages.filter { it.role != "system" } }
    val traceVersion by com.ai.data.ApiTracer.traceVersion.collectAsState()
    val traceFilenameByMessageKey by produceState<Map<String, String>>(
        initialValue = emptyMap(),
        displayMessages,
        model,
        currentSessionId,
        traceVersion
    ) {
        value = if (model.isBlank()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                val all = com.ai.data.ApiTracer.getTraceFiles().filter { it.model == model }
                val scoped = all.filter { it.reportId == currentSessionId }
                val legacy = all.filter { it.reportId == null }
                val candidates = scoped.ifEmpty { legacy }
                if (candidates.isEmpty()) {
                    emptyMap()
                } else {
                    displayMessages.mapIndexedNotNull { index, msg ->
                        if (msg.role == "user") {
                            null
                        } else {
                            val filename = candidates
                                .minByOrNull { kotlin.math.abs(it.timestamp - msg.timestamp) }
                                ?.filename
                            filename?.let { chatMessageListKey(msg, index) to it }
                        }
                    }.toMap()
                }
            }
        }
    }
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
        // Seed a sensible default title from the first 10 words of
        // the first user message. The chat_title internal prompt
        // (kicked off after the first assistant response) replaces
        // this with a short AI-generated title shortly after.
        if (sessionTitle.isBlank()) {
            sessionTitle = input.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .take(10)
                .joinToString(" ")
        }
        saveSession(messages)

        // Snapshot the per-turn flags + attachments at entry. The
        // coroutine below captures these by-reference; if the user
        // toggles 🌐 / changes 📚 selection / changes 🧠 effort while
        // streaming, the in-flight request would otherwise read the
        // updated values instead of the ones that were active when
        // they hit Send.
        val sentWebSearch = useWebSearch
        val sentReasoning = reasoningEffort
        val sentKbIds = attachedKnowledgeBaseIds
        val sentMessages = messages
        // Add LiteLLM-reported tool_use overhead when web-search is on so
        // the client-side cost estimate isn't 5–10× under the actual bill
        // for tool-using turns (Claude with web_search adds ~3-4k system
        // tokens; the conversation text alone misses that). Chat streaming
        // exposes text chunks only, not a final "tool actually ran" flag, so
        // this is an upper-bound estimate and the title cost gets a ≤ prefix.
        val toolOverhead = if (sentWebSearch) (PricingCache.liteLLMToolUseOverhead(provider, model) ?: 0) else 0
        val inputTokens = messages.sumOf { AppViewModel.estimateTokens(it.content) } + toolOverhead
        val inputTokensAreUpperBound = toolOverhead > 0

        scope.launch {
            isStreaming = true; streamingContentState.value = ""
            val sb = StringBuilder()
            try {
                // Tag chat traces with this session's id (reportId slot) so the
                // per-bubble 🐞 lookup can filter to THIS session's traces — a
                // model-name + closest-timestamp match alone pulled in traces
                // from other chat sessions using the same model.
                com.ai.data.withTracerTags(reportId = currentSessionId, category = "Chat") {
                    onSendMessageStream(sentMessages, sentWebSearch, sentReasoning, sentKbIds).collect { chunk -> sb.append(chunk); streamingContentState.value = sb.toString() }
                }
                // Build the saved message and token count from the local
                // StringBuilder that actually accumulated the chunks, not from
                // the mutable UI state (which the finally block clears) — these
                // can diverge if an exception lands between append and assign.
                val finalContent = sb.toString()
                val assistantMsg = ChatMessage(role = "assistant", content = finalContent)
                val isFirstAssistantTurn = messages.none { it.role == "assistant" }
                messages = messages + assistantMsg
                saveSession(messages)
                val outputTokens = AppViewModel.estimateTokens(finalContent)
                totalInputTokens += inputTokens
                totalOutputTokens += outputTokens
                if (inputTokensAreUpperBound) totalCostHasUpperBoundEstimate = true
                onRecordStatistics(inputTokens, outputTokens)
                // After the very first assistant response, kick off a
                // background DeepSeek call (chat_title internal prompt)
                // to replace the 10-word default with a short
                // AI-generated title. Silent on failure — the default
                // stays. Subsequent turns don't re-trigger.
                if (isFirstAssistantTurn) {
                    val firstUser = sentMessages.firstOrNull { it.role == "user" }?.content.orEmpty()
                    kickOffChatTitleGeneration(
                        context = context,
                        scope = scope,
                        repository = repository,
                        aiSettings = aiSettings,
                        userPrompt = firstUser,
                        assistantResponse = assistantMsg.content,
                        onTitleResolved = { newTitle ->
                            sessionTitle = newTitle
                            saveSession(messages)
                        },
                        onUsageResolved = { inputTokens, outputTokens ->
                            totalInputTokens += inputTokens
                            totalOutputTokens += outputTokens
                            onRecordStatistics(inputTokens, outputTokens)
                        }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User left the screen / closed the app. Don't persist a
                // "[Stream interrupted]" line into the saved session — the
                // partial chunks weren't really an error from the user's
                // perspective. Re-throw so structured cancellation works.
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Streaming error"
                if (sb.isNotEmpty()) {
                    val partialContent = sb.toString()
                    messages = messages + ChatMessage(role = "assistant", content = "$partialContent\n\n[Stream interrupted: ${e.message}]")
                    saveSession(messages)
                    val outputTokens = AppViewModel.estimateTokens(partialContent)
                    totalInputTokens += inputTokens
                    totalOutputTokens += outputTokens
                    if (inputTokensAreUpperBound) totalCostHasUpperBoundEstimate = true
                    onRecordStatistics(inputTokens, outputTokens)
                }
            } finally {
                isStreaming = false; streamingContentState.value = ""
                sendInFlight = false
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
        if (sendInFlight) return
        sendInFlight = true
        val mod = moderationModel
        val img = attachedImage
        // Reset any leftover moderation error from a previous turn —
        // without this, a once-failed moderation call left its error
        // banner stuck across every subsequent send until the user
        // toggled the moderation picker off and back on.
        moderationError = null
        if (mod == null) { actuallySend(input, img); return }
        scope.launch {
            isModerating = true
            var handedOffToSend = false
            try {
                com.ai.data.withTraceCategory("Chat validate input") {
                    val (modProvider, modModelId) = mod
                    val apiKey = aiSettings.getApiKey(modProvider)
                    val traceSink = java.util.concurrent.atomic.AtomicReference<String?>()
                    val (results, apiResult) = com.ai.data.withTraceFilenameSink(traceSink) {
                        com.ai.data.callModerationApi(modProvider, apiKey, modModelId, listOf(input))
                    }
                    val r = results?.firstOrNull()
                    if (apiResult.errorMessage != null || r == null) {
                        moderationError = apiResult.errorMessage ?: "No moderation result"
                        handedOffToSend = true
                        actuallySend(input, img)
                    } else if (r.flagged) {
                        pendingFlagged = FlaggedState(input, r, img, traceSink.get())
                    } else {
                        handedOffToSend = true
                        actuallySend(input, img)
                    }
                }
            } finally {
                isModerating = false
                if (!handedOffToSend && pendingFlagged == null) {
                    sendInFlight = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        val navToModelInfo = com.ai.ui.shared.LocalNavigateToModelInfo.current
        TitleBar(
            helpTopic = "chat_session",
            title = "Chat", onBackClick = onNavigateBack,
            // Cost label used to ride in the (now-retired) leftContent
            // slot of the top bar. With the bottom bar carrying back
            // and every action icon, the chat's running cost surfaces
            // here as the title bar's subject — when set, it slots
            // alongside the "Chat" title in BOTH mode or replaces it
            // in SUBJECT mode. Sub-cent costs render "<0.01c" so a
            // tiny cost reads differently from a literal zero.
            subject = totalCostSubject,
            onInfo = { navToModelInfo(provider, model) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.ai.ui.shared.modelLabel(provider.id, model, separator = " / "),
                fontSize = 12.sp, color = AppColors.TextTertiary,
                modifier = Modifier.weight(1f).modelInfoClickable(provider, model))
            // Knowledge attach chip — tap opens a multi-select
            // dialog over saved KBs. Shown only when at least one
            // KB exists. Per-turn injection happens in
            // ChatViewModel.{sendChatMessageStream,sendLocalLlmStream}.
            if ((experimentalFeatures && availableKbs.isNotEmpty()) || attachedKnowledgeBaseIds.isNotEmpty()) {
                val kbLabel = if (attachedKnowledgeBaseIds.isEmpty()) "${mi.library} Knowledge"
                    else "${mi.library} ${attachedKnowledgeBaseIds.size}"
                Text(kbLabel, fontSize = 11.sp,
                    color = if (attachedKnowledgeBaseIds.isNotEmpty()) AppColors.SuccessAccent else AppColors.TextTertiary,
                    modifier = Modifier
                        .clickable { showKbDialog = true }
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text(
                if (pinned) "${mi.pin} Pinned" else "${mi.pin} Pin",
                fontSize = 11.sp,
                color = if (pinned) AppColors.WarningAccent else AppColors.TextTertiary,
                modifier = Modifier
                    .clickable {
                        pinned = !pinned
                        // Touch the persisted record so the hub picks up the
                        // new state without waiting for the next message save.
                        // setSessionPinned does a load+rewrite, so push it off
                        // the main thread.
                        val newPinned = pinned
                        scope.launch(Dispatchers.IO) { ChatHistoryManager.setSessionPinned(currentSessionId, newPinned) }
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
                    items(
                        displayMessages.size,
                        // New messages carry a persisted UUID. Legacy
                        // sessions saved before that field fall back to a
                        // composite plus index so duplicate old rows cannot
                        // collide and crash LazyColumn.
                        key = { chatMessageListKey(displayMessages[it], it) }
                    ) { idx ->
                        val msg = displayMessages[idx]
                        ChatMessageBubble(
                            message = msg,
                            userName = userName,
                            traceFilename = traceFilenameByMessageKey[chatMessageListKey(msg, idx)],
                            onNavigateToTraceFile = onNavigateToTraceFile
                        )
                    }
                    if (isStreaming) {
                        item(key = "streaming") {
                            val content = streamingContentState.value
                            if (content.isEmpty()) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AppColors.InfoAccent, strokeWidth = 2.dp)
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
            Text(error!!, color = AppColors.DangerAccent, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = useWebSearch,
                onClick = { useWebSearch = !useWebSearch },
                label = { Text("${mi.web} Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.InfoAccent.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.InfoAccent
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
                        label = { Text("${mi.reportModelIcon} $levelLabel", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryAccent.copy(alpha = 0.2f),
                            selectedLabelColor = AppColors.PrimaryAccent
                        )
                    )
                    DropdownMenu(
                        expanded = reasoningMenuExpanded,
                        onDismissRequest = { reasoningMenuExpanded = false },
                        modifier = Modifier.background(AppColors.SurfaceDark)
                    ) {
                        // Narrow the option list to whatever the model
                        // self-reports it accepts (Anthropic
                        // capabilities.effort.{low,medium,high,max}). Fall
                        // back to the legacy low/medium/high set when the
                        // provider's /models response didn't carry per-
                        // level info — every other provider currently.
                        val perModelLevels = aiSettings.getProvider(provider)
                            .modelCapabilities[model]?.reasoningEffortLevels
                        val effortValues = perModelLevels ?: listOf("low", "medium", "high")
                        val options = listOf("" to "None") + effortValues.map { v ->
                            v to v.replaceFirstChar { it.uppercase() }
                        }
                        options.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(label, fontSize = 13.sp,
                                        color = if (reasoningEffort == value) AppColors.InfoAccent else AppColors.TextPrimary)
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
                    val label = moderationModel?.let { (p, m) -> "${mi.shield} $m" } ?: "${mi.shield} Validate input"
                    Text(label, fontSize = 12.sp, maxLines = 1)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.WarningAccent.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.WarningAccent
                )
            )
            if (isModerating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AppColors.WarningAccent, strokeWidth = 2.dp)
            }
        }
        if (moderationError != null) {
            Text(
                "Moderation: ${moderationError}",
                fontSize = 11.sp, color = AppColors.WarningAccent,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        attachedImage?.let { (mime, b64) ->
            // Decode off-thread: BitmapFactory.decodeByteArray on a
            // multi-MB photo blocks the UI thread long enough to ANR
            // on lower-end devices. produceState gives us null while
            // decoding, then the bitmap.
            val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, b64) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                val bmpLocal = bmp
                if (bmpLocal != null) {
                    Image(bitmap = bmpLocal.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Image attached (${mime.substringAfter('/')})", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                TextButton(onClick = { attachedImage = null }) { Text("Remove", color = AppColors.DangerAccent, fontSize = 12.sp) }
            }
            if (!isVisionCapable) {
                Text(
                    "${mi.warningPlain} Model is not flagged vision-capable in Model Info. The request may fail.",
                    fontSize = 11.sp, color = AppColors.DangerAccent, modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedButton(
                onClick = { pickImageLauncher.launch("image/*") },
                enabled = !isStreaming,
                colors = AppColors.outlinedButtonColors()
            ) { Text(com.ai.data.MetadataIconsHolder.current.attach, fontSize = 18.sp, maxLines = 1, softWrap = false) }
            OutlinedTextField(
                value = userInput, onValueChange = { userInput = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Type a message...") },
                maxLines = 4, colors = AppColors.outlinedFieldColors()
            )
            OutlinedButton(
                onClick = { if ((userInput.isNotBlank() || attachedImage != null) && !isStreaming && !isModerating && !sendInFlight) trySend(userInput.trim()) },
                enabled = (userInput.isNotBlank() || attachedImage != null) && !isStreaming && !isModerating && !sendInFlight,
                colors = AppColors.outlinedButtonColors()
            ) { Text("Send", maxLines = 1, softWrap = false) }
        }
    }

    // Moderation model picker — single-pick by design. Filtered to
    // ModelType.MODERATION by default; toggle-able to widen if the
    // moderation model isn't in the user's catalog under that type.
    if (showModerationPicker) {
        com.ai.ui.other.ReportSelectModelsScreen(
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
            onDismissRequest = {
                pendingFlagged = null
                sendInFlight = false
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Input flagged by moderation", modifier = Modifier.weight(1f))
                    if (ApiTracer.ladybugLinksEnabled && flagged.traceFilename != null) {
                        Text(
                            com.ai.data.MetadataIconsHolder.current.traces, fontSize = 18.sp,
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
                }) { Text("Proceed anyway", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingFlagged = null
                    sendInFlight = false
                }) {
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
    traceFilename: String? = null,
    onNavigateToTraceFile: (String) -> Unit = {},
) {
    val isUser = message.role == "user"
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) AppColors.PrimaryAccent.copy(alpha = 0.15f) else AppColors.SurfaceDark
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isUser) userName else "Assistant",
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = if (isUser) AppColors.PrimaryAccent else AppColors.InfoAccent,
                    modifier = Modifier.weight(1f)
                )
                // 🐞 button — opens the trace for this assistant turn.
                // Only rendered when a matching trace exists; the lookup
                // result is null on user messages and on assistant
                // responses where tracing was off at call time. Also
                // suppressed wholesale when API tracing is off in
                // Settings — old recordings stay on disk but the icons
                // disappear.
                if (ApiTracer.ladybugLinksEnabled && traceFilename != null) {
                    Text(
                        com.ai.data.MetadataIconsHolder.current.traces, fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { onNavigateToTraceFile(traceFilename) }
                            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp, end = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            message.imageBase64?.let { b64 ->
                val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, b64) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            val bytes = Base64.decode(b64, Base64.NO_WRAP)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) { null }
                    }
                }
                val bmpLocal = bmp
                if (bmpLocal != null) {
                    Image(
                        bitmap = bmpLocal.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp))
                    )
                    if (message.content.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                }
            }
            if (message.content.isNotBlank()) {
                Text(message.content, fontSize = 14.sp, color = AppColors.TextPrimary)
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
                Text("Assistant", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = AppColors.InfoAccent)
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = AppColors.InfoAccent, strokeWidth = 1.5.dp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            AnimatedTextLines(content)
        }
    }
}

@Composable
private fun AnimatedTextLines(content: String) {
    val lines = remember(content) { content.lineSequence().toList() }
    val targetLineCount = lines.size
    val animateLines = targetLineCount <= 12
    var visibleLineCount by remember { mutableIntStateOf(0) }

    // Key on the line-count target, not on `content`: keying on content
    // restarted the delay loop on EVERY chunk, which made the reveal flicker
    // and oscillate. The target only grows during streaming, so the loop walks
    // monotonically up to it. Snap when there are too many lines to reveal
    // gracefully (a 100-line response used to take ~50s at the old cadence).
    LaunchedEffect(targetLineCount, animateLines) {
        if (!animateLines) {
            visibleLineCount = targetLineCount
            return@LaunchedEffect
        }
        while (visibleLineCount < targetLineCount) {
            kotlinx.coroutines.delay(80)
            visibleLineCount = minOf(visibleLineCount + 1, targetLineCount)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            val alpha = if (animateLines) {
                val targetAlpha = if (index < visibleLineCount) 1f else 0f
                val animatedAlpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))
                animatedAlpha
            } else {
                1f
            }
            Text(line, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.alpha(alpha))
        }
    }
}

// The former ParametersSelectorDialog / SystemPromptSelectorDialog are
// gone — replaced by the full-screen com.ai.ui.shared.ParametersSelectScreen
// / SystemPromptSelectScreen opened from the 🌡️ / 🎭 bottom-bar icons.

private fun estimatePersistedChatTokenTotals(messages: List<ChatMessage>): Pair<Int, Int> {
    var inputTokens = 0
    var outputTokens = 0
    messages.forEachIndexed { index, message ->
        if (message.role == "assistant") {
            inputTokens += messages.take(index).sumOf { AppViewModel.estimateTokens(it.content) }
            val billedContent = message.content.substringBefore("\n\n[Stream interrupted:")
            outputTokens += AppViewModel.estimateTokens(billedContent)
        }
    }
    return inputTokens to outputTokens
}

/** Fire-and-forget call to the `chat_title` internal prompt. Mirrors
 *  [com.ai.viewmodel.ReportViewModel.kickOffIconGeneration]: looks up
 *  the bundled prompt, resolves the pinned agent (DeepSeek by
 *  default), substitutes `@PROMPT@` / `@RESPONSE@`, dispatches via
 *  [AnalysisRepository.analyzeWithAgent], and on success calls
 *  [onTitleResolved] back on the main thread. Silent on every
 *  failure path — the 10-word default seeded at send time stays. */
private fun kickOffChatTitleGeneration(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    repository: AnalysisRepository,
    aiSettings: Settings,
    userPrompt: String,
    assistantResponse: String,
    onTitleResolved: (String) -> Unit,
    onUsageResolved: suspend (Int, Int) -> Unit
) {
    val prompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "internal" && it.name.equals("chat-title", ignoreCase = true)
    } ?: return
    val rawAgent = aiSettings.resolvePromptAgent(prompt) ?: return
    val agent = rawAgent.copy(
        apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
        model = aiSettings.getEffectiveModelForAgent(rawAgent)
    )
    val resolved = prompt.text
        .replace("@PROMPT@", userPrompt)
        .replace("@RESPONSE@", assistantResponse)
    scope.launch(Dispatchers.IO) {
        com.ai.data.withTraceCategory("Chat title") {
            runCatching {
                val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val response = repository.analyzeWithAgent(
                    agent, "", resolved, AgentParameters(),
                    null, context, baseUrl
                )
                if (response.error == null) {
                    response.tokenUsage?.let { usage ->
                        val inputTokens = usage.inputTokens + usage.cachedInputTokens + usage.cacheCreationTokens
                        val outputTokens = usage.outputTokens + usage.reasoningTokens
                        if (inputTokens > 0 || outputTokens > 0) {
                            withContext(Dispatchers.Main) { onUsageResolved(inputTokens, outputTokens) }
                        }
                    }
                }
                val raw = response.analysis?.trim().orEmpty()
                if (response.error != null || raw.isEmpty()) return@runCatching
                // Strip surrounding quotes / trailing period that some
                // models add despite "no other text" instruction.
                val cleaned = raw
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .trim()
                    .trimEnd('.')
                    .trim()
                if (cleaned.isNotEmpty()) {
                    withContext(Dispatchers.Main) { onTitleResolved(cleaned) }
                }
            }
        }
    }
}
