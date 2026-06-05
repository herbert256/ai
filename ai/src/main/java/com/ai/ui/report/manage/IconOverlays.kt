package com.ai.ui.report.manage
import com.ai.ui.report.start.ModelSelectionScreen
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resolve the most recent "Report icons tier N" trace file backing
 *  an agent's icon call. Tier 1 / 2 traces use the agent's own model;
 *  tier 3 falls back to a bundled icon agent so its trace's model is
 *  different — match on category-tier and only tighten by model on
 *  tiers 1/2. Null winningTier means the chain exhausted: surface the
 *  most recent "Report icons" trace regardless of tier so the user
 *  still has a path to the failure.
 *
 *  Extracted as a separate @Composable so the call site doesn't push
 *  the parent ReportsScreen over the JVM 64KB method-size limit. */
@Composable
internal fun rememberAgentIconTrace(
    reportId: String?,
    agentModel: String,
    iconKey: String?,
    winningTier: Int?,
    promptUsed: String?
): String? = rememberIconTrace(
    reportId = reportId,
    model = agentModel,
    // Per-agent icons come from model/icons; a Find-alternative
    // pick (iconPromptUsed "report_alt") comes from alt/report. Match
    // those trace categories so the 🐞 link resolves.
    categories = when (promptUsed) {
        "report_alt" -> listOf("alt/report")
        else -> listOf("model/icons", "alt/report")
    }
)

/** Generic icon-trace lookup — finds the most-recent trace whose
 *  `category` matches one of [categories], optionally scoped to a
 *  specific [reportId] and [model]. Pass null for either scope to
 *  mean "any". Used by every adapter that wants to surface a 🐞
 *  deep-link on the unified Icon-lookup screen. */
@Composable
internal fun rememberIconTrace(
    reportId: String?,
    model: String?,
    categories: List<String>
): String? {
    val state = androidx.compose.runtime.produceState<String?>(
        initialValue = null, reportId, model, categories
    ) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { tf ->
                    (reportId == null || tf.reportId == reportId) &&
                        (model.isNullOrBlank() || tf.model == model) &&
                        tf.category in categories
                }
                .maxByOrNull { it.timestamp }?.filename
        }
    }
    return state.value
}

/** Routing helper for the shared `FindIconsSelectionScreen` —
 *  picks the right onFindIcons dispatch based on which icon flow
 *  is active. Extracted out of `ReportsScreen` so the parent
 *  stays under the JVM 64 KB per-method bytecode limit. */
