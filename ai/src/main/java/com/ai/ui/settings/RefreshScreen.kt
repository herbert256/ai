package com.ai.ui.settings

import android.content.Intent
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
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@Composable
fun RefreshScreen(
    aiSettings: Settings,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onSave: (Settings) -> Unit,
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int>,
    onTestApiKey: suspend (AppService, String, String) -> String?,
    onProviderStateChange: (AppService, String) -> Unit,
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

    var refreshResults by remember { mutableStateOf<Map<String, Int>?>(null) }
    var openRouterResult by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var litellmResult by remember { mutableStateOf<Int?>(null) }
    var modelsDevResult by remember { mutableStateOf<Int?>(null) }
    var heliconeResult by remember { mutableStateOf<Int?>(null) }
    var llmPricesResult by remember { mutableStateOf<Int?>(null) }
    var aaResult by remember { mutableStateOf<Int?>(null) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var showProviderStateDialog by remember { mutableStateOf(false) }
    var showOpenRouterDialog by remember { mutableStateOf(false) }
    var showLiteLLMDialog by remember { mutableStateOf(false) }
    var showModelsDevDialog by remember { mutableStateOf(false) }
    var showHeliconeDialog by remember { mutableStateOf(false) }
    var showLLMPricesDialog by remember { mutableStateOf(false) }
    var showAaDialog by remember { mutableStateOf(false) }
    // (providerName, state) where state == null means "pending" (still being tested);
    // any other string is the final state ("ok"/"error"/"inactive"/"not-used").
    val providerStateRows = remember { mutableStateListOf<Pair<String, String?>>() }
    // Ordered list of (providerName, result) where result: null = pending, true = ok, false = failed.
    // SnapshotStateList of Pair so the dialog recomposes on each incremental update.
    val generationRows = remember { mutableStateListOf<Pair<String, Boolean?>>() }
    var showGenerationDialog by remember { mutableStateOf(false) }

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

    if (showResultsDialog && refreshResults != null) {
        val rows = refreshResults!!.entries.sortedBy { it.key }.map { (name, count) ->
            RefreshResultRow(
                label = name,
                value = if (count > 0) "$count models" else "failed",
                color = if (count > 0) AppColors.Green else AppColors.Red
            )
        }
        val total = rows.size
        val ok = rows.count { it.color == AppColors.Green }
        RefreshResultScreen(
            titleText = "Model Refresh Results",
            description = "$ok of $total providers returned a model list. Failed providers usually mean a missing key, an inactive state, or a transient network error.",
            rows = rows,
            onBack = { showResultsDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showProviderStateDialog) {
        val doneCount = providerStateRows.count { it.second != null }
        val totalCount = providerStateRows.size
        val rows = providerStateRows.map { (name, state) ->
            val (text, color) = when (state) {
                null -> "pending" to AppColors.TextTertiary
                "ok" -> "ok" to AppColors.Green
                "error" -> "error" to AppColors.Red
                else -> state to AppColors.TextTertiary
            }
            RefreshResultRow(name, text, color)
        }
        RefreshResultScreen(
            titleText = if (totalCount > 0 && doneCount < totalCount) "Provider State — $doneCount / $totalCount" else "Provider State Results",
            description = "Each provider's saved API key was tested with a small live call. Inactive and unkeyed providers are skipped without testing.",
            rows = rows,
            onBack = { showProviderStateDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showOpenRouterDialog && openRouterResult != null) {
        val (pricing, specPricing, specParams) = openRouterResult!!
        val ok = pricing > 0 || specPricing > 0
        RefreshResultScreen(
            titleText = "OpenRouter",
            description = "Pulled the OpenRouter catalog. Pricing entries feed the OpenRouter tier; the spec rows feed model capability flags and the supported-parameters list used by the chat UI.",
            rows = listOf(
                RefreshResultRow("Pricing entries", "$pricing models", if (pricing > 0) AppColors.Green else AppColors.Red),
                RefreshResultRow("Spec pricing", "$specPricing", if (specPricing > 0) AppColors.Green else AppColors.TextTertiary),
                RefreshResultRow("Spec parameters", "$specParams", if (specParams > 0) AppColors.Green else AppColors.TextTertiary),
                RefreshResultRow("Cache age", PricingCache.getOpenRouterCacheAge(context), AppColors.TextTertiary)
            ),
            sampleHeader = if (ok) "Sample model entries" else null,
            sampleEntries = if (ok) PricingCache.getOpenRouterPricing(context).keys.sorted().take(8) else emptyList(),
            onBack = { showOpenRouterDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showLiteLLMDialog) {
        val n = litellmResult
        val ok = n != null && n > 0
        RefreshResultScreen(
            titleText = "LiteLLM Pricing",
            description = if (n == null) "Failed to fetch model_prices_and_context_window.json from BerriAI/litellm. Check connectivity and try again."
            else "Downloaded model_prices_and_context_window.json. LiteLLM is the primary tier in the layered pricing lookup — also feeds capability flags (vision, web search, etc.).",
            rows = listOf(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Priced models", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary)
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
        RefreshResultScreen(
            titleText = "models.dev",
            description = if (n == null) "Failed to fetch from models.dev. Check connectivity and try again."
            else "Pulled the models.dev community catalog. Sits below LiteLLM in the layered pricing lookup — fills gaps for newer models and -latest aliases that haven't reached LiteLLM yet.",
            rows = listOf(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Priced models", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary)
            ),
            onBack = { showModelsDevDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showHeliconeDialog) {
        val n = heliconeResult
        val ok = n != null && n > 0
        RefreshResultScreen(
            titleText = "Helicone",
            description = if (n == null) "Failed to fetch from helicone.ai/api/llm-costs. Check connectivity and try again."
            else "Pulled the Helicone pricing aggregator. Pricing-only fallback — sits after LiteLLM and models.dev in the layered lookup, before llm-prices.",
            rows = listOf(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Entries", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary)
            ),
            onBack = { showHeliconeDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showLLMPricesDialog) {
        val n = llmPricesResult
        val ok = n != null && n > 0
        RefreshResultScreen(
            titleText = "llm-prices.com",
            description = if (n == null) "Failed to fetch from simonw/llm-prices. Check connectivity and try again."
            else "Pulled Simon Willison's curated per-vendor pricing tables. Useful as a tiebreaker for the major commercial providers.",
            rows = listOf(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Entries", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary),
                RefreshResultRow("Vendors", "10", AppColors.TextTertiary)
            ),
            onBack = { showLLMPricesDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showAaDialog) {
        val n = aaResult
        val ok = n != null && n > 0
        val description = when {
            n == null && artificialAnalysisApiKey.isBlank() -> "Add the Artificial Analysis API key under External Services first."
            n == null -> "Failed to fetch from artificialanalysis.ai/api/v2/data/llms/models. Check the API key and try again."
            else -> "Pulled Artificial Analysis (pricing + intelligence_index + output speed). Bottom-of-stack pricing fallback before manual override and DEFAULT."
        }
        RefreshResultScreen(
            titleText = "Artificial Analysis",
            description = description,
            rows = listOf(
                RefreshResultRow(
                    "Status", if (n == null) "failed" else "loaded",
                    if (n == null) AppColors.Red else AppColors.Green
                ),
                RefreshResultRow("Entries", "${n ?: 0}", if (ok) AppColors.Green else AppColors.TextTertiary)
            ),
            onBack = { showAaDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    if (showGenerationDialog) {
        val doneCount = generationRows.count { it.second != null }
        val totalCount = generationRows.size
        val rows = generationRows.map { (name, result) ->
            val (text, color) = when (result) {
                null  -> "pending" to AppColors.TextTertiary
                true  -> "OK" to AppColors.Green
                false -> "failed" to AppColors.Red
            }
            RefreshResultRow(name, text, color)
        }
        RefreshResultScreen(
            titleText = if (totalCount > 0 && doneCount < totalCount) "Default Agents — $doneCount / $totalCount" else "Default Agent Generation",
            description = "Each active provider was probed with its current default model. On success the provider gets a default agent (if missing) and joins the \"default agents\" flock.",
            rows = rows,
            onBack = { showGenerationDialog = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Each refresh's core work lives in a suspend lambda that captures the
    // surrounding state — keeps individual buttons and the "Refresh all"
    // chain pointing at the same code path. The Boolean parameter controls
    // whether the per-step result dialog opens at the end (true for the
    // dedicated button, false during a chained Refresh all).
    val runOpenRouter: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Pulling OpenRouter catalog…"
        withContext(Dispatchers.IO) {
            val pricing = PricingCache.fetchOpenRouterPricing(openRouterApiKey)
            if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, pricing)
            val specs = PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
            openRouterResult = Triple(pricing.size, specs?.first ?: 0, specs?.second ?: 0)
        }
        if (showDialogAtEnd) showOpenRouterDialog = true
    }
    val runLiteLLM: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading model_prices_and_context_window.json…"
        val n = PricingCache.fetchLiteLLMPricingOnline(context)
        litellmResult = n
        // Catalog answers may have shifted — refresh the precomputed
        // vision / web-search sets so list renders pick up the new state.
        if (n != null) onSave(aiSettings.recomputeAllCapabilities())
        if (showDialogAtEnd) showLiteLLMDialog = true
    }
    val runModelsDev: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading models.dev/api.json…"
        val n = PricingCache.fetchModelsDevOnline(context)
        modelsDevResult = n
        if (n != null) onSave(aiSettings.recomputeAllCapabilities())
        if (showDialogAtEnd) showModelsDevDialog = true
    }
    val runHelicone: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading helicone.ai/api/llm-costs…"
        val n = PricingCache.fetchHeliconeOnline(context)
        heliconeResult = n
        // Helicone is pricing-only — no capability flags, so no recompute.
        if (showDialogAtEnd) showHeliconeDialog = true
    }
    val runLLMPrices: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading simonw/llm-prices vendor files…"
        val n = PricingCache.fetchLLMPricesOnline(context)
        llmPricesResult = n
        if (showDialogAtEnd) showLLMPricesDialog = true
    }
    val runArtificialAnalysis: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        progressText = "Downloading artificialanalysis.ai/api/v2/data/llms/models…"
        val n = PricingCache.fetchArtificialAnalysisOnline(context, artificialAnalysisApiKey)
        aaResult = n
        if (showDialogAtEnd) showAaDialog = true
    }
    val runProviders: suspend (Boolean) -> Unit = { showProgressDialog ->
        data class Seed(val service: AppService, val final: String?, val testModel: String?)
        val seeds = AppService.entries.sortedBy { it.displayName }.map { service ->
            val state = aiSettings.getProviderState(service)
            val apiKey = aiSettings.getApiKey(service)
            when {
                state == "inactive" -> Seed(service, "inactive", null)
                apiKey.isBlank() -> Seed(service, "not-used", null)
                else -> Seed(service, null, aiSettings.getModel(service))
            }
        }
        providerStateRows.clear()
        providerStateRows.addAll(seeds.map { it.service.displayName to it.final })
        if (showProgressDialog) showProviderStateDialog = true
        val testable = seeds.withIndex().filter { it.value.final == null }
        val total = testable.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        progressText = "0 / $total"
        supervisorScope {
            testable.map { (idx, seed) ->
                val service = seed.service
                val apiKey = aiSettings.getApiKey(service)
                val model = seed.testModel ?: ""
                async(Dispatchers.IO) {
                    val error = try { onTestApiKey(service, apiKey, model) } catch (e: Exception) { e.message ?: "error" }
                    val newState = if (error == null) "ok" else "error"
                    withContext(Dispatchers.Main) {
                        if (idx < providerStateRows.size) providerStateRows[idx] = service.displayName to newState
                    }
                    onProviderStateChange(service, newState)
                    val done = completed.incrementAndGet()
                    progressText = "$done / $total — ${service.displayName}"
                }
            }.awaitAll()
        }
    }
    val runModels: suspend (Boolean) -> Unit = { showDialogAtEnd ->
        refreshResults = onRefreshAllModels(aiSettings, true) { msg: String -> progressText = msg }
        if (showDialogAtEnd) showResultsDialog = true
    }
    val runDefaultAgents: suspend (Boolean) -> Unit = { showProgressDialog ->
        val candidates = aiSettings.getActiveServices().map { s ->
            Triple(s, aiSettings.getApiKey(s), aiSettings.getModel(s))
        }
        generationRows.clear()
        generationRows.addAll(candidates.map { it.first.displayName to null })
        if (showProgressDialog) showGenerationDialog = true
        val total = candidates.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        progressText = "0 / $total"
        val results: List<Pair<String, Boolean>> = supervisorScope {
            candidates.mapIndexed { idx, (service, key, model) ->
                async(Dispatchers.IO) {
                    val error = try { onTestApiKey(service, key, model) } catch (e: Exception) { e.message ?: "error" }
                    val success = error == null
                    withContext(Dispatchers.Main) {
                        if (idx < generationRows.size) generationRows[idx] = service.displayName to success
                    }
                    val done = completed.incrementAndGet()
                    progressText = "$done / $total — ${service.displayName}"
                    service.displayName to success
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) {
            var updatedSettings = aiSettings
            for ((service, _, model) in candidates) {
                val success = results.find { it.first == service.displayName }?.second == true
                if (success) {
                    val existing = updatedSettings.agents.find { it.name == service.displayName && it.provider.id == service.id }
                    if (existing == null) {
                        val agent = Agent(java.util.UUID.randomUUID().toString(), service.displayName, service, model, "")
                        updatedSettings = updatedSettings.copy(agents = updatedSettings.agents + agent)
                    }
                }
            }
            val defaultAgentIds = updatedSettings.agents.filter { a -> results.any { it.first == a.provider.displayName && it.second } }.map { it.id }
            val existingFlock = updatedSettings.flocks.find { it.name == com.ai.model.DEFAULT_AGENTS_FLOCK_NAME }
            updatedSettings = if (existingFlock != null) {
                updatedSettings.copy(flocks = updatedSettings.flocks.map { if (it.id == existingFlock.id) it.copy(agentIds = defaultAgentIds) else it })
            } else {
                val flock = Flock(java.util.UUID.randomUUID().toString(), com.ai.model.DEFAULT_AGENTS_FLOCK_NAME, defaultAgentIds)
                updatedSettings.copy(flocks = updatedSettings.flocks + flock)
            }
            onSave(updatedSettings)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "refresh", title = "Refresh", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Refresh all — runs the catalogs first (OpenRouter, LiteLLM,
            // models.dev) so the per-provider Models fetch and the
            // Providers / Default-agents tests see fresh capability data.
            RefreshAction(
                label = "Refresh all",
                description = "Run every refresh below in sequence: catalogs first (OpenRouter, LiteLLM, models.dev), then provider keys, model lists, and default agents.",
                enabled = !isAnyRunning,
                onClick = {
                    launchTask("Refresh all") {
                        if (openRouterApiKey.isNotBlank()) runOpenRouter(false)
                        runLiteLLM(false)
                        runModelsDev(false)
                        runHelicone(false)
                        runLLMPrices(false)
                        if (artificialAnalysisApiKey.isNotBlank()) runArtificialAnalysis(false)
                        runProviders(false)
                        runModels(false)
                        runDefaultAgents(false)
                        // Auto-restart so the freshly-loaded catalogs and
                        // recomputed precomputed sets are picked up cleanly
                        // — saves the user from a manual kill/relaunch.
                        progressText = "Flushing caches…"
                        // Flush every in-memory cache that defers writes
                        // synchronously before we kill the process. The
                        // usage-stats cache debounces flushes to a 2-second
                        // window — without an explicit flush here, every
                        // unsaved counter increment from this Refresh All
                        // run gets dropped.
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                SettingsPreferences(prefs, context.filesDir).flushUsageStats()
                            }
                        }
                        progressText = "Restarting…"
                        kotlinx.coroutines.delay(400)
                        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        launch?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        if (launch != null) context.startActivity(launch)
                        Runtime.getRuntime().exit(0)
                    }
                }
            )

            // Catalog refreshes (top group — they're prerequisites for the
            // model-list and capability-derivation work below).
            RefreshAction(
                label = "OpenRouter",
                description = "Pull OpenRouter's catalog (pricing, capability flags, supported parameters). Needs the OpenRouter External Services key.",
                enabled = !isAnyRunning && openRouterApiKey.isNotBlank(),
                onClick = { launchTask("Refreshing OpenRouter") { runOpenRouter(true) } }
            )
            RefreshAction(
                label = "LiteLLM",
                description = "Download model_prices_and_context_window.json from BerriAI/litellm — the primary source for pricing and capability flags.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Refreshing LiteLLM") { runLiteLLM(true) } }
            )
            RefreshAction(
                label = "models.dev",
                description = "Pull the models.dev community catalog. Acts as a LiteLLM fallback for newer models / -latest aliases LiteLLM hasn't picked up yet.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Refreshing models.dev") { runModelsDev(true) } }
            )
            RefreshAction(
                label = "Helicone",
                description = "Pull Helicone's pricing aggregator (helicone.ai/api/llm-costs). Pricing-only fallback after LiteLLM and models.dev.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Refreshing Helicone") { runHelicone(true) } }
            )
            RefreshAction(
                label = "llm-prices.com",
                description = "Pull Simon Willison's curated per-vendor pricing tables (10 vendors). Useful as a tiebreaker on the major commercial providers.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Refreshing llm-prices.com") { runLLMPrices(true) } }
            )
            RefreshAction(
                label = "Artificial Analysis",
                description = "Pull Artificial Analysis (pricing + intelligence_index + output speed). Needs the API key under External Services.",
                enabled = !isAnyRunning && artificialAnalysisApiKey.isNotBlank(),
                onClick = { launchTask("Refreshing Artificial Analysis") { runArtificialAnalysis(true) } }
            )

            // Per-provider work (depends on the catalogs above).
            RefreshAction(
                label = "Providers",
                description = "Test the saved API key for every provider against a small live model call. Marks each as ok / error / inactive / not-used.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Testing Providers") { runProviders(true) } }
            )
            RefreshAction(
                label = "Models",
                description = "Fetch the latest model list from every active provider's /models endpoint. Replaces the cached lists used by the model pickers.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Refreshing Models") { runModels(true) } }
            )
            RefreshAction(
                label = "Default agents",
                description = "Create a default agent per active provider (using its current default model) and a \"default agents\" flock that includes them.",
                enabled = !isAnyRunning,
                onClick = { launchTask("Generating Agents") { runDefaultAgents(true) } }
            )

        }
    }
}

@Composable
private fun RefreshAction(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Text(description, fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.fillMaxWidth())
        }
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
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "refresh", title = titleText, onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))
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
