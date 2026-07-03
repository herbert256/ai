package com.ai.ui.report.start

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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.data.AnalysisRepository
import com.ai.data.ApiTracer
import com.ai.data.KnowledgeService
import com.ai.data.KnowledgeStore
import com.ai.data.ModelCooldownStore
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.local.LocalEmbedder
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.ai.ui.knowledge.displayNameForUri
import com.ai.ui.knowledge.pickTypeForUri
import com.ai.ui.search.supportedEmbeddingChoices
import com.ai.data.preferences.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.TranslationRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val userTagRegex = Regex("""<user>.*?</user>""", RegexOption.DOT_MATCHES_ALL)

/** Saver letting the flagged-prompt dialog survive the 🐞 trace
 *  round-trip (same rationale as ChatScreens' FlaggedStateSaver — a
 *  forward nav disposes this composition, and plain remember dropped
 *  the Proceed/Cancel choice right after the user viewed the trace).
 *  Encodes the fired categories; scores aren't shown in the dialog. */
private val FlaggedTripleSaver = Saver<Triple<String, com.ai.data.ModerationInputResult, String?>?, java.util.ArrayList<Any?>>(
    save = { state ->
        if (state == null) java.util.ArrayList()
        else java.util.ArrayList<Any?>().apply {
            add(state.first)
            add(java.util.ArrayList(state.second.firedCategories))
            add(state.third)
        }
    },
    restore = { list ->
        if (list.isEmpty()) null
        else {
            @Suppress("UNCHECKED_CAST")
            val fired = (list[1] as java.util.ArrayList<String>).toList()
            Triple(
                list[0] as String,
                com.ai.data.ModerationInputResult(
                    flagged = true,
                    categories = fired.associateWith { true },
                    scores = emptyMap()
                ),
                list[2] as? String
            )
        }
    }
)

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

    // rememberSaveable throughout: tapping ❓ Help or the flagged dialog's
    // 🐞 trace link is a FORWARD navigation that disposes this composition
    // (the same hop manage/Savers.kt documents). With plain remember the
    // typed prompt/title reverted to the prefs snapshot of the PREVIOUS
    // report and the moderation pick + flagged dialog vanished on return.
    var title by rememberSaveable {
        mutableStateOf(initialTitle.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: "" })
    }
    val rawPrompt = remember { initialPrompt.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" } }
    var userTagBlock by rememberSaveable { mutableStateOf(userTagRegex.find(rawPrompt)?.value ?: "") }
    var prompt by rememberSaveable { mutableStateOf(rawPrompt.replace(userTagRegex, "").trim()) }
    // Draft autosave — the fields used to persist only inside the Next
    // handler, so backing out (or process death) lost a long draft and a
    // return showed the PREVIOUS submitted prompt. Debounced writes to the
    // same prefs keys the seed above reads make the draft round-trip.
    LaunchedEffect(title, prompt, userTagBlock) {
        kotlinx.coroutines.delay(800)
        prefs.edit()
            .putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
            .putString(
                SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT,
                (prompt + if (userTagBlock.isNotBlank()) "\n$userTagBlock" else "").trim()
            )
            .apply()
    }
    // (mime, base64) of an optional image attached to the prompt — passed
    // through to every agent in the report. Seeded from UiState when the
    // share-target chooser staged an image into reportImageBase64/Mime.
    // The staging fields are kept in SYNC with this local state (instead
    // of drained on first composition) so a Help / trace-viewer hop
    // re-seeds the image on return — base64 payloads are too large for
    // rememberSaveable's Binder bundle. The staging drains on the real
    // exits (Back, Next) below, so a later fresh visit doesn't re-stage.
    var attachedImage by remember {
        mutableStateOf<Pair<String, String>?>(
            uiState.reportImageMime?.let { mime ->
                uiState.reportImageBase64?.let { b64 -> mime to b64 }
            }
        )
    }
    LaunchedEffect(attachedImage) {
        viewModel.updateUiState { it.copy(
            reportImageBase64 = attachedImage?.second,
            reportImageMime = attachedImage?.first
        ) }
    }

    // Non-image URIs the share-target chooser routed here as "files
    // attach as Knowledge". The banner below offers a one-tap
    // auto-attach: create a fresh KB, ingest the URIs, append the KB id
    // to attachedKnowledgeBaseIds so the report run uses RAG against
    // them. Read LIVE from UiState (not snapshotted + drained on first
    // composition) so the banner survives a Help hop; the staging
    // drains on attach / skip and on the real exits below.
    var sharedKbState by remember { mutableStateOf<SharedKbBannerState>(SharedKbBannerState.Idle) }
    val sharedKbUris = uiState.pendingReportKnowledgeUris
    var attachError by remember { mutableStateOf<String?>(null) }
    var useWebSearch by remember { mutableStateOf(false) }
    // Per-report metadata kill-switch — skip title/icon/language/per-model
    // calls for THIS report without flipping the global setting.
    var skipMetadata by remember { mutableStateOf(false) }
    // Per-report reasoning level. "" = none; one of low/medium/high
    // gets OR'd onto every agent's params at dispatch (non-thinking
    // models drop the field).
    var reasoningEffort by remember { mutableStateOf("") }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }
    // Report-level Parameters / System prompt now live as cards on the
    // "Report - setup" screen (step 3), not as icons here.
    // Optional moderation pre-check — when set, the prompt runs through
    // the chosen moderation model before any agent fires. Mirrors the
    // chat session screen, including its savers (AppService itself
    // isn't bundle-storable — store the provider id + model name and
    // re-resolve on restore).
    val moderationSaver = remember {
        Saver<Pair<com.ai.data.AppService, String>?, ArrayList<String>>(
            save = { p -> if (p == null) arrayListOf() else arrayListOf(p.first.id, p.second) },
            restore = { l -> if (l.size < 2) null else com.ai.data.AppService.findById(l[0])?.let { it to l[1] } }
        )
    }
    var moderationModel by rememberSaveable(stateSaver = moderationSaver) { mutableStateOf<Pair<com.ai.data.AppService, String>?>(null) }
    var showModerationPicker by rememberSaveable { mutableStateOf(false) }
    var pendingFlagged by rememberSaveable(stateSaver = FlaggedTripleSaver) {
        mutableStateOf<Triple<String, com.ai.data.ModerationInputResult, String?>?>(null)
    }
    var moderationError by rememberSaveable { mutableStateOf<String?>(null) }
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

    // Real exits drain the share-target staging (image + KB uris) so a
    // LATER fresh visit doesn't re-stage them. The Help / trace hop is a
    // forward nav and deliberately keeps the staging (see attachedImage
    // above), which is why the drain lives here and not on composition.
    val drainStagingAndBack: () -> Unit = {
        viewModel.updateUiState { it.copy(
            reportImageBase64 = null, reportImageMime = null,
            pendingReportKnowledgeUris = emptyList()
        ) }
        onNavigateBack()
    }
    BackHandler { drainStagingAndBack() }

    // Moderation model picker — full-screen overlay. Composed BEFORE the host
    // Column (with the early return) so the base content doesn't stay in
    // composition beneath it, where taps in the overlay's dead zones could
    // fall through to the underlying prompt field / Next button.
    if (showModerationPicker) {
        com.ai.ui.other.ReportSelectModelsScreen(
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

    // 🧽 confirm — one stray tap used to destroy a multi-paragraph prompt
    // (plus any attached image) with no recovery. Trivial content (short
    // prompt, nothing attached) still clears instantly.
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this report draft?") },
            text = { Text("Wipes the title, the prompt${if (attachedImage != null) ", the attached image" else ""} and any tags. There is no undo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    title = ""; prompt = ""; userTagBlock = ""; attachedImage = null
                }) { Text("Clear", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_new", title = "New Report", subject = "Write your prompt, then pick models", onBackClick = drainStagingAndBack,
            onClear = {
                // Confirm only when there is something worth losing.
                if (prompt.length > 80 || attachedImage != null) confirmClear = true
                else { title = ""; prompt = ""; userTagBlock = ""; attachedImage = null }
            },
            onAttach = { showAttachChooser = true },
            onValidatePrompt = { if (moderationModel == null) showModerationPicker = true else moderationModel = null },
            validatePromptActive = moderationModel != null)

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
                                st.copy(
                                    attachedKnowledgeBaseIds = (st.attachedKnowledgeBaseIds + s.kbId).distinct(),
                                    // Consumed — a Help hop after attach must
                                    // not resurrect the banner for a re-ingest.
                                    pendingReportKnowledgeUris = emptyList()
                                )
                            }
                        }
                    }
                },
                onSkip = {
                    sharedKbState = SharedKbBannerState.Skipped
                    // sharedKbState is composition-local; drain the staging so
                    // the banner stays skipped across a Help hop too.
                    viewModel.updateUiState { it.copy(pendingReportKnowledgeUris = emptyList()) }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (userTagBlock.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Shared user instructions attached",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { userTagBlock = "" }) {
                        Text("Remove", color = AppColors.DangerAccent, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Primary CTA hoisted into its own full-width row so the
        // "advance" affordance is always reachable without picking
        // it out of the Clear / 📎 row.
        OutlinedButton(
            onClick = next@{
                    val titleRequired = !uiState.generalSettings.reportTitleAiOn()
                    if ((titleRequired && title.isBlank()) || prompt.isBlank() || isModerating) return@next
                    val visiblePrompt = prompt.trim()
                    val fullPrompt = if (userTagBlock.isNotBlank()) "$visiblePrompt\n$userTagBlock" else visiblePrompt
                    prefs.edit().putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
                        .putString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, visiblePrompt).apply()
                    SettingsPreferences(prefs, context.filesDir).savePromptToHistory(title, visiblePrompt)

                    fun proceed() {
                        reportViewModel.showGenericAgentSelection(
                            title, fullPrompt,
                            imageBase64 = attachedImage?.second,
                            imageMime = attachedImage?.first,
                            webSearchTool = useWebSearch,
                            reasoningEffort = reasoningEffort.ifBlank { null },
                            metadataDisabled = skipMetadata
                        )
                        // Staging consumed by the selection flow — drain the
                        // shared-KB staging so a later fresh visit doesn't
                        // re-offer the same files. The image fields must NOT
                        // be drained here: showGenericAgentSelection just
                        // wrote them into UiState and generateGenericReports
                        // reads (then drains) them at dispatch — nulling them
                        // here sent every image-attached report out
                        // text-only, at full cost.
                        viewModel.updateUiState { it.copy(
                            pendingReportKnowledgeUris = emptyList()
                        ) }
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
            enabled = (uiState.generalSettings.reportTitleAiOn() || title.isNotBlank())
                && prompt.isNotBlank() && !isModerating,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) {
            if (isModerating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AppColors.TextPrimary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Next", fontSize = 16.sp, maxLines = 1, softWrap = false)
        }
        // Clear (🧽), attach (📎) and Validate prompt (🚩) now live on the
        // bottom-bar icons (wired on the TitleBar above). 🚩 is grayed until
        // a moderation model is picked.
        if (moderationError != null) {
            Text("Moderation: ${moderationError}", fontSize = 11.sp, color = AppColors.WarningAccent,
                modifier = Modifier.padding(top = 4.dp))
        }

        attachError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = AppColors.DangerAccent, fontSize = 12.sp)
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
                TextButton(onClick = { attachedImage = null }) { Text("Remove", color = AppColors.DangerAccent, fontSize = 12.sp) }
            }
        }

        // Report-level Web-search + Reasoning-effort chips — the same pair
        // chat offers per message, applied here to every model of the run.
        // The values ride into the report's captured config
        // (webSearchTool / reasoningEffort); at dispatch each agent drops
        // whatever its model doesn't support, so no per-model gating here
        // (the run fans out across many models).
        Spacer(modifier = Modifier.height(8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val mi = com.ai.ui.shared.LocalMetadataIcons.current
            FilterChip(
                selected = useWebSearch,
                onClick = { useWebSearch = !useWebSearch },
                label = { Text("${mi.web} Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.InfoAccent.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.InfoAccent
                )
            )
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
                    val options = listOf("" to "None", "low" to "Low", "medium" to "Medium", "high" to "High")
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
            FilterChip(
                selected = skipMetadata,
                onClick = { skipMetadata = !skipMetadata },
                label = { Text("${com.ai.ui.shared.LocalMetadataIcons.current.image} Skip metadata", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.CautionAccent.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.CautionAccent
                )
            )
        }

        // Title input hidden in AI title-mode — the title is filled
        // post-creation by [ReportViewModel.kickOffReportTitleGeneration]
        // via the bundled `internal/report_title` prompt and surfaced
        // on Manage report's new `title` row.
        if (!uiState.generalSettings.reportTitleAiOn()) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title") },
                placeholder = { Text("Enter a title for the report") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors()
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt") },
            placeholder = { Text("Enter your prompt...") },
            modifier = Modifier.fillMaxWidth().weight(1f), minLines = 10, colors = AppColors.outlinedFieldColors()
        )
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
                    if (ApiTracer.ladybugLinksEnabled && traceFn != null) {
                        Text(com.ai.data.MetadataIconsHolder.current.traces, fontSize = 18.sp,
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
                        reasoningEffort = reasoningEffort.ifBlank { null },
                            metadataDisabled = skipMetadata
                    )
                    // Same staging drain as proceed(): without it the
                    // shared-KB banner (and a never-generated image)
                    // resurrected on the next fresh New Report visit,
                    // offering files that belonged to this report.
                    viewModel.updateUiState { it.copy(
                        pendingReportKnowledgeUris = emptyList()
                    ) }
                }) { Text("Proceed anyway", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
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
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.SuccessAccent
            )
            embedderLabel?.let {
                Text("Embedder: $it", fontSize = 11.sp, color = AppColors.TextTertiary)
            } ?: Text(
                "No embedder available — install a local .tflite under Housekeeping → Local Models, or activate a provider with an embedding model.",
                fontSize = 11.sp, color = AppColors.DangerAccent
            )
            when (state) {
                is SharedKbBannerState.Working -> Text(state.message, fontSize = 12.sp, color = AppColors.TextSecondary)
                is SharedKbBannerState.Done -> Text(
                    "Indexed ${state.sources} source(s), ${state.chunks} chunk(s). Attached as ${com.ai.data.MetadataIconsHolder.current.library}.",
                    fontSize = 12.sp, color = AppColors.SuccessAccent
                )
                is SharedKbBannerState.Failed -> Text("Failed: ${state.message}", fontSize = 12.sp, color = AppColors.DangerAccent)
                else -> { /* Idle — nothing extra */ }
            }
            if (state !is SharedKbBannerState.Done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAttach,
                        enabled = canAttach,
                        modifier = Modifier.weight(1f),
                        colors = AppColors.outlinedButtonColors()
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
    suspend fun emitProgress(message: String) {
        withContext(Dispatchers.Main) { onProgress(message) }
    }
    // try/finally so a cancellation (the user taps Help or Back mid-ingest,
    // unmounting NewReportScreen and cancelling this screen-scoped
    // coroutine) still drops a KB that never finished a source — otherwise
    // the just-created 'Shared with …' KB with 0–n sources orphaned in the
    // Knowledge list and a return re-offered Attach, duplicating it.
    try {
        uris.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@forEachIndexed
            val isHttp = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
            emitProgress("Ingesting ${idx + 1}/${uris.size}…")
            val result = if (isHttp) {
                KnowledgeService.indexUrl(context, repository, aiSettings, kb.id, trimmed) { msg, _, _ ->
                    emitProgress("(${idx + 1}/${uris.size}) $msg")
                }
            } else {
                val u = android.net.Uri.parse(trimmed)
                val displayName = displayNameForUri(context, u) ?: "shared_${System.currentTimeMillis()}"
                val type = pickTypeForUri(context, u) ?: run {
                    emitProgress("Skipping unsupported source: $displayName")
                    return@forEachIndexed
                }
                KnowledgeService.indexFile(context, repository, aiSettings, kb.id, type, u, displayName) { msg, _, _ ->
                    emitProgress("(${idx + 1}/${uris.size}) $displayName: $msg")
                }
            }
            result.onSuccess { src ->
                totalChunks += src.chunkCount
                sourcesIndexed++
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // NonCancellable: the enclosing scope is already cancelling, so the
        // delete must run outside cancellation to actually reach disk.
        withContext(kotlinx.coroutines.NonCancellable) {
            KnowledgeStore.deleteKnowledgeBase(context, kb.id)
        }
        throw e
    }
    return@withContext if (sourcesIndexed == 0) {
        // Drop the empty KB so it doesn't leak into the user's list.
        KnowledgeStore.deleteKnowledgeBase(context, kb.id)
        SharedKbBannerState.Failed("Nothing indexed.")
    } else {
        SharedKbBannerState.Done(kb.id, kbName, sourcesIndexed, totalChunks)
    }
}