@Composable
internal fun FindIconsPickerRouter(
    reportId: String,
    targetLanguage: String?,
    targetPromptId: String?,
    targetAgentId: String?,
    targetPairId: String?,
    /** Non-null when finding an alternative TITLE for a fan-out pair
     *  (the L3 META "Find alternative title" button). Routes onAction
     *  to [onStartPairTitleFanOut] and makes this a TITLE flow. */
    targetPairTitleId: String?,
    targetLanguageIcon: Boolean,
    internalPrompts: List<com.ai.model.InternalPrompt>,
    aiSettings: Settings,
    models: List<ReportModel>,
    /** When non-empty the alt prompt has a worker configured: skip the
     *  model-selection screen entirely and run the fan-out directly on these
     *  models (resolved from the prompt's worker chain). */
    autoDispatchModels: List<ReportModel> = emptyList(),
    genericPromptText: String,
    /** Non-null when this run finds alternative TITLES not icons:
     *  "report" for the report title, else the agentId. */
    targetTitleFor: String?,
    onStartTitleFanOut: (String, List<ReportModel>, List<String>, String?) -> Unit,
    translationIconCallbacks: TranslationIconCallbacks,
    languageIconCallbacks: LanguageIconCallbacks,
    onStartInternalPromptIconFanOut: (com.ai.model.InternalPrompt, List<ReportModel>, List<String>, String?) -> Unit,
    onStartAgentIconFanOut: (String, String, List<ReportModel>) -> Unit,
    onStartPairIconFanOut: (String, String, List<ReportModel>) -> Unit,
    onStartPairTitleFanOut: (String, String, List<ReportModel>) -> Unit,
    onStartIconFanOut: (String, String, List<ReportModel>) -> Unit,
    onAddAgent: () -> Unit,
    onAddFlock: () -> Unit,
    onAddSwarm: () -> Unit,
    onAddFromReport: () -> Unit,
    onAddAllModels: () -> Unit,
    onRemoveModel: (Int) -> Unit,
    onClearAll: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val targetPrompt = targetPromptId?.let { id ->
        internalPrompts.firstOrNull { it.id == id }
    }
    val isTitleFlow = targetTitleFor != null || targetPairTitleId != null
    // Worker-configured alt prompt: skip the model-selection screen and run the
    // fan-out straight on the prompt's resolved worker models. Mirrors the
    // onAction / onActionWithParams routing below (params-flows get no run-time
    // 🌡️/🎭 override), then confirms into the candidates grid.
    if (autoDispatchModels.isNotEmpty()) {
        LaunchedEffect(autoDispatchModels) {
            when {
                targetPairTitleId != null -> onStartPairTitleFanOut(reportId, targetPairTitleId, autoDispatchModels)
                targetTitleFor != null -> onStartTitleFanOut(targetTitleFor, autoDispatchModels, emptyList(), null)
                targetPrompt != null -> onStartInternalPromptIconFanOut(targetPrompt, autoDispatchModels, emptyList(), null)
                targetLanguageIcon -> languageIconCallbacks.onStartFanOut(reportId, genericPromptText, autoDispatchModels)
                targetLanguage != null -> translationIconCallbacks.onStartFanOut(targetLanguage, autoDispatchModels)
                targetPairId != null -> onStartPairIconFanOut(reportId, targetPairId, autoDispatchModels)
                targetAgentId != null -> onStartAgentIconFanOut(reportId, targetAgentId, autoDispatchModels)
                else -> onStartIconFanOut(reportId, genericPromptText, autoDispatchModels)
            }
            onConfirm()
        }
        return
    }
    ModelSelectionScreen(
        models = models,
        aiSettings = aiSettings,
        title = if (isTitleFlow) "Find titles" else "Find icons",
        actionLabel = if (isTitleFlow) "Find Titles" else "Find Icons",
        onAddAgent = onAddAgent,
        onAddFlock = onAddFlock,
        onAddSwarm = onAddSwarm,
        onAddFromReport = onAddFromReport,
        onAddAllModels = onAddAllModels,
        onRemoveModel = onRemoveModel,
        onClearAll = onClearAll,
        onAction = {
            when {
                targetPairTitleId != null -> onStartPairTitleFanOut(reportId, targetPairTitleId, models)
                targetLanguageIcon -> languageIconCallbacks.onStartFanOut(reportId, genericPromptText, models)
                targetLanguage != null -> translationIconCallbacks.onStartFanOut(targetLanguage, models)
                targetPairId != null -> onStartPairIconFanOut(reportId, targetPairId, models)
                targetAgentId != null -> onStartAgentIconFanOut(reportId, targetAgentId, models)
                else -> onStartIconFanOut(reportId, genericPromptText, models)
            }
            onConfirm()
        },
        // Alt icons / Alt titles get the per-launch 🌡️ / 🎭 pick.
        onActionWithParams = if (targetTitleFor != null || targetPairTitleId != null || targetPrompt != null) {
            { pIds, spId ->
                when {
                    targetPairTitleId != null -> onStartPairTitleFanOut(reportId, targetPairTitleId, models)
                    targetTitleFor != null -> onStartTitleFanOut(targetTitleFor, models, pIds, spId)
                    targetPrompt != null -> onStartInternalPromptIconFanOut(targetPrompt, models, pIds, spId)
                }
                onConfirm()
            }
        } else null,
        onBack = onBack
    )
}

/** Routing helper for the shared `AlternativeIconsScreen` — picks
 *  the right candidate list + onPick / onRestart callbacks based
 *  on which icon flow is active (per-translation > per-prompt >
 *  per-agent > per-report). Extracted out of `ReportsScreen` so
 *  the parent stays under the JVM 64 KB per-method bytecode
 *  limit. */
