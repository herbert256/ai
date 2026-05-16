package com.ai.ui.hub

import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.data.AnalysisRepository
import com.ai.data.ApiTracer
import com.ai.data.KnowledgeService
import com.ai.data.KnowledgeStore
import com.ai.data.local.LocalEmbedder
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.ai.ui.knowledge.displayNameForUri
import com.ai.ui.knowledge.pickTypeForUri
import com.ai.ui.search.supportedEmbeddingChoices
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HubScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTraces: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToReportsHub: () -> Unit,
    onNavigateToUsage: () -> Unit,
    onNavigateToChatsHub: () -> Unit,
    onNavigateToAiSetup: () -> Unit,
    onNavigateToHousekeeping: () -> Unit,
    onNavigateToModelSearch: () -> Unit,
    onNavigateToKnowledge: () -> Unit = {},
    onOpenLatestReport: () -> Unit = {},
    viewModel: AppViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BackHandler { (context as? Activity)?.moveTaskToBack(true) }

    val hasAnyAgent = remember(uiState.aiSettings.agents) { uiState.aiSettings.agents.isNotEmpty() }
    // Re-fire whenever the agent list changes. Was keyed on the whole
    // uiState, which churned ~30 times during refreshAllModelLists (each
    // model-list fetch touches aiSettings.providers but not agents) and
    // dragged the main thread through repeated disk work for nothing. The
    // narrower key still picks up the once-per-bootstrap transition that
    // ensureUsageStatsCache needs to retry past a ProviderRegistry-init
    // race, plus any agent edit during the session.
    val hasStatisticsData by produceState(initialValue = false, uiState.aiSettings.agents) {
        value = withContext(Dispatchers.IO) {
            val sp = SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir)
            sp.loadUsageStats().isNotEmpty()
        }
    }
    val hasTraces by produceState(initialValue = false, uiState.aiSettings.agents) {
        // hasAnyTraceFile only enumerates filenames — no JSON parse, vs the
        // ~250-file parse getTraceFiles() does for the full list.
        value = withContext(Dispatchers.IO) { ApiTracer.hasAnyTraceFile() }
    }
    // Drives the logo's clickability — tapping the logo opens the
    // most recent report's result page. Re-fires on resume so a
    // freshly-finished report is reachable without a process restart.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val hasAnyReport by produceState(initialValue = false, refreshTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context).isNotEmpty() }
    }

    // The AI API Traces card disappears entirely when tracing is off \u2014
    // adjust the card count so the logo sizing math still works.
    val tracingEnabled = uiState.generalSettings.tracingEnabled
    val cardHeight = 50.dp
    val cardSpacing = 12.dp
    val cardCount = if (tracingEnabled) 10 else 9
    val cardsHeight = (cardHeight * cardCount) + (cardSpacing * (cardCount - 1)) + 32.dp

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)
    ) {
        val logoSize = (maxHeight - cardsHeight).coerceIn(100.dp, 220.dp)
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "AI App Logo",
                modifier = Modifier.size(logoSize).offset(y = (-32).dp)
                    .then(if (hasAnyReport) Modifier.clickable { onOpenLatestReport() } else Modifier)
            )
            // Inactive cards are hidden entirely (rather than rendered
            // grayed-out + non-clickable as in earlier builds). Each
            // visibility-gated card carries its own trailing Spacer
            // inside the `if`, so the gap goes with it and the layout
            // stays compact.
            if (hasAnyAgent) {
                HubCard(icon = "\uD83D\uDCDD", title = "AI Reports", onClick = onNavigateToReportsHub)
                Spacer(modifier = Modifier.height(12.dp))
                HubCard(icon = "\uD83D\uDCAC", title = "AI Chat", onClick = onNavigateToChatsHub)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (uiState.generalSettings.showKnowledgeCard) {
                HubCard(icon = "\uD83D\uDCDA", title = "AI Knowledge", onClick = onNavigateToKnowledge)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (hasAnyAgent) {
                HubCard(icon = "\uD83E\uDDE0", title = "AI Models", onClick = onNavigateToModelSearch)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (hasStatisticsData) {
                HubCard(icon = "\uD83D\uDCC8", title = "AI Usage", onClick = onNavigateToUsage)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (tracingEnabled && hasTraces) {
                HubCard(icon = "\uD83D\uDC1E", title = "AI API Traces", onClick = onNavigateToTraces)
                Spacer(modifier = Modifier.height(12.dp))
            }
            HubCard(icon = "\uD83E\uDD16", title = "AI Setup", onClick = onNavigateToAiSetup)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDDF9", title = "AI Housekeeping", onClick = onNavigateToHousekeeping)
            Spacer(modifier = Modifier.height(32.dp))
            HubCard(icon = "\u2699\uFE0F", title = "Settings", onClick = onNavigateToSettings)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\u2753", title = "Help", onClick = onNavigateToHelp)
        }
    }
}

@Composable
private fun HubCard(icon: String, title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 26.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
fun ReportsHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    onNavigateToExamplePrompts: () -> Unit = {},
    hasExamplePrompts: Boolean = false,
    onNavigateToHistory: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToLocalSemanticSearch: () -> Unit = {},
    onNavigateToLocalSearch: () -> Unit,
    onNavigateToQuickLocalSearch: () -> Unit,
    onOpenReport: (String) -> Unit = {},
) {
    val context = LocalContext.current
    // Re-fetch on every ON_RESUME — without this, navigating into a
    // detail screen and popping back left a stale cached list (the
    // composable is preserved across the trip and remember{} would
    // never re-evaluate). Keys all the disk reads through one tick.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val hasPromptHistory = remember(refreshTick) {
        SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir).loadPromptHistory().isNotEmpty()
    }
    val allReports = remember(refreshTick) { ReportStorage.getAllReports(context) }
    val hasPreviousReports = allReports.isNotEmpty()
    val pinnedReports = remember(allReports) { allReports.filter { it.pinned }.take(3) }
    val recentReports = remember(allReports) { allReports.filter { !it.pinned }.take(3) }
    // A report is "in flight" when it hasn't been marked completed AND
    // at least one of its agents is still PENDING / RUNNING. Reports
    // that were cancelled or errored on every agent have completedAt
    // null too but no live agent — those don't count.
    val inFlightReports = remember(allReports) {
        allReports.filter { r ->
            r.completedAt == null && r.agents.any { a ->
                a.reportStatus == com.ai.data.ReportStatus.PENDING ||
                    a.reportStatus == com.ai.data.ReportStatus.RUNNING
            }
        }
    }
    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)) {
        TitleBar(helpTopic = "reports_hub", title = "AI Reports", onBackClick = onNavigateBack)
        if (inFlightReports.isNotEmpty()) {
            InFlightPill(
                count = inFlightReports.size,
                onResume = { onOpenReport(inFlightReports.first().id) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        StartHubGroup(
            hasPromptHistory = hasPromptHistory,
            hasExamplePrompts = hasExamplePrompts,
            onNew = onNavigateToNewReport,
            onPreviousPrompt = onNavigateToPromptHistory,
            onExamplePrompt = onNavigateToExamplePrompts
        )
        if (hasPreviousReports) {
            Spacer(modifier = Modifier.height(12.dp))
            ExistingReportsCard(
                recent = recentReports,
                pinned = pinnedReports,
                onHeaderClick = onNavigateToHistory,
                onOpenReport = onOpenReport
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SearchHubGroup(
            enabled = hasPreviousReports,
            onQuickLocal = onNavigateToQuickLocalSearch,
            onExtendedLocal = onNavigateToLocalSearch,
            onRemoteSemantic = onNavigateToSearch,
            onLocalSemantic = onNavigateToLocalSemanticSearch
        )
    }
}

/** Surfaces a small pill at the top of the hub when at least one
 *  report is mid-flight (not completed AND has at least one agent
 *  still PENDING / RUNNING). Tap resumes the most recent in-flight
 *  report. Lets the user jump back to a backgrounded run without
 *  having to detour through History. */
@Composable
private fun InFlightPill(count: Int, onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onResume() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F3340))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.ai.ui.shared.AnimatedHourglass(fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            val label = if (count == 1) "1 report running" else "$count reports running"
            Text(label, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("Resume", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold)
        }
    }
}

/** Combined card under the AI Reports hub: up to 3 most-recent
 *  unpinned reports (🕘) followed by up to 3 most-recent pinned
 *  reports (📌). Trailing 'All AI reports' row routes to the full
 *  History screen. Visual style mirrors StartHubGroup / SearchHubGroup
 *  so the three list cards on the hub look uniform. */
@Composable
private fun ExistingReportsCard(
    recent: List<com.ai.data.Report>,
    pinned: List<com.ai.data.Report>,
    onHeaderClick: () -> Unit,
    onOpenReport: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Existing reports", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp))
            val iconGenEnabled = com.ai.ui.shared.LocalIconGenEnabled.current
            recent.forEach { r ->
                SearchHubItem(icon = (if (iconGenEnabled) r.icon else null) ?: "🕘",
                    title = r.title.ifBlank { "(untitled)" },
                    enabled = true, onClick = { onOpenReport(r.id) })
            }
            pinned.forEach { r ->
                SearchHubItem(icon = (if (iconGenEnabled) r.icon else null) ?: "📌",
                    title = r.title.ifBlank { "(untitled)" },
                    enabled = true, onClick = { onOpenReport(r.id) })
            }
            // Visual break between the per-report rows above and the
            // catch-all "All AI reports" link below — without it the
            // 📚 line reads as just another pinned/recent entry.
            if (recent.isNotEmpty() || pinned.isNotEmpty()) {
                HorizontalDivider(
                    color = AppColors.TextDisabled,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            SearchHubItem(icon = "📚", title = "All AI reports", enabled = true, onClick = onHeaderClick)
        }
    }
}

/** Groups the two creation entry points ("New AI Report" and "Start
 *  with a previous prompt") into one Start card so the hub doesn't
 *  show two loose top-level rows for what is conceptually the same
 *  step. Mirrors the Search card pattern. */
@Composable
private fun StartHubGroup(
    hasPromptHistory: Boolean,
    hasExamplePrompts: Boolean,
    onNew: () -> Unit,
    onPreviousPrompt: () -> Unit,
    onExamplePrompt: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Start", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp))
            SearchHubItem(icon = "\uD83D\uDCDD", title = "New AI Report", enabled = true, onClick = onNew)
            SearchHubItem(icon = "\uD83D\uDD04", title = "Start with a previous prompt", enabled = hasPromptHistory, onClick = onPreviousPrompt)
            SearchHubItem(icon = "\uD83D\uDCA1", title = "Start with an example prompt", enabled = hasExamplePrompts, onClick = onExamplePrompt)
        }
    }
}

