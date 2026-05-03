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
import com.ai.data.ApiTracer
import com.ai.data.ReportStorage
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

    val cardHeight = 50.dp
    val cardSpacing = 12.dp
    val cardCount = 9
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
            )
            HubCard(icon = "\uD83D\uDCDD", title = "AI Reports", onClick = onNavigateToReportsHub, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDCAC", title = "AI Chat", onClick = onNavigateToChatsHub, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDDE0", title = "AI Models", onClick = onNavigateToModelSearch, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDCC8", title = "AI Usage", onClick = onNavigateToUsage, enabled = hasStatisticsData)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDC1E", title = "AI API Traces", onClick = onNavigateToTraces, enabled = hasTraces)
            Spacer(modifier = Modifier.height(12.dp))
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
private fun HubCard(icon: String, title: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 26.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else AppColors.TextDim)
        }
    }
}

@Composable
fun ReportsHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToLocalSearch: () -> Unit,
    onNavigateToQuickLocalSearch: () -> Unit
) {
    val context = LocalContext.current
    val hasPromptHistory = remember {
        SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir).loadPromptHistory().isNotEmpty()
    }
    val hasPreviousReports = remember { ReportStorage.getAllReports(context).isNotEmpty() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "AI Reports", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(24.dp))
        StartHubGroup(
            hasPromptHistory = hasPromptHistory,
            onNew = onNavigateToNewReport,
            onPreviousPrompt = onNavigateToPromptHistory
        )
        Spacer(modifier = Modifier.height(12.dp))
        HubCard(icon = "\uD83D\uDCDA", title = "View previous reports", onClick = onNavigateToHistory, enabled = hasPreviousReports)
        Spacer(modifier = Modifier.height(12.dp))
        SearchHubGroup(
            enabled = hasPreviousReports,
            onQuickLocal = onNavigateToQuickLocalSearch,
            onExtendedLocal = onNavigateToLocalSearch,
            onSemantic = onNavigateToSearch
        )
    }
}

/** Groups the two creation entry points ("New AI Report" and "Start
 *  with a previous prompt") into one Start card so the hub doesn't
 *  show two loose top-level rows for what is conceptually the same
 *  step. Mirrors the Search card pattern. */
@Composable
private fun StartHubGroup(
    hasPromptHistory: Boolean,
    onNew: () -> Unit,
    onPreviousPrompt: () -> Unit
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
        }
    }
}

/** Groups the three search modes into one card titled "Search" so the
 *  hub doesn't show three loose top-level rows for variants of the
 *  same operation. Order matches escalating cost: Quick (single-word
 *  on-device) → Extended (tokenised on-device) → Semantic (uploads
 *  text to an embedding provider). */
@Composable
private fun SearchHubGroup(
    enabled: Boolean,
    onQuickLocal: () -> Unit,
    onExtendedLocal: () -> Unit,
    onSemantic: () -> Unit
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
            SearchHubItem(icon = "🌐", title = "Semantic search", enabled = enabled, onClick = onSemantic)
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
            color = if (enabled) Color.White else AppColors.TextDim)
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
    // through to every agent in the report.
    var attachedImage by remember { mutableStateOf<Pair<String, String>?>(null) }
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
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    attachedImage = mime to Base64.encodeToString(bytes, Base64.NO_WRAP)
                    attachError = null
                }
            } catch (e: Exception) {
                attachError = "Failed to attach image: ${e.message}"
            }
        }
    }

    LaunchedEffect(uiState.showGenericAgentSelection) {
        if (uiState.showGenericAgentSelection) {
            reportViewModel.dismissGenericAgentSelection()
            onNavigateToReports()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "New AI Report", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { title = ""; prompt = ""; attachedImage = null }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Clear", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }, colors = AppColors.outlinedButtonColors()) {
                Text("📎", fontSize = 16.sp, maxLines = 1, softWrap = false)
            }
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
                        val previousCategory = ApiTracer.currentCategory
                        ApiTracer.currentCategory = "Hub validate input"
                        try {
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
                        } finally {
                            ApiTracer.currentCategory = previousCategory
                            isModerating = false
                        }
                    }
                },
                enabled = title.isNotBlank() && prompt.isNotBlank() && !isModerating,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) {
                if (isModerating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Next", fontSize = 16.sp, maxLines = 1, softWrap = false)
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
                    if (traceFn != null) {
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