@Composable
internal fun AlternativeIconsRouter(
    reportId: String,
    targetLanguage: String?,
    targetPromptId: String?,
    /** Non-null when the per-row Meta-tile flow opened the alt-pick
     *  overlay. Routes the pick to [onPickMetaRowIcon] (per-row icon
     *  override on disk) instead of [onPickInternalPromptIcon] (the
     *  shared per-(name,title) cache entry every tile of that name
     *  would otherwise inherit). */
    targetMetaRowId: String?,
    targetAgentId: String?,
    targetPairId: String?,
    targetLanguageIcon: Boolean,
    internalPrompts: List<com.ai.model.InternalPrompt>,
    internalPromptIconFanOutByPrompt: Map<String, List<IconCandidate>>,
    agentIconFanOutByAgent: Map<String, List<IconCandidate>>,
    pairIconFanOutByPair: Map<String, List<IconCandidate>>,
    iconFanOutByReport: Map<String, List<IconCandidate>>,
    translationIconCallbacks: TranslationIconCallbacks,
    languageIconCallbacks: LanguageIconCallbacks,
    onPickInternalPromptIcon: (com.ai.model.InternalPrompt, IconCandidate.Done) -> Unit,
    onPickMetaRowIcon: (reportId: String, rowId: String, emoji: String) -> Unit,
    onPickAgentIcon: (String, String, String) -> Unit,
    onPickPairIcon: (String, String, String) -> Unit,
    onPickAlternativeIcon: (String, String, String) -> Unit,
    onRestartInternalPromptIconFanOut: (com.ai.model.InternalPrompt) -> Unit,
    onRestartAgentIconFanOut: (String, String) -> Unit,
    onRestartPairIconFanOut: (String, String) -> Unit,
    onRestartIconFanOut: (String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onCloseAll: () -> Unit,
    onRestartReopenPicker: () -> Unit,
    onClose: () -> Unit
) {
    val targetPrompt = targetPromptId?.let { id ->
        internalPrompts.firstOrNull { it.id == id }
    }
    val promptKey = targetPrompt?.let { it.name + "" + it.title }
    val translationKey = targetLanguage?.let { "translation_icon" + "" + it }
    val candidates = when {
        targetLanguageIcon -> languageIconCallbacks.fanOutByReport[reportId].orEmpty()
        translationKey != null -> internalPromptIconFanOutByPrompt[translationKey].orEmpty()
        promptKey != null -> internalPromptIconFanOutByPrompt[promptKey].orEmpty()
        targetPairId != null -> pairIconFanOutByPair[targetPairId].orEmpty()
        targetAgentId != null -> agentIconFanOutByAgent[targetAgentId].orEmpty()
        else -> iconFanOutByReport[reportId].orEmpty()
    }
    AlternativeIconsScreen(
        reportId = reportId,
        candidates = candidates,
        onPickIcon = { emoji, iconModel ->
            when {
                targetLanguageIcon -> languageIconCallbacks.onPickAlternative(reportId, emoji, iconModel)
                targetLanguage != null -> {
                    val cand = candidates.filterIsInstance<IconCandidate.Done>()
                        .firstOrNull { "${it.provider.id}/${it.model}" == iconModel }
                    if (cand != null) translationIconCallbacks.onPick(targetLanguage, cand)
                }
                // Per-row variant wins when the icon flow was opened
                // from a specific Meta tile — the pick lands on that
                // row's `icon` field on disk so the other tiles
                // sharing this prompt name keep their own icons.
                targetPrompt != null && targetMetaRowId != null -> {
                    onPickMetaRowIcon(reportId, targetMetaRowId, emoji)
                }
                targetPrompt != null -> {
                    val cand = candidates.filterIsInstance<IconCandidate.Done>()
                        .firstOrNull { "${it.provider.id}/${it.model}" == iconModel }
                    if (cand != null) onPickInternalPromptIcon(targetPrompt, cand)
                }
                targetPairId != null -> onPickPairIcon(reportId, targetPairId, emoji)
                targetAgentId != null -> onPickAgentIcon(reportId, targetAgentId, emoji)
                else -> onPickAlternativeIcon(reportId, emoji, iconModel)
            }
            onCloseAll()
        },
        onRestart = {
            when {
                targetLanguageIcon -> languageIconCallbacks.onRestartFanOut(reportId)
                targetLanguage != null -> translationIconCallbacks.onRestartFanOut(targetLanguage)
                targetPrompt != null -> onRestartInternalPromptIconFanOut(targetPrompt)
                targetPairId != null -> onRestartPairIconFanOut(reportId, targetPairId)
                targetAgentId != null -> onRestartAgentIconFanOut(reportId, targetAgentId)
                else -> onRestartIconFanOut(reportId)
            }
            onRestartReopenPicker()
        },
        onNavigateToTraceFile = onNavigateToTraceFile,
        onBack = onClose
    )
}

/** Helper composable for the per-agent icon detail overlay —
 *  extracted out of `ReportsScreen` so the parent's body stays
 *  under the JVM 64 KB per-method bytecode limit. Returns true when
 *  the overlay rendered, false when the agent / its provider didn't
 *  resolve and the caller should drop the state. */
@Composable
internal fun AgentIconDetailOverlay(
    agentId: String,
    aiSettings: com.ai.model.Settings,
    currentReportId: String?,
    loadedReportPrompt: String,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    agentRecordsByAgentId: Map<String, com.ai.data.ReportAgent>,
    agentIconFanOutByAgent: Map<String, List<IconCandidate>>,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeIcons: (Boolean) -> Unit,
    onApplyIcon: (String) -> Unit,
    onClose: () -> Unit,
    /** Hide "Find alternative icons" when false (the standalone
     *  Report-model route can't host the alternatives picker). */
    showFindAlternatives: Boolean = true,
): Boolean {
    val iconPrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "workers" && it.name == "model-icons"
    }
    val altPrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "alt" && it.name == "report"
    }
    val agent = agentRecordsByAgentId[agentId] ?: return false
    val provider = AppService.findById(agent.provider) ?: return false
    val hasActiveAgentFanOut = agentIconFanOutByAgent[agentId].orEmpty().isNotEmpty()
    val agentIconTraceFilename = rememberAgentIconTrace(
        reportId = currentReportId,
        agentModel = agent.model,
        iconKey = agent.icon,
        winningTier = agent.iconWinningTier,
        promptUsed = agent.iconPromptUsed
    )
    // Subject = bundled prompt name that produced the displayed emoji.
    val subject = agent.iconPromptUsed ?: "report_title_icon"
    // Reconstruct the API interaction. A Find-alternative pick ran
    // alt/report (@PROMPT@ + @RESPONSE@ against the agent's own
    // model); every other icon is the worker title→icon result
    // (model/icons with @TITLE@ = the model title).
    val apiInteraction = if (agent.iconPromptUsed == "report_alt") {
        val resolved = (altPrompt?.text.orEmpty())
            .replace("@PROMPT@", loadedReportPrompt)
            .replace("@RESPONSE@", agent.responseBody.orEmpty())
        buildOneShotApiInteraction(resolved, agent.icon)
    } else {
        val resolved = (iconPrompt?.text.orEmpty())
            .replace("@TITLE@", agent.modelTitle.orEmpty())
        buildOneShotApiInteraction(resolved, agent.icon)
    }
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides onClose
    ) {
        IconLookupScreen(IconLookupContext(
            helpTopic = "icon_lookup_agent",
            subject = subject,
            provider = provider,
            model = agent.model,
            pricingTier = "",
            cost = agent.iconInputCost + agent.iconOutputCost,
            apiInteraction = apiInteraction,
            emoji = agent.icon,
            errorMessage = agent.iconErrorMessage,
            traceFile = agentIconTraceFilename,
            hasActiveFanOut = hasActiveAgentFanOut,
            showFindAlternatives = showFindAlternatives,
            onFindAlternativeIcons = { onFindAlternativeIcons(hasActiveAgentFanOut) },
            onApplyIcon = onApplyIcon,
            onContinueChat = null,
            onNavigateToModelInfo = { /* model info nav not currently wired in this overlay */ },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onBack = onClose
        ))
    }
    return true
}