/** Groups the four search modes into one card titled "Search". Order
 *  matches escalating cost: Quick (single-word on-device) → Extended
 *  (tokenised on-device) → Remote semantic (uploads text to an
 *  embedding provider) → Local semantic (on-device LiteRT model;
 *  nothing leaves the device). */
@Composable
private fun SearchHubGroup(
    enabled: Boolean,
    onQuickLocal: () -> Unit,
    onExtendedLocal: () -> Unit,
    onRemoteSemantic: () -> Unit,
    onLocalSemantic: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Search", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) AppColors.TextSecondary else AppColors.TextDim,
                modifier = Modifier.padding(bottom = 4.dp))
            SearchHubItem(icon = "🔍", title = "Quick local search", enabled = enabled, onClick = onQuickLocal)
            SearchHubItem(icon = "📂", title = "Extended local search", enabled = enabled, onClick = onExtendedLocal)
            SearchHubItem(icon = "🌐", title = "Remote semantic search", enabled = enabled, onClick = onRemoteSemantic)
            SearchHubItem(icon = "📱", title = "Local semantic search", enabled = enabled, onClick = onLocalSemantic)
        }
    }
}

@Composable
private fun SearchHubItem(icon: String, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 22.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else AppColors.TextDim,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private val userTagRegex = Regex("""<user>.*?</user>""", RegexOption.DOT_MATCHES_ALL)

@Composable
fun NewReportScreen(
    viewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToReports: () -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit = {},
    initialTitle: String = "",
    initialPrompt: String = ""
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    var title by remember {
        mutableStateOf(initialTitle.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: "" })
    }
    val rawPrompt = remember { initialPrompt.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" } }
    val userTagBlock = remember { userTagRegex.find(rawPrompt)?.value ?: "" }
    var prompt by remember { mutableStateOf(rawPrompt.replace(userTagRegex, "").trim()) }
    // (mime, base64) of an optional image attached to the prompt — passed
    // through to every agent in the report. Seeded from UiState when the
    // share-target chooser staged an image into reportImageBase64/Mime
    // before navigating here; we clear those fields on first composition
    // so a later return to this screen doesn't re-stage the same image.
    var attachedImage by remember {
        mutableStateOf<Pair<String, String>?>(
            uiState.reportImageMime?.let { mime ->
                uiState.reportImageBase64?.let { b64 -> mime to b64 }
            }
        )
    }
    LaunchedEffect(Unit) {
        if (uiState.reportImageBase64 != null || uiState.reportImageMime != null) {
            viewModel.updateUiState { it.copy(reportImageBase64 = null, reportImageMime = null) }
        }
    }

    // Snapshot of any non-image URIs the share-target chooser routed
    // here as "files attach as Knowledge". The banner below offers a
    // one-tap auto-attach: create a fresh KB, ingest the URIs, append
    // the KB id to attachedKnowledgeBaseIds so the report run uses
    // RAG against them. Drained on attach / skip / discard so a later
    // return to this screen doesn't re-stage the same files.
    var sharedKbState by remember { mutableStateOf<SharedKbBannerState>(SharedKbBannerState.Idle) }
    val sharedKbUris = remember { uiState.pendingReportKnowledgeUris }
    LaunchedEffect(Unit) {
        if (uiState.pendingReportKnowledgeUris.isNotEmpty()) {
            viewModel.updateUiState { it.copy(pendingReportKnowledgeUris = emptyList()) }
        }
    }
    var attachError by remember { mutableStateOf<String?>(null) }
    var useWebSearch by remember { mutableStateOf(false) }
    // Per-report reasoning level. "" = none; one of low/medium/high
    // gets OR'd onto every agent's params at dispatch (non-thinking
    // models drop the field).
    var reasoningEffort by remember { mutableStateOf("") }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }
    // Optional moderation pre-check — when set, the prompt runs through
    // the chosen moderation model before any agent fires. Mirrors the
    // chat session screen.
    var moderationModel by remember { mutableStateOf<Pair<com.ai.data.AppService, String>?>(null) }
    var showModerationPicker by remember { mutableStateOf(false) }
    var pendingFlagged by remember { mutableStateOf<Triple<String, com.ai.data.ModerationInputResult, String?>?>(null) }
    var moderationError by remember { mutableStateOf<String?>(null) }
    var isModerating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val pair = withContext(Dispatchers.IO) {
                    runCatching { com.ai.data.loadImageAsBase64(context, uri) }.getOrNull()
                }
                if (pair != null) {
                    attachedImage = pair
                    attachError = null
                } else {
                    attachError = "Failed to attach image"
                }
            }
        }
    }
    // 📎 paperclip is a two-step entry: open a small chooser so the
    // user picks "Take photo" or "Use existing photo", then fire the
    // matching launcher. Replaces the previous Reports-hub "Start with
    // photo" row plus the gallery-only paperclip with a single
    // attach surface.
    val launchCameraForAttach = com.ai.ui.shared.rememberCameraCaptureLauncher(
        onCaptured = { mime, b64 ->
            attachedImage = mime to b64
            attachError = null
        },
        onError = { attachError = it }
    )
    var showAttachChooser by remember { mutableStateOf(false) }
    if (showAttachChooser) {
        AlertDialog(
            onDismissRequest = { showAttachChooser = false },
            title = { Text("Attach image") },
            text = { Text("Take a new photo or pick one from your gallery?") },
            confirmButton = {
                TextButton(onClick = {
                    showAttachChooser = false
                    launchCameraForAttach()
                }) { Text("Take photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAttachChooser = false
                    pickImageLauncher.launch("image/*")
                }) { Text("Use existing photo") }
            }
        )
    }

    LaunchedEffect(uiState.showGenericAgentSelection) {
        if (uiState.showGenericAgentSelection) {
            reportViewModel.dismissGenericAgentSelection()
            onNavigateToReports()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_new", title = "New AI Report", onBackClick = onNavigateBack)

        if (sharedKbUris.isNotEmpty() && sharedKbState !is SharedKbBannerState.Skipped) {
            SharedKbBanner(
                uris = sharedKbUris,
                title = title,
                aiSettings = uiState.aiSettings,
                state = sharedKbState,
                onAttach = {
                    sharedKbState = SharedKbBannerState.Working("Preparing…")
                    coroutineScope.launch {
                        sharedKbState = ingestSharedKb(
                            context = context,
                            repository = viewModel.repository,
                            aiSettings = uiState.aiSettings,
                            reportTitle = title,
                            uris = sharedKbUris
                        ) { msg -> sharedKbState = SharedKbBannerState.Working(msg) }
                        val s = sharedKbState
                        if (s is SharedKbBannerState.Done) {
                            viewModel.updateUiState { st ->
                                st.copy(attachedKnowledgeBaseIds = (st.attachedKnowledgeBaseIds + s.kbId).distinct())
                            }
                        }
                    }
                },
                onSkip = { sharedKbState = SharedKbBannerState.Skipped }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Primary CTA hoisted into its own full-width row so the
        // "advance" affordance is always reachable without picking
        // it out of the Clear / 📎 row.
        Button(
            onClick = next@{
                    if (title.isBlank() || prompt.isBlank() || isModerating) return@next
                    val fullPrompt = if (userTagBlock.isNotEmpty()) "$prompt\n$userTagBlock" else prompt
                    prefs.edit().putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
                        .putString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, fullPrompt).apply()
                    SettingsPreferences(prefs, context.filesDir).savePromptToHistory(title, fullPrompt)

                    fun proceed() {
                        reportViewModel.showGenericAgentSelection(
                            title, fullPrompt,
                            imageBase64 = attachedImage?.second,
                            imageMime = attachedImage?.first,
                            webSearchTool = useWebSearch,
                            reasoningEffort = reasoningEffort.ifBlank { null }
                        )
                    }

                    val mod = moderationModel
                    if (mod == null) { proceed(); return@next }
                    coroutineScope.launch {
                        isModerating = true
                        try {
                            com.ai.data.withTraceCategory("Hub validate input") {
                                val (modProvider, modModelId) = mod
                                val apiKey = uiState.aiSettings.getApiKey(modProvider)
                                val callStart = System.currentTimeMillis()
                                val (results, apiResult) = com.ai.data.callModerationApi(modProvider, apiKey, modModelId, listOf(fullPrompt))
                                val r = results?.firstOrNull()
                                if (apiResult.errorMessage != null || r == null) {
                                    moderationError = apiResult.errorMessage ?: "No moderation result"
                                    proceed()
                                } else if (r.flagged) {
                                    val traceFn = withContext(Dispatchers.IO) {
                                        ApiTracer.getTraceFiles()
                                            .filter { it.reportId == null && it.model == modModelId && it.timestamp >= callStart }
                                            .minByOrNull { it.timestamp }?.filename
                                    }
                                    pendingFlagged = Triple(fullPrompt, r, traceFn)
                                } else {
                                    proceed()
                                }
                            }
                        } finally {
                            isModerating = false
                        }
                    }
                },
            enabled = title.isNotBlank() && prompt.isNotBlank() && !isModerating,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) {
            if (isModerating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Next", fontSize = 16.sp, maxLines = 1, softWrap = false)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Secondary actions — Clear (wipes the form) and 📎 (attach
        // image). Kept in their own row below the primary CTA so the
        // green Next button stays visually distinct.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { title = ""; prompt = ""; attachedImage = null },
                modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Clear", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { showAttachChooser = true }, colors = AppColors.outlinedButtonColors()) {
                Text("📎", fontSize = 16.sp, maxLines = 1, softWrap = false)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = useWebSearch,
                onClick = { useWebSearch = !useWebSearch },
                label = { Text("🌐 Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Blue.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Blue
                )
            )
            // 🧠 Thinking pulldown — same low/medium/high set as chat.
            // Always shown on the report screen since the report fans out
            // to many models with mixed reasoning support; the dispatch
            // layer's per-format helper drops the field on non-thinking
            // models so showing the chip universally is harmless.
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
            // 🛡 Moderation chip — tap when off opens the model picker;
            // tap when on clears the selection. With a model set, the
            // prompt is validated before generation kicks off.
            FilterChip(
                selected = moderationModel != null,
                onClick = {
                    if (moderationModel == null) showModerationPicker = true
                    else moderationModel = null
                },
                label = {
                    val label = moderationModel?.let { (_, m) -> "🛡 $m" } ?: "🛡 Validate prompt"
                    Text(label, fontSize = 12.sp, maxLines = 1)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Orange.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Orange
                )
            )
        }
        if (moderationError != null) {
            Text("Moderation: ${moderationError}", fontSize = 11.sp, color = AppColors.Orange,
                modifier = Modifier.padding(top = 4.dp))
        }

        attachError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = AppColors.Red, fontSize = 12.sp)
        }

        attachedImage?.let { (mime, b64) ->
            val bmp = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Image attached (${mime.substringAfter('/')})", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                TextButton(onClick = { attachedImage = null }) { Text("Remove", color = AppColors.Red, fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Title") },
            placeholder = { Text("Enter a title for the report") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it }, label = { Text("AI Prompt") },
            placeholder = { Text("Enter your prompt for the AI...") },
            modifier = Modifier.fillMaxWidth().weight(1f), minLines = 10, colors = AppColors.outlinedFieldColors()
        )
    }

    // Moderation model picker — overlay. Single-select; tap → set the
    // session's moderation model and close.
    if (showModerationPicker) {
        com.ai.ui.report.ReportSelectModelsScreen(
            aiSettings = uiState.aiSettings,
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

    // Flagged-prompt dialog — same Proceed-anyway / Cancel choices the
    // chat screen offers, with a 🐞 trace icon when the call left a
    // recording. Proceed continues to model selection; Cancel drops the
    // pending state and the user stays on the entry screen.
    val flagged = pendingFlagged
    if (flagged != null) {
        val (input, result, traceFn) = flagged
        AlertDialog(
            onDismissRequest = { pendingFlagged = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prompt flagged by moderation", modifier = Modifier.weight(1f))
                    if (ApiTracer.isTracingEnabled && traceFn != null) {
                        Text("🐞", fontSize = 18.sp,
                            modifier = Modifier
                                .clickable { onNavigateToTraceFile(traceFn) }
                                .padding(start = 8.dp, end = 4.dp))
                    }
                }
            },
            text = {
                Column {
                    Text(
                        "The chosen moderation model flagged this prompt under: " +
                            result.firedCategories.joinToString(", "),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sending it to the report's models anyway may produce unsafe output or violate provider policies.",
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingFlagged = null
                    reportViewModel.showGenericAgentSelection(
                        title, input,
                        imageBase64 = attachedImage?.second,
                        imageMime = attachedImage?.first,
                        webSearchTool = useWebSearch,
                        reasoningEffort = reasoningEffort.ifBlank { null }
                    )
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

/** State machine for the share-target "files queued as Knowledge"
 *  banner on [NewReportScreen]. Idle = banner shown with the
 *  Attach / Skip buttons; Working = ingestion in flight, status
 *  message live; Done = KB created and attached, success summary
 *  shown; Failed = error message + retry; Skipped = dismissed,
 *  banner gone. */
private sealed class SharedKbBannerState {
    object Idle : SharedKbBannerState()
    data class Working(val message: String) : SharedKbBannerState()
    data class Done(val kbId: String, val kbName: String, val sources: Int, val chunks: Int) : SharedKbBannerState()
    data class Failed(val message: String) : SharedKbBannerState()
    object Skipped : SharedKbBannerState()
}

@Composable
private fun SharedKbBanner(
    uris: List<String>,
    title: String,
    aiSettings: Settings,
    state: SharedKbBannerState,
    onAttach: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    // Resolve the embedder we'd use so the banner can show it (and
    // surface "no embedder available" up front instead of failing
    // mid-ingest). Local default wins when installed; otherwise the
    // first remote (provider, model) marked EMBEDDING.
    val embedderLabel = remember(aiSettings, context) {
        when {
            LocalEmbedder.isDefaultModelInstalled(context) ->
                "Local · ${LocalEmbedder.DEFAULT_MODEL_DISPLAY_NAME}"
            else -> supportedEmbeddingChoices(aiSettings).firstOrNull()
                ?.let { (svc, model) -> "${svc.id} · $model" }
        }
    }
    val canAttach = embedderLabel != null && state is SharedKbBannerState.Idle
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val nFiles = uris.size
            Text(
                text = if (nFiles == 1) "1 file shared — attach as a knowledge base?"
                    else "$nFiles files shared — attach as a knowledge base?",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Green
            )
            embedderLabel?.let {
                Text("Embedder: $it", fontSize = 11.sp, color = AppColors.TextTertiary)
            } ?: Text(
                "No embedder available — install a local .tflite under Housekeeping → Local Models, or activate a provider with an embedding model.",
                fontSize = 11.sp, color = AppColors.Red
            )
            when (state) {
                is SharedKbBannerState.Working -> Text(state.message, fontSize = 12.sp, color = AppColors.TextSecondary)
                is SharedKbBannerState.Done -> Text(
                    "Indexed ${state.sources} source(s), ${state.chunks} chunk(s). Attached as 📚.",
                    fontSize = 12.sp, color = AppColors.Green
                )
                is SharedKbBannerState.Failed -> Text("Failed: ${state.message}", fontSize = 12.sp, color = AppColors.Red)
                else -> { /* Idle — nothing extra */ }
            }
            if (state !is SharedKbBannerState.Done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAttach,
                        enabled = canAttach,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                    ) { Text("Attach as KB", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(
                        onClick = onSkip,
                        enabled = state !is SharedKbBannerState.Working,
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Skip", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** Create a fresh KB from [uris], pick an embedder, ingest each
 *  source via [KnowledgeService]. Returns [SharedKbBannerState.Done]
 *  on success ([Failed] otherwise). Run on [Dispatchers.IO] —
 *  callers should not block the main thread. */
private suspend fun ingestSharedKb(
    context: android.content.Context,
    repository: AnalysisRepository,
    aiSettings: Settings,
    reportTitle: String,
    uris: List<String>,
    onProgress: (String) -> Unit
): SharedKbBannerState = withContext(Dispatchers.IO) {
    val (embedderProviderId, embedderModel) = when {
        LocalEmbedder.isDefaultModelInstalled(context) ->
            "LOCAL" to LocalEmbedder.DEFAULT_MODEL_NAME
        else -> {
            val choice = supportedEmbeddingChoices(aiSettings).firstOrNull()
                ?: return@withContext SharedKbBannerState.Failed(
                    "No embedder available."
                )
            choice.first.id to choice.second
        }
    }
    val kbName = "Shared with ${reportTitle.ifBlank { "report" }} — ${
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    }"
    val kb = runCatching {
        KnowledgeStore.createKnowledgeBase(context, kbName, embedderProviderId, embedderModel)
    }.getOrElse { return@withContext SharedKbBannerState.Failed(it.message ?: "Could not create KB") }
    var totalChunks = 0
    var sourcesIndexed = 0
    uris.forEachIndexed { idx, raw ->
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return@forEachIndexed
        val isHttp = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
        onProgress("Ingesting ${idx + 1}/${uris.size}…")
        val result = if (isHttp) {
            KnowledgeService.indexUrl(context, repository, aiSettings, kb.id, trimmed) { msg, _, _ ->
                onProgress("(${idx + 1}/${uris.size}) $msg")
            }
        } else {
            val u = android.net.Uri.parse(trimmed)
            val type = pickTypeForUri(context, u)
            val displayName = displayNameForUri(context, u) ?: "shared_${System.currentTimeMillis()}"
            KnowledgeService.indexFile(context, repository, aiSettings, kb.id, type, u, displayName) { msg, _, _ ->
                onProgress("(${idx + 1}/${uris.size}) $displayName: $msg")
            }
        }
        result.onSuccess { src ->
            totalChunks += src.chunkCount
            sourcesIndexed++
        }
    }
    if (sourcesIndexed == 0) {
        // Drop the empty KB so it doesn't leak into the user's list.
        KnowledgeStore.deleteKnowledgeBase(context, kb.id)
        SharedKbBannerState.Failed("Nothing indexed.")
    } else {
        SharedKbBannerState.Done(kb.id, kbName, sourcesIndexed, totalChunks)
    }
}
