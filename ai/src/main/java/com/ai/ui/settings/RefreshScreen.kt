package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.RestartAppBanner
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.restartApp
import com.ai.viewmodel.CatalogStep
import com.ai.viewmodel.RefreshAllState
import com.ai.viewmodel.RefreshStepStatus
import com.ai.viewmodel.WorkerRow
import com.ai.viewmodel.WorkerStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RefreshScreen(
    aiSettings: Settings,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onSave: (Settings) -> Unit,
    refreshAllState: RefreshAllState?,
    onStartRefreshAll: () -> Unit,
    onStartRefreshWorkers: () -> Unit,
    onClearRefreshAllState: () -> Unit,
    /** Navigate the parent settings screen to AI Setup → Providers →
     *  edit page for a specific provider. Used by the Refresh-all
     *  progress screen's "Failed providers" list so the user can jump
     *  straight to the provider whose key / model needs fixing. */
    onOpenProvider: (AppService) -> Unit = {},
    onNavigateToHelpTopic: (String) -> Unit = {},
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var progressTitle by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    val isAnyRunning by remember { derivedStateOf { progressTitle.isNotBlank() } }
    var taskError by remember { mutableStateOf<String?>(null) }

    var openRouterResult by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var litellmResult by remember { mutableStateOf<Int?>(null) }
    var modelsDevResult by remember { mutableStateOf<Int?>(null) }
    var heliconeResult by remember { mutableStateOf<Int?>(null) }
    var llmPricesResult by remember { mutableStateOf<Int?>(null) }
    var aaResult by remember { mutableStateOf<Int?>(null) }
    var showOpenRouterDialog by remember { mutableStateOf(false) }
    var showLiteLLMDialog by remember { mutableStateOf(false) }
    var showModelsDevDialog by remember { mutableStateOf(false) }
    var showHeliconeDialog by remember { mutableStateOf(false) }
    var showLLMPricesDialog by remember { mutableStateOf(false) }
    var showAaDialog by remember { mutableStateOf(false) }

    fun launchTask(title: String, initialText: String = "", block: suspend () -> Unit) {
        scope.launch {
            progressTitle = title; progressText = initialText
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) {
                // Fall back to the exception class name when the
                // message is null. The previous "${e.message}" path
                // showed the literal "null" toast on NPE / OOM /
                // generic Throwable subclasses without a populated
                // message string.
                taskError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            finally { progressTitle = ""; progressText = "" }
        }
    }

    if (isAnyRunning) {
        AlertDialog(onDismissRequest = {}, title = { Text(progressTitle) },
            text = { Column {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                if (progressText.isNotBlank()) { Spacer(modifier = Modifier.height(8.dp)); Text(progressText, fontSize = 12.sp, color = AppColors.TextTertiary) }
            }}, confirmButton = {})
    }

    taskError?.let { error ->
        AlertDialog(onDismissRequest = { taskError = null }, title = { Text("Error") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { taskError = null }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    // Refresh-all takes over the screen whenever a run is in flight or
    // just finished. Lives in AppViewModel so it survives navigation —
    // user can back-gesture out, come back, and pick up the live view.
    refreshAllState?.let { state ->
        val failedProviders = remember(state) {
            state.workerRows.filter { it.stage is WorkerStage.Failed }
                .mapNotNull { row -> AppService.entries.find { it.id == row.serviceId } }
        }
        fun doRestart() {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        SettingsPreferences(prefs, context.filesDir).flushUsageStats()
                    }
                }
                restartApp(context)
            }
        }
        RefreshAllProgressScreen(
            state = state,
            failedProviders = failedProviders,
            onOpenProvider = { svc ->
                // Tear down the in-progress overlay before navigating
                // so a back-press from the provider edit screen
                // doesn't drop the user back into a stale refresh-all
                // view. Background work in the VM continues regardless.
                onClearRefreshAllState()
                onOpenProvider(svc)
            },
            onNavigateToHelpTopic = onNavigateToHelpTopic,
            onBack = onBack,
            // Surfaces a "Restart application" banner at the top of the
            // progress screen once the run is done — replaces the
            // earlier modal RestartAppDialog. Null while running.
            onRestart = if (state.isFinished) ({ doRestart() }) else null,
            restartMessage = if (state.isFinished) "${state.title} done" else null
        )
        return
    }

    if (showOpenRouterDialog && openRouterResult != null) {
        val (pricing, specPricing, specParams) = openRouterResult!!
        val ok = pricing > 0 || specPricing > 0
        val kept = if (!ok) {
            // fetchOpenRouterPricing returns an empty map on failure
            // rather than null, so we look up "openrouter" via
            // previousCacheInfo to surface what's still on disk.
            PricingCache.previousCacheInfo(context, "openrouter")?.let {
                RefreshResultRow("Kept previous", "${it.entryCount} from ${it.ageString()}", AppColors.Orange)
            }
        } else null
        RefreshResultScreen(
            titleText = "OpenRouter",
            description = when {
                ok -> "Pulled the OpenRouter catalog. Pricing entries feed the OpenRouter tier; the spec rows feed model capability flags and the supported-parameters list used by the chat UI."
                kept != null -> "Failed to fetch the OpenRouter catalog. The previously fetched data is still in use — see the rows below."
                else -> "Failed to fetch the OpenRouter catalog. Check the OpenRouter key and connectivity."
            },
            rows = listOfNotNull(
                RefreshResultRow("Pricing entries", "$pricing models", if (pricing > 0) AppColors.Green else AppColors.Red),
                RefreshResultRow("Spec pricing", "$specPricing", if (specPricing > 0) AppColors.Green else AppColors.TextTertiary),
                RefreshResultRow("Spec parameters", "$specParams", if (specParams > 0) AppColors.Green else AppColors.TextTertiary),
                RefreshResultRow("Cache age", PricingCache.getOpenRouterCacheAge(context), AppColors.TextTertiary),
                kept
            ),
            sampleHeader = if (ok) "Sample model entries" else null,
            sampleEntries = if (ok) PricingCache.getOpenRouterPricing(context).keys.sorted().take(8) else emptyList(),
            onBack = { showOpenRouterDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Helper: build a "Kept previous" row for a failed per-tier
    // refresh. Returns null when there is no previous cache to fall
    // back on. The fetch functions intentionally leave the in-memory
    // cache untouched on failure, so this reports what's actually
    // still usable.
    fun keptPreviousRow(source: String): RefreshResultRow? {
        val info = PricingCache.previousCacheInfo(context, source) ?: return null
        return RefreshResultRow("Kept previous", "${info.entryCount} from ${info.ageString()}", AppColors.Orange)
    }

    if (showLiteLLMDialog) {
        val n = litellmResult
        val ok = n != null && n > 0
        val kept = if (!ok) keptPreviousRow("litellm") else null
        RefreshResultScreen(
            titleText = "LiteLLM Pricing",
            description = when {
                ok -> "Downloaded model_prices_and_context_window.json. LiteLLM is the primary tier in the layered pricing lookup — also feeds capability flags (vision, web search, etc.)."
                kept != null -> "Failed to fetch model_prices_and_context_window.json from BerriAI/litellm. The previously fetched catalog is still in use — see the rows below."
                else -> "Failed to fetch model_prices_and_context_window.json from BerriAI/litellm. Check connectivity and try again."
            },
            rows = listOfNotNull(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Priced models", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary),
                kept
            ),
            sampleHeader = if (ok) "Sample model entries" else null,
            sampleEntries = if (ok) PricingCache.getLiteLLMPricing(context).keys.sorted().take(8) else emptyList(),
            onBack = { showLiteLLMDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showModelsDevDialog) {
        val n = modelsDevResult
        val ok = n != null && n > 0
        val kept = if (!ok) keptPreviousRow("modelsdev") else null
        RefreshResultScreen(
            titleText = "models.dev",
            description = when {
                ok -> "Pulled the models.dev community catalog. Sits below LiteLLM in the layered pricing lookup — fills gaps for newer models and -latest aliases that haven't reached LiteLLM yet."
                kept != null -> "Failed to fetch from models.dev. The previously fetched catalog is still in use — see the rows below."
                else -> "Failed to fetch from models.dev. Check connectivity and try again."
            },
            rows = listOfNotNull(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Priced models", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary),
                kept
            ),
            onBack = { showModelsDevDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showHeliconeDialog) {
        val n = heliconeResult
        val ok = n != null && n > 0
        val kept = if (!ok) keptPreviousRow("helicone") else null
        RefreshResultScreen(
            titleText = "Helicone",
            description = when {
                ok -> "Pulled the Helicone pricing aggregator. Pricing-only fallback — sits after LiteLLM and models.dev in the layered lookup, before llm-prices."
                kept != null -> "Failed to fetch from helicone.ai/api/llm-costs. The previously fetched catalog is still in use — see the rows below."
                else -> "Failed to fetch from helicone.ai/api/llm-costs. Check connectivity and try again."
            },
            rows = listOfNotNull(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Entries", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary),
                kept
            ),
            onBack = { showHeliconeDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showLLMPricesDialog) {
        val n = llmPricesResult
        val ok = n != null && n > 0
        val kept = if (!ok) keptPreviousRow("llmprices") else null
        RefreshResultScreen(
            titleText = "llm-prices.com",
            description = when {
                ok -> "Pulled Simon Willison's curated per-vendor pricing tables. Useful as a tiebreaker for the major commercial providers."
                kept != null -> "Failed to fetch from simonw/llm-prices. The previously fetched catalog is still in use — see the rows below."
                else -> "Failed to fetch from simonw/llm-prices. Check connectivity and try again."
            },
            rows = listOfNotNull(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Entries", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary),
                RefreshResultRow("Vendors", "10", AppColors.TextTertiary),
                kept
            ),
            onBack = { showLLMPricesDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showAaDialog) {
        val n = aaResult
        val ok = n != null && n > 0
        val kept = if (!ok) keptPreviousRow("aa") else null
        val description = when {
            ok -> "Pulled Artificial Analysis (pricing + intelligence_index + output speed). Bottom-of-stack pricing fallback before manual override and DEFAULT."
            artificialAnalysisApiKey.isBlank() -> "Add the Artificial Analysis API key under External Services first."
            kept != null -> "Failed to fetch from artificialanalysis.ai/api/v2/data/llms/models. The previously fetched catalog is still in use — see the rows below."
            else -> "Failed to fetch from artificialanalysis.ai/api/v2/data/llms/models. Check the API key and try again."
        }
        RefreshResultScreen(
            titleText = "Artificial Analysis",
            description = description,
            rows = listOfNotNull(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Entries", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary),
                kept
            ),
            onBack = { showAaDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Each per-catalog refresh's core work lives in a suspend lambda
    // that captures the surrounding state. The Boolean parameter controls
    // whether the per-step result dialog opens at the end.
    val runOpenRouter: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Pulling OpenRouter catalog"
        withContext(Dispatchers.IO) {
            val pricing = PricingCache.fetchOpenRouterPricing(openRouterApiKey)
            if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, pricing)
            val specs = PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
            openRouterResult = Triple(pricing.size, specs?.first ?: 0, specs?.second ?: 0)
        }
        if (showDialogAtEnd) showOpenRouterDialog = true
    }
    val runLiteLLM: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading model_prices_and_context_window.json"
        val n = PricingCache.fetchLiteLLMPricingOnline(context)
        litellmResult = n
        // Catalog answers may have shifted — refresh the precomputed
        // vision / web-search sets so list renders pick up the new state.
        if (n != null) onSave(aiSettings.recomputeAllCapabilities())
        if (showDialogAtEnd) showLiteLLMDialog = true
    }
    val runModelsDev: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading models.dev/api.json"
        val n = PricingCache.fetchModelsDevOnline(context)
        modelsDevResult = n
        if (n != null) onSave(aiSettings.recomputeAllCapabilities())
        if (showDialogAtEnd) showModelsDevDialog = true
    }
    val runHelicone: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading helicone.ai/api/llm-costs"
        val n = PricingCache.fetchHeliconeOnline(context)
        heliconeResult = n
        // Helicone is pricing-only — no capability flags, so no recompute.
        if (showDialogAtEnd) showHeliconeDialog = true
    }
    val runLLMPrices: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading simonw/llm-prices vendor files"
        val n = PricingCache.fetchLLMPricesOnline(context)
        llmPricesResult = n
        if (showDialogAtEnd) showLLMPricesDialog = true
    }
    val runArtificialAnalysis: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading artificialanalysis.ai/api/v2/data/llms/models"
        val n = PricingCache.fetchArtificialAnalysisOnline(context, artificialAnalysisApiKey)
        aaResult = n
        if (showDialogAtEnd) showAaDialog = true
    }

    // AI Info Providers sub-page lives as a full-screen overlay reached
    // via a NavCard on the main Refresh screen. Same early-return idiom
    // as before so the parent's remember state survives the round-trip.
    var subPage by remember { mutableStateOf<RefreshSubPage?>(null) }
    if (subPage == RefreshSubPage.INFO_PROVIDERS) {
        InfoProvidersRefreshPage(
            isAnyRunning = isAnyRunning,
            openRouterApiKey = openRouterApiKey,
            artificialAnalysisApiKey = artificialAnalysisApiKey,
            onOpenRouter = { launchTask("Refreshing OpenRouter") { runOpenRouter(true) } },
            onLiteLLM = { launchTask("Refreshing LiteLLM") { runLiteLLM(true) } },
            onModelsDev = { launchTask("Refreshing models.dev") { runModelsDev(true) } },
            onHelicone = { launchTask("Refreshing Helicone") { runHelicone(true) } },
            onLLMPrices = { launchTask("Refreshing llm-prices.com") { runLLMPrices(true) } },
            onArtificialAnalysis = { launchTask("Refreshing Artificial Analysis") { runArtificialAnalysis(true) } },
            onNavigateToHelpTopic = onNavigateToHelpTopic,
            onBack = { subPage = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "refresh", title = "Refresh", onBackClick = onBack)

        // Fresh-install gate: with no keyed provider, the Workers phase
        // has nothing to act on, so "Refresh all" would just run the
        // catalog phase. Hide it so the user is steered to AI Info
        // Providers (catalogs only) until they've configured a key.
        val hasAnyKeyedProvider = AppService.entries.any { aiSettings.getApiKey(it).isNotBlank() }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Refresh all — runs the six catalog sources and the
            // per-provider Workers phase in parallel. Status renders on
            // a full-screen progress page owned by AppViewModel, so the
            // user can navigate away and come back to a live view.
            if (hasAnyKeyedProvider) {
                RefreshAction(
                    label = "Refresh all",
                    description = "Refresh the six catalog sources and the per-provider workers (key test, model list, default agent) in parallel. Continues in the background if you navigate away.",
                    enabled = !isAnyRunning,
                    onClick = { onStartRefreshAll() }
                )
                // Worker-only variant: same per-provider feedback as
                // Refresh-all, but skips the six external catalog fetches.
                // Useful when the user only wants to re-seed default
                // agents or re-test keys without paying the catalog
                // round-trip time / quota.
                RefreshAction(
                    label = "Providers / models / default agents",
                    description = "Per-provider key test → model list fetch → default agent rewrite, in parallel. Skips every external pricing/spec catalog. Continues in the background if you navigate away.",
                    enabled = !isAnyRunning,
                    onClick = { onStartRefreshWorkers() }
                )
            }

            // Info Providers sub-page — same RefreshAction shape as the
            // two cards above so the three cards on this screen all
            // read as one column of equal-weight buttons. The button
            // drills into the sub-page that lists the six catalog
            // sources with their own progress rows.
            RefreshAction(
                label = "Info providers",
                description = "Catalog-source refreshes (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis).",
                enabled = !isAnyRunning,
                onClick = { subPage = RefreshSubPage.INFO_PROVIDERS }
            )

        }
    }
}

private enum class RefreshSubPage { INFO_PROVIDERS }

@Composable
private fun InfoProvidersRefreshPage(
    isAnyRunning: Boolean,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onOpenRouter: () -> Unit,
    onLiteLLM: () -> Unit,
    onModelsDev: () -> Unit,
    onHelicone: () -> Unit,
    onLLMPrices: () -> Unit,
    onArtificialAnalysis: () -> Unit,
    onNavigateToHelpTopic: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "refresh_info_providers", title = "AI Info Providers", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RefreshAction(
                label = "OpenRouter",
                description = "Pull OpenRouter's catalog (pricing, capability flags, supported parameters). Needs the OpenRouter External Services key.",
                enabled = !isAnyRunning && openRouterApiKey.isNotBlank(),
                onClick = onOpenRouter,
                helpTopic = "info_provider_openrouter",
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            RefreshAction(
                label = "LiteLLM",
                description = "Download model_prices_and_context_window.json from BerriAI/litellm — the primary source for pricing and capability flags.",
                enabled = !isAnyRunning,
                onClick = onLiteLLM,
                helpTopic = "info_provider_litellm",
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            RefreshAction(
                label = "models.dev",
                description = "Pull the models.dev community catalog. Acts as a LiteLLM fallback for newer models / -latest aliases LiteLLM hasn't picked up yet.",
                enabled = !isAnyRunning,
                onClick = onModelsDev,
                helpTopic = "info_provider_models_dev",
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            RefreshAction(
                label = "Helicone",
                description = "Pull Helicone's pricing aggregator (helicone.ai/api/llm-costs). Pricing-only fallback after LiteLLM and models.dev.",
                enabled = !isAnyRunning,
                onClick = onHelicone,
                helpTopic = "info_provider_helicone",
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            RefreshAction(
                label = "llm-prices.com",
                description = "Pull Simon Willison's curated per-vendor pricing tables (10 vendors). Useful as a tiebreaker on the major commercial providers.",
                enabled = !isAnyRunning,
                onClick = onLLMPrices,
                helpTopic = "info_provider_llm_prices",
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
            RefreshAction(
                label = "Artificial Analysis",
                description = "Pull Artificial Analysis (pricing + intelligence_index + output speed). Needs the API key under External Services.",
                enabled = !isAnyRunning && artificialAnalysisApiKey.isNotBlank(),
                onClick = onArtificialAnalysis,
                helpTopic = "info_provider_artificial_analysis",
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
        }
    }
}


@Composable
private fun RefreshAction(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** When set, an ℹ️ icon appears on the description row that
     *  navigates to the matching info-provider help topic. Null for
     *  rows that aren't info providers. */
    helpTopic: String? = null,
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            RefreshActionRow(label, description, enabled, onClick, helpTopic, onNavigateToHelpTopic)
        }
    }
}

@Composable
private fun RefreshActionRow(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    helpTopic: String? = null,
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(description, fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            if (helpTopic != null) {
                IconButton(onClick = { onNavigateToHelpTopic(helpTopic) }, modifier = Modifier.size(28.dp)) {
                    Text("ℹ️", fontSize = 14.sp)
                }
            }
        }
    }
}

// ===== Refresh-all full-screen progress =====

/** Full-screen progress page for the Refresh-all chain. State is owned
 *  by AppViewModel so the run survives navigation; this composable is
 *  a thin observer. Back-gesture pops the screen but the work keeps
 *  going; re-entering the Refresh screen drops the user back here
 *  while the run is in flight or freshly finished. */
@Composable
private fun RefreshAllProgressScreen(
    state: RefreshAllState,
    failedProviders: List<AppService>,
    onOpenProvider: (AppService) -> Unit,
    onNavigateToHelpTopic: (String) -> Unit,
    onBack: () -> Unit,
    /** Non-null once the run is finished — renders the "Restart
     *  application" banner at the top of the page. Tapping it calls
     *  the host's restart routine (which flushes usage stats first). */
    onRestart: (() -> Unit)? = null,
    restartMessage: String? = null
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "refresh_all", title = state.title, onBackClick = onBack)
        if (onRestart != null && restartMessage != null) {
            RestartAppBanner(message = restartMessage, onConfirm = onRestart)
        }
        val totalSteps = state.catalogSteps.size + state.workerRows.size
        val doneCatalogs = state.catalogSteps.count {
            it.status is RefreshStepStatus.Done || it.status is RefreshStepStatus.Skipped || it.status is RefreshStepStatus.Failed
        }
        val doneWorkers = state.workerRows.count {
            it.stage is WorkerStage.Done || it.stage is WorkerStage.Failed
        }
        val done = doneCatalogs + doneWorkers
        Text("Step $done / $totalSteps", fontSize = 12.sp, color = AppColors.TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (totalSteps == 0) 0f else done.toFloat() / totalSteps },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = AppColors.Orange,
            trackColor = AppColors.DividerDark
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Hide the catalogs section entirely on the worker-only
            // variant (Housekeeping → Refresh → "Providers / models /
            // default agents") so the user doesn't see a stray empty
            // header above the worker rows.
            if (state.catalogSteps.isNotEmpty()) {
                Text("Info providers", fontSize = 13.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            }
            state.catalogSteps.forEach { step ->
                val (icon, statusText, color) = when (val s = step.status) {
                    RefreshStepStatus.Pending -> Triple("⏳", "queued", AppColors.TextTertiary)
                    is RefreshStepStatus.Running -> Triple("▶", s.detail ?: "running", AppColors.Orange)
                    is RefreshStepStatus.Done -> Triple("✓", s.detail ?: "done", AppColors.Green)
                    is RefreshStepStatus.Failed -> Triple("✗", s.detail ?: "failed", AppColors.Red)
                    RefreshStepStatus.Skipped -> Triple("—", "skipped", AppColors.TextTertiary)
                }
                CatalogProgressRow(label = step.label, icon = icon, statusText = statusText, color = color, isPending = step.status is RefreshStepStatus.Pending)
            }

            if (state.workerRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Workers (per provider)", fontSize = 13.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                state.workerRows.forEach { row ->
                    val (icon, statusText, color, isPending) = when (val stage = row.stage) {
                        WorkerStage.Pending -> WorkerStageView("⏳", "queued", AppColors.TextTertiary, true)
                        WorkerStage.TestingKey -> WorkerStageView("▶", "testing key", AppColors.Orange, false)
                        WorkerStage.FetchingModels -> WorkerStageView("▶", "fetching models", AppColors.Orange, false)
                        WorkerStage.WritingAgent -> WorkerStageView("▶", "writing agent", AppColors.Orange, false)
                        WorkerStage.Done -> WorkerStageView("✓", "done", AppColors.Green, false)
                        is WorkerStage.Failed -> WorkerStageView("✗", stage.reason.take(60).ifBlank { "failed" }, AppColors.Red, false)
                    }
                    CatalogProgressRow(label = row.serviceId, icon = icon, statusText = statusText, color = color, isPending = isPending)
                }
            }

            if (state.overallError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Refresh aborted", fontSize = 13.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                        Text(state.overallError, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            // Failed-providers shortcut so the user can jump straight
            // into AI Setup → Providers → <name> to fix the API key /
            // model / endpoint and re-test.
            if (failedProviders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Failed provider tests (${failedProviders.size})",
                    fontSize = 13.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        failedProviders.forEach { svc ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onOpenProvider(svc) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("✗", fontSize = 14.sp, color = AppColors.Red, modifier = Modifier.width(20.dp))
                                Text(svc.id, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Text("Open ›", fontSize = 12.sp, color = AppColors.Blue)
                            }
                            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

private data class WorkerStageView(val icon: String, val statusText: String, val color: Color, val isPending: Boolean)

@Composable
private fun CatalogProgressRow(label: String, icon: String, statusText: String, color: Color, isPending: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPending) {
            com.ai.ui.shared.AnimatedHourglass(fontSize = 14.sp, modifier = Modifier.width(20.dp))
        } else {
            Text(icon, fontSize = 14.sp, modifier = Modifier.width(20.dp))
        }
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(statusText, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp))
    }
}

private data class RefreshResultRow(val label: String, val value: String, val color: Color)

@Composable
private fun RefreshResultScreen(
    titleText: String,
    description: String?,
    rows: List<RefreshResultRow>,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    sampleHeader: String? = null,
    sampleEntries: List<String> = emptyList()
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "refresh_result", title = titleText, onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!description.isNullOrBlank()) {
                Text(description, fontSize = 13.sp, color = AppColors.TextSecondary)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { r ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                            Text(r.value, fontSize = 13.sp, color = r.color, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (!sampleHeader.isNullOrBlank() && sampleEntries.isNotEmpty()) {
                Text(sampleHeader, fontSize = 13.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sampleEntries.forEach { key ->
                            Text(key, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
            Text("OK", maxLines = 1, softWrap = false)
        }
    }
}