/** 6th adapter — unified Icon lookup for the per-fan-out-pair
 *  icon. Reached by long-pressing a pair's icon cell on
 *  FanOutL2Screen (MAIN mode) or tapping the big centred icon on
 *  FanOutL3Screen. Reads the pair's row off
 *  [SecondaryResultStorage] (re-reads on every iconRefreshTick so
 *  a Find-alt pick refreshes the displayed emoji + cost without a
 *  manual reload). Returns true when the overlay rendered, false
 *  when the pair id no longer resolves on disk. */
@Composable
internal fun PairIconDetailOverlay(
    pairId: String,
    reportId: String,
    aiSettings: com.ai.model.Settings,
    iconRefreshTick: Int,
    loadedReportPrompt: String,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    agentRecordsByAgentId: Map<String, com.ai.data.ReportAgent>,
    pairIconFanOutByPair: Map<String, List<IconCandidate>>,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeIcons: (Boolean) -> Unit,
    onApplyIcon: (String) -> Unit,
    onClose: () -> Unit
): Boolean {
    val context = LocalContext.current
    // Re-read the SR on each iconRefreshTick so a Fan Meta
    // run / Find-alt pick reflects immediately. Returns null on
    // first composition; the overlay falls through to a brief
    // "loading" branch then re-renders.
    // Async disk read — the pair lives on SecondaryResultStorage.
    // `loaded` flips true once the produceState block has run at
    // least once, so we can distinguish "still loading" (return
    // true with an empty placeholder, keep the overlay state) from
    // "pair gone from disk" (return false → caller clears state).
    val loaded = remember { mutableStateOf(false) }
    val pairState = androidx.compose.runtime.produceState<com.ai.data.SecondaryResult?>(
        initialValue = null, pairId, reportId, iconRefreshTick
    ) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.SecondaryResultStorage.listForReport(context, reportId)
                .firstOrNull { it.id == pairId }
        }
        loaded.value = true
    }
    val pair = pairState.value
    if (pair == null) {
        if (!loaded.value) {
            // Still loading — render a blank fullscreen so nothing
            // beneath bleeds through; keep the overlay state alive.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.AppBackground)
            ) {}
            return true
        }
        // Loaded but no row found — pair was deleted. Tell the
        // caller to drop the overlay state.
        return false
    }
    val provider = AppService.findById(pair.providerId) ?: return false
    val fanMetaPrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "workers" && it.name == "fan-meta"
    }
    val altPrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "alt" && it.name == "fan_out"
    }
    val metaPrompt = pair.metaPromptId?.let { mid ->
        aiSettings.internalPrompts.firstOrNull { it.id == mid }
    }
    val sourceAgent = pair.fanOutSourceAgentId?.let { agentRecordsByAgentId[it] }
    val hasActivePairFanOut = pairIconFanOutByPair[pairId].orEmpty().isNotEmpty()
    val pairIconTraceFile = rememberIconTrace(
        reportId = reportId,
        model = pair.model,
        categories = listOf("fan/meta", "alt/fan_out")
    )
    val subject = pair.iconPromptUsed ?: "fan-meta"
    // Reconstruct what hit the wire. A Find-alternative pick
    // (fan_out_alt) ran alt/fan_out — its @QUESTION@/@SOURCE_RESPONSE@/
    // @META_PROMPT@/@RESPONSE@ tokens resolve to the report prompt, the
    // source agent's response, the resolved meta-prompt and the pair's
    // own response. Every other icon came from the single
    // fan/meta call (@PROMPT@ = the pair's own response).
    val sourceBody = sourceAgent?.responseBody.orEmpty()
    val resolvedMetaForDisplay = metaPrompt?.text?.let { template ->
        com.ai.data.resolveSecondaryPrompt(
            template,
            question = loadedReportPrompt,
            results = "",
            count = 0,
            title = loadedReportTitle
        ).replace("@RESPONSE@", sourceBody)
    }.orEmpty()
    val apiInteraction = if (pair.iconPromptUsed == "fan_out_alt") {
        val resolved = (altPrompt?.text.orEmpty())
            .replace("@QUESTION@", loadedReportPrompt)
            .replace("@SOURCE_RESPONSE@", sourceBody)
            .replace("@META_PROMPT@", resolvedMetaForDisplay)
            .replace("@RESPONSE@", pair.content.orEmpty())
        buildOneShotApiInteraction(resolved, pair.icon)
    } else {
        val resolved = (fanMetaPrompt?.text.orEmpty())
            .replace("@PROMPT@", pair.content.orEmpty())
        buildOneShotApiInteraction(resolved, pair.icon)
    }
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides onClose
    ) {
        IconLookupScreen(IconLookupContext(
            helpTopic = "icon_lookup_pair",
            subject = subject,
            provider = provider,
            model = pair.model,
            pricingTier = "",
            cost = pair.iconInputCost + pair.iconOutputCost,
            apiInteraction = apiInteraction,
            emoji = pair.icon,
            errorMessage = pair.iconErrorMessage,
            traceFile = pairIconTraceFile,
            hasActiveFanOut = hasActivePairFanOut,
            onFindAlternativeIcons = { onFindAlternativeIcons(hasActivePairFanOut) },
            onApplyIcon = onApplyIcon,
            onContinueChat = null,
            onNavigateToModelInfo = { /* model info nav not currently wired in this overlay */ },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onBack = onClose
        ))
    }
    return true
}

/** Helper composable for the per-Internal-Prompt Meta-icon
 *  detail overlay. Extracted out of `ReportsScreen` so the
 *  parent stays under the JVM 64 KB per-method bytecode limit.
 *  Returns true when the overlay rendered, false when the prompt
 *  id didn't resolve and the caller should drop the state. */
@Composable
internal fun MetaIconDetailOverlay(
    promptId: String,
    iconRefreshTick: Int,
    internalPrompts: List<com.ai.model.InternalPrompt>,
    fanOutCandidates: Map<String, List<IconCandidate>>,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    onOpenAlternativeIcons: (Boolean) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onApplyIcon: (String) -> Unit,
    onClose: () -> Unit
): Boolean {
    val prompt = internalPrompts.firstOrNull { it.id == promptId } ?: return false
    val entry = remember(promptId, prompt.name, prompt.title, iconRefreshTick) {
        com.ai.data.InternalPromptIconCache.getEntry(prompt.name, prompt.title)
    }
    val promptKey = prompt.name + "" + prompt.title
    val hasActiveFanOut = fanOutCandidates[promptKey].orEmpty().isNotEmpty()
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides { onClose() }
    ) {
        val provider = entry?.providerId?.let { AppService.findById(it) } ?: AppService.LOCAL
        // Meta cache is cross-report so we don't scope by reportId
        // — match on the cached model + the icon_meta* category set
        // (base + alt). Newest matching trace wins.
        val metaTraceFile = rememberIconTrace(
            reportId = null,
            model = entry?.model,
            categories = listOf("second/meta", "alt/meta")
        )
        IconLookupScreen(IconLookupContext(
            helpTopic = "icon_lookup_meta",
            subject = entry?.promptName ?: "second-meta",
            provider = provider,
            model = entry?.model.orEmpty(),
            pricingTier = "",
            cost = (entry?.inputCost ?: 0.0) + (entry?.outputCost ?: 0.0),
            apiInteraction = buildOneShotApiInteraction(
                entry?.promptText.orEmpty(),
                entry?.responseText ?: entry?.emoji
            ),
            emoji = entry?.emoji,
            errorMessage = null,
            traceFile = metaTraceFile,
            hasActiveFanOut = hasActiveFanOut,
            onFindAlternativeIcons = { onOpenAlternativeIcons(hasActiveFanOut) },
            onApplyIcon = onApplyIcon,
            onContinueChat = null,
            onNavigateToModelInfo = { /* meta-icon flow doesn't wire Model Info nav */ },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onBack = onClose
        ))
    }
    return true
}

/** Helper composable for the per-translation Meta-icon detail
 *  overlay. Extracted out of `ReportsScreen` so the parent stays
 *  under the JVM 64 KB per-method bytecode limit. Reads the
 *  cache entry by the synthetic `("translation_icon", language)`
 *  key, computes whether a per-language fan-out is in flight,
 *  and renders the generic [InternalPromptIconDetailScreen]. */
@Composable
internal fun TranslationIconDetailOverlay(
    language: String,
    iconRefreshTick: Int,
    fanOutCandidates: Map<String, List<IconCandidate>>,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    onOpenAlternativeIcons: (Boolean) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onApplyIcon: (String) -> Unit,
    onClose: () -> Unit
) {
    val entry = remember(language, iconRefreshTick) {
        com.ai.data.InternalPromptIconCache.getEntry("translation_icon", language)
    }
    val translationKey = "translation_icon" + "" + language
    val hasActiveFanOut = fanOutCandidates[translationKey].orEmpty().isNotEmpty()
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides { onClose() }
    ) {
        val provider = entry?.providerId?.let { AppService.findById(it) } ?: AppService.LOCAL
        val translationTraceFile = rememberIconTrace(
            reportId = null,
            model = entry?.model,
            categories = listOf("translation/icon", "alt/translation")
        )
        IconLookupScreen(IconLookupContext(
            helpTopic = "icon_lookup_translation",
            subject = entry?.promptName ?: "translation-icon",
            provider = provider,
            model = entry?.model.orEmpty(),
            pricingTier = "",
            cost = (entry?.inputCost ?: 0.0) + (entry?.outputCost ?: 0.0),
            apiInteraction = buildOneShotApiInteraction(
                entry?.promptText.orEmpty(),
                entry?.responseText ?: entry?.emoji
            ),
            emoji = entry?.emoji,
            errorMessage = null,
            traceFile = translationTraceFile,
            hasActiveFanOut = hasActiveFanOut,
            onFindAlternativeIcons = { onOpenAlternativeIcons(hasActiveFanOut) },
            onApplyIcon = onApplyIcon,
            onContinueChat = null,
            onNavigateToModelInfo = { /* translation-icon flow doesn't wire Model Info nav */ },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onBack = onClose
        ))
    }
}

@Composable
internal fun RenderLanguageDetailOverlay(
    reportId: String,
    aiSettings: com.ai.model.Settings,
    promptText: String,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    iconRefreshTick: Int,
    hasActiveFanOut: Boolean,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    continueChat: ContinueChatCallbacks?,
    onFindAlternativeIcons: () -> Unit,
    onApplyIcon: (String) -> Unit,
    onBack: () -> Unit,
) {
    val languagePrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "workers" && it.name == "report-language-icon"
    } ?: return
    val languageAgent = languagePrompt.workers.firstNotNullOfOrNull {
        aiSettings.resolveWorker(it)
    } ?: return
    val altLanguagePrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "alt" && it.name == "language"
    }
    val context = LocalContext.current
    // Load language fields here (not at the ReportsScreen scope) so
    // the parent's bytecode stays under the JVM 64 KB per-method
    // ceiling. Re-read on every iconRefreshTick bump so a fresh
    // detection result lands in the open detail screen too.
    data class LangSnapshot(
        val icon: String?, val error: String?, val model: String?,
        val rawResponse: String?, val cost: Double, val traceFile: String?
    )
    val snapshot = produceState(initialValue = LangSnapshot(null, null, null, null, 0.0, null), reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val r = com.ai.data.ReportStorage.getReport(context, reportId)
            LangSnapshot(
                r?.languageIcon, r?.languageIconErrorMessage, r?.languageIconModel,
                r?.languageIconRawResponse,
                (r?.languageIconInputCost ?: 0.0) + (r?.languageIconOutputCost ?: 0.0),
                r?.languageIconTraceFile
            )
        }
    }.value
    // Re-read the persisted prompt-used + language name so we can
    // build a richer API-interaction transcript (the bundled
    // `language` prompt uses @LANGUAGE@; we substitute with the
    // detected language for the [user] turn).
    val ctxData = produceState<Pair<String?, String?>>(initialValue = null to null, reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val r = com.ai.data.ReportStorage.getReport(context, reportId)
            r?.languageIconPromptUsed to r?.languageName
        }
    }.value
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides onBack
    ) {
        val infoTarget = resolveInfoTarget(snapshot.model, languageAgent, aiSettings)
        // The language icon came from the single report/language call
        // (@PROMPT@ = the report prompt); a Find-alternative pick
        // (language_alt) ran alt/language (@LANGUAGE@ = detected language).
        val resolvedPrompt = if (ctxData.first == "language_alt")
            (altLanguagePrompt?.text.orEmpty()).replace("@LANGUAGE@", ctxData.second.orEmpty())
        else
            languagePrompt.text.replace("@PROMPT@", promptText)
        val provider = snapshot.model?.split("/", limit = 2)?.firstOrNull()
            ?.let { AppService.findById(it) } ?: languageAgent.provider
        val modelId = snapshot.model?.split("/", limit = 2)?.getOrNull(1)
            ?: aiSettings.getEffectiveModelForAgent(languageAgent)
        IconLookupScreen(IconLookupContext(
            helpTopic = "icon_lookup_language",
            subject = ctxData.first ?: "language",
            provider = provider,
            model = modelId,
            pricingTier = "",
            cost = snapshot.cost,
            apiInteraction = buildOneShotApiInteraction(
                resolvedPrompt,
                snapshot.rawResponse ?: snapshot.icon
            ),
            emoji = snapshot.icon,
            errorMessage = snapshot.error,
            traceFile = snapshot.traceFile,
            hasActiveFanOut = hasActiveFanOut,
            onFindAlternativeIcons = onFindAlternativeIcons,
            onApplyIcon = onApplyIcon,
            onContinueChat = continueChat?.let { c -> { c.onCurrent(reportId, "") } },
            onNavigateToModelInfo = infoTarget?.let { (p, m) -> { onNavigateToModelInfo(p, m) } } ?: { },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onBack = onBack
        ))
    }
}

/** Bytecode-saving wrapper for the two icon-detail variants the
 *  parent ReportsScreen multiplexes between via [targetLanguageIcon].
 *  Returns true if it rendered (caller early-returns); false when
 *  the report-icon prompt / agent isn't configured (caller should
 *  drop showIconDetail). Extracted from ReportsScreen so the
 *  parent's per-method bytecode stays under the JVM 64 KB ceiling. */
@Composable
internal fun ReportIconOrLanguageDetailOverlay(
    reportId: String,
    aiSettings: com.ai.model.Settings,
    promptText: String,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    iconRefreshTick: Int,
    targetLanguageIcon: Boolean,
    reportIcon: String?,
    reportIconError: String?,
    reportIconCost: Double,
    reportIconModel: String?,
    reportIconTraceFile: String?,
    iconFanOutByReport: Map<String, List<IconCandidate>>,
    languageIconCallbacks: LanguageIconCallbacks,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    continueChat: ContinueChatCallbacks?,
    onOpenPicker: () -> Unit,
    onOpenAltIcons: () -> Unit,
    onApplyReportIcon: (String) -> Unit,
    onApplyLanguageIcon: (String) -> Unit,
    onClose: () -> Unit,
): Boolean {
    if (targetLanguageIcon) {
        val hasLangFanOut = languageIconCallbacks.fanOutByReport[reportId].orEmpty().isNotEmpty()
        RenderLanguageDetailOverlay(
            reportId = reportId,
            aiSettings = aiSettings,
            promptText = promptText,
            effectiveReportIcon = effectiveReportIcon,
            loadedReportTitle = loadedReportTitle,
            iconRefreshTick = iconRefreshTick,
            hasActiveFanOut = hasLangFanOut,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo,
            continueChat = continueChat,
            onFindAlternativeIcons = { if (hasLangFanOut) onOpenAltIcons() else onOpenPicker() },
            onApplyIcon = onApplyLanguageIcon,
            onBack = onClose
        )
        return true
    }
    val iconPrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "workers" && it.name == "report-icon"
    } ?: return false
    val iconAgent = iconPrompt.workers.firstNotNullOfOrNull {
        aiSettings.resolveWorker(it)
    } ?: return false
    val altPrompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "alt" && it.name == "main"
    }
    val hasActiveFanOut = iconFanOutByReport[reportId].orEmpty().isNotEmpty()
    val context = LocalContext.current
    // Re-read the persisted prompt-used so the subject row reflects
    // "main_alt" after a Find-alt pick.
    val promptUsed = produceState<String?>(initialValue = null, reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, reportId)?.iconPromptUsed
        }
    }.value
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides onClose
    ) {
        val infoTarget = resolveInfoTarget(reportIconModel, iconAgent, aiSettings)
        // The report icon came from report/icon (@TITLE_LONG@ =
        // the report's long title); a Find-alternative pick (main_alt)
        // ran alt/main (@PROMPT@ = the report prompt).
        val resolvedPrompt = if (promptUsed == "main_alt")
            (altPrompt?.text.orEmpty()).replace("@PROMPT@", promptText)
        else
            iconPrompt.text.replace("@TITLE_LONG@", loadedReportTitle.orEmpty())
        val provider = reportIconModel?.split("/", limit = 2)?.firstOrNull()
            ?.let { AppService.findById(it) } ?: iconAgent.provider
        val modelId = reportIconModel?.split("/", limit = 2)?.getOrNull(1)
            ?: aiSettings.getEffectiveModelForAgent(iconAgent)
        IconLookupScreen(IconLookupContext(
            helpTopic = "icon_lookup_main",
            subject = promptUsed ?: "main",
            provider = provider,
            model = modelId,
            pricingTier = "",
            cost = reportIconCost,
            apiInteraction = buildOneShotApiInteraction(resolvedPrompt, reportIcon),
            emoji = reportIcon,
            errorMessage = reportIconError,
            traceFile = reportIconTraceFile,
            hasActiveFanOut = hasActiveFanOut,
            onFindAlternativeIcons = { if (hasActiveFanOut) onOpenAltIcons() else onOpenPicker() },
            onApplyIcon = onApplyReportIcon,
            onContinueChat = continueChat?.let { c -> { c.onCurrent(reportId, "") } },
            onNavigateToModelInfo = infoTarget?.let { (p, m) -> { onNavigateToModelInfo(p, m) } } ?: { },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onBack = onClose
        ))
    }
    return true
}

/** Thin wrapper around [AlternativeIconsRouter] — extracted from
 *  [ReportsScreen] so the parent's per-method bytecode stays under
 *  the JVM 64 KB ceiling. Pure plumbing, no extra logic. */
@Composable
internal fun AlternativeIconsOverlayHost(
    reportId: String,
    aiSettings: com.ai.model.Settings,
    translationIconLanguageFor: String?,
    promptIconDetailForId: String?,
    /** Non-null when the active prompt-icon flow was opened from a
     *  specific SecondaryResult row — routes the eventual alt pick
     *  through [onPickMetaRowIcon] (per-row override) instead of
     *  [onPickInternalPromptIcon] (shared name-keyed cache). */
    targetMetaRowId: String?,
    fanOutTargetAgentId: String?,
    pairIconDetailFor: String?,
    targetLanguageIcon: Boolean,
    internalPromptIconFanOutByPrompt: Map<String, List<IconCandidate>>,
    agentIconFanOutByAgent: Map<String, List<IconCandidate>>,
    pairIconFanOutByPair: Map<String, List<IconCandidate>>,
    iconFanOutByReport: Map<String, List<IconCandidate>>,
    translationIconCallbacks: TranslationIconCallbacks,
    languageIconCallbacks: LanguageIconCallbacks,
    onPickInternalPromptIcon: (com.ai.model.InternalPrompt, IconCandidate.Done) -> Unit,
    onPickMetaRowIcon: (reportId: String, rowId: String, emoji: String) -> Unit,
    onPickAgentIcon: (String, String, String) -> Unit,
    onPickPairIcon: (String, String, String) -> Unit,
    onPickAlternativeIcon: (String, String, String) -> Unit,
    onRestartInternalPromptIconFanOut: (com.ai.model.InternalPrompt) -> Unit,
    onRestartAgentIconFanOut: (String, String) -> Unit,
    onRestartPairIconFanOut: (String, String) -> Unit,
    onRestartIconFanOut: (String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onCloseAll: () -> Unit,
    onRestartReopenPicker: () -> Unit,
    onClose: () -> Unit,
) {
    AlternativeIconsRouter(
        reportId = reportId,
        targetLanguage = translationIconLanguageFor,
        targetPromptId = promptIconDetailForId,
        targetMetaRowId = targetMetaRowId,
        targetAgentId = fanOutTargetAgentId,
        targetPairId = pairIconDetailFor,
        targetLanguageIcon = targetLanguageIcon,
        internalPrompts = aiSettings.internalPrompts,
        internalPromptIconFanOutByPrompt = internalPromptIconFanOutByPrompt,
        agentIconFanOutByAgent = agentIconFanOutByAgent,
        pairIconFanOutByPair = pairIconFanOutByPair,
        iconFanOutByReport = iconFanOutByReport,
        translationIconCallbacks = translationIconCallbacks,
        languageIconCallbacks = languageIconCallbacks,
        onPickInternalPromptIcon = onPickInternalPromptIcon,
        onPickMetaRowIcon = onPickMetaRowIcon,
        onPickAgentIcon = onPickAgentIcon,
        onPickPairIcon = onPickPairIcon,
        onPickAlternativeIcon = onPickAlternativeIcon,
        onRestartInternalPromptIconFanOut = onRestartInternalPromptIconFanOut,
        onRestartAgentIconFanOut = onRestartAgentIconFanOut,
        onRestartPairIconFanOut = onRestartPairIconFanOut,
        onRestartIconFanOut = onRestartIconFanOut,
        onNavigateToTraceFile = onNavigateToTraceFile,
        onCloseAll = onCloseAll,
        onRestartReopenPicker = onRestartReopenPicker,
        onClose = onClose,
    )
}

/** Resolve the (provider, model) pair Model Info should open for an
 *  icon detail screen. [iconModel] (format "providerId/modelId") wins
 *  when the user picked an alt-icon; otherwise falls back to the
 *  bundled-agent default. Returns null when nothing resolves so the
 *  caller can hide the ℹ️ icon. */
internal fun resolveInfoTarget(
    iconModel: String?,
    iconAgent: Agent,
    aiSettings: Settings,
): Pair<AppService, String>? {
    iconModel?.split("/", limit = 2)?.let { parts ->
        if (parts.size == 2) {
            val prov = AppService.findById(parts[0])
            if (prov != null) return prov to parts[1]
        }
    }
    val model = aiSettings.getEffectiveModelForAgent(iconAgent)
    return iconAgent.provider to model
}
