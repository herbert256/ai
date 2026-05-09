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
import kotlinx.coroutines.coroutineScope
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
    /** Navigate the parent settings screen to AI Setup → Providers →
     *  edit page for a specific provider. Used by the Refresh-all
     *  progress screen's "Failed providers" list so the user can jump
     *  straight to the provider whose key / model needs fixing. */
    onOpenProvider: (AppService) -> Unit = {},
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

    // Refresh-all chain — full-screen progress instead of the small
    // per-task popup. Lists every planned step up front and updates
    // each as work proceeds; the 6 catalog sources at the top run in
    // parallel via coroutineScope { ... awaitAll() }, then the
    // dependent provider/model/agent steps run sequentially.
    val refreshAllSteps = remember { mutableStateListOf<RefreshAllStep>() }
    var refreshAllInProgress by remember { mutableStateOf(false) }
    var refreshAllFinished by remember { mutableStateOf(false) }
    var refreshAllError by remember { mutableStateOf<String?>(null) }
    fun setStep(id: String, status: StepStatus) {
        val i = refreshAllSteps.indexOfFirst { it.id == id }
        if (i >= 0) refreshAllSteps[i] = refreshAllSteps[i].copy(status = status)
    }

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

    if (refreshAllInProgress) {
        // Resolve failed-provider rows back to AppService so the
        // "Failed providers" list can drive a direct nav to each
        // provider's edit page. The displayName lookup is safe
        // because the same AppService.entries iteration produced
        // the row in runProvidersWithProgress.
        val failedProviders = remember(refreshAllSteps.toList(), providerStateRows.toList()) {
            providerStateRows
                .filter { it.second == "error" }
                .mapNotNull { (name, _) -> AppService.entries.find { it.id == name } }
        }
        RefreshAllProgressScreen(
            steps = refreshAllSteps.toList(),
            overallError = refreshAllError,
            isFinished = refreshAllFinished,
            failedProviders = failedProviders,
            onOpenProvider = { svc ->
                // Tear down the in-progress overlay before navigating
                // so a back-press from the provider edit screen
                // doesn't drop the user back into a stale refresh-all
                // view that'll auto-clear on its next render anyway.
                refreshAllInProgress = false
                refreshAllSteps.clear()
                refreshAllError = null
                onOpenProvider(svc)
            },
            onRestartNow = {
                // Same flush-then-process-kill the legacy chain did at
                // the end. Captured here as an explicit user action so
                // they can review final step results first.
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                            SettingsPreferences(prefs, context.filesDir).flushUsageStats()
                        }
                    }
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launch?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    if (launch != null) context.startActivity(launch)
                    Runtime.getRuntime().exit(0)
                }
            },
            onBack = {
                // Don't allow leaving while work is in flight — keeps
                // the per-step status reachable for the user.
                if (refreshAllFinished) {
                    refreshAllInProgress = false
                    refreshAllSteps.clear()
                    refreshAllError = null
                }
            },
            onNavigateHome = onNavigateHome
        )
        return
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
        val seeds = AppService.entries.sortedBy { it.id }.map { service ->
            val state = aiSettings.getProviderState(service)
            val apiKey = aiSettings.getApiKey(service)
            when {
                state == "inactive" -> Seed(service, "inactive", null)
                apiKey.isBlank() -> Seed(service, "not-used", null)
                else -> Seed(service, null, aiSettings.getModel(service))
            }
        }
        providerStateRows.clear()
        providerStateRows.addAll(seeds.map { it.service.id to it.final })
        val testable = seeds.withIndex().filter { it.value.final == null }
        val total = testable.size
        // Avoid flashing an empty "0 / 0" dialog when every provider
        // is inactive or unkeyed — the supervisorScope below would
        // exit immediately and the dialog would close on its own,
        // but the flicker is worse than just skipping it.
        if (showProgressDialog && total > 0) showProviderStateDialog = true
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
                        if (idx < providerStateRows.size) providerStateRows[idx] = service.id to newState
                    }
                    onProviderStateChange(service, newState)
                    val done = completed.incrementAndGet()
                    progressText = "$done / $total — ${service.id}"
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
        generationRows.addAll(candidates.map { it.first.id to null })
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
                        if (idx < generationRows.size) generationRows[idx] = service.id to success
                    }
                    val done = completed.incrementAndGet()
                    progressText = "$done / $total — ${service.id}"
                    service.id to success
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) {
            var updatedSettings = aiSettings
            for ((service, _, model) in candidates) {
                val success = results.find { it.first == service.id }?.second == true
                if (success) {
                    val existing = updatedSettings.agents.find { it.name == service.id && it.provider.id == service.id }
                    if (existing == null) {
                        val agent = Agent(java.util.UUID.randomUUID().toString(), service.id, service, model, "")
                        updatedSettings = updatedSettings.copy(agents = updatedSettings.agents + agent)
                    }
                }
            }
            val defaultAgentIds = updatedSettings.agents.filter { a -> results.any { it.first == a.provider.id && it.second } }.map { it.id }
            // Skip flock creation when no agent passed — an empty
            // "Default agents" flock is just clutter the user has to
            // delete by hand.
            if (defaultAgentIds.isNotEmpty()) {
                val existingFlock = updatedSettings.flocks.find { it.name == com.ai.model.DEFAULT_AGENTS_FLOCK_NAME }
                updatedSettings = if (existingFlock != null) {
                    updatedSettings.copy(flocks = updatedSettings.flocks.map { if (it.id == existingFlock.id) it.copy(agentIds = defaultAgentIds) else it })
                } else {
                    val flock = Flock(java.util.UUID.randomUUID().toString(), com.ai.model.DEFAULT_AGENTS_FLOCK_NAME, defaultAgentIds)
                    updatedSettings.copy(flocks = updatedSettings.flocks + flock)
                }
            }
            onSave(updatedSettings)
        }
    }

    // Refresh-all variants: same work as runProviders / runDefaultAgents
    // but route progress through a callback (the full-screen progress
    // page's per-step detail) instead of mutating progressText (which
    // would no-op since the per-task popup is suppressed during the
    // refresh-all flow).
    val runProvidersWithProgress: suspend ((String) -> Unit) -> Unit = { onProgress ->
        data class Seed(val service: AppService, val final: String?, val testModel: String?)
        val seeds = AppService.entries.sortedBy { it.id }.map { service ->
            val state = aiSettings.getProviderState(service)
            val apiKey = aiSettings.getApiKey(service)
            when {
                state == "inactive" -> Seed(service, "inactive", null)
                apiKey.isBlank() -> Seed(service, "not-used", null)
                else -> Seed(service, null, aiSettings.getModel(service))
            }
        }
        providerStateRows.clear()
        providerStateRows.addAll(seeds.map { it.service.id to it.final })
        val testable = seeds.withIndex().filter { it.value.final == null }
        val total = testable.size
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress("0 / $total")
        supervisorScope {
            testable.map { (idx, seed) ->
                val service = seed.service
                val apiKey = aiSettings.getApiKey(service)
                val model = seed.testModel ?: ""
                async(Dispatchers.IO) {
                    val error = try { onTestApiKey(service, apiKey, model) } catch (e: Exception) { e.message ?: "error" }
                    val newState = if (error == null) "ok" else "error"
                    withContext(Dispatchers.Main) {
                        if (idx < providerStateRows.size) providerStateRows[idx] = service.id to newState
                    }
                    onProviderStateChange(service, newState)
                    val done = completed.incrementAndGet()
                    onProgress("$done / $total — ${service.id}")
                }
            }.awaitAll()
        }
    }
    /** Refresh-all variant of runDefaultAgents that takes the providers
     *  that already passed the preceding "Provider key tests" step
     *  instead of re-testing them. Two reasons:
     *    1. Re-testing every provider seconds after a successful test
     *       wastes a round-trip and quota per provider.
     *    2. The second test occasionally fails (rate-limit 429,
     *       transient network) on a provider that passed the first —
     *       producing the surface bug "N active providers but only
     *       N-1 agents in the default flock" where the user has no
     *       way to tell which one slipped. */
    val runDefaultAgentsFromPassed: suspend (List<AppService>, (String) -> Unit) -> Unit = { passedProviders, onProgress ->
        generationRows.clear()
        generationRows.addAll(passedProviders.map { it.id to true })
        onProgress("${passedProviders.size} / ${passedProviders.size} (already tested)")
        withContext(Dispatchers.IO) {
            var updatedSettings = aiSettings
            for (service in passedProviders) {
                val model = aiSettings.getModel(service)
                val existing = updatedSettings.agents.find { it.name == service.id && it.provider.id == service.id }
                if (existing == null) {
                    val agent = Agent(java.util.UUID.randomUUID().toString(), service.id, service, model, "")
                    updatedSettings = updatedSettings.copy(agents = updatedSettings.agents + agent)
                }
            }
            // Match the legacy behaviour: every agent whose provider's
            // displayName matches a passed provider goes into the
            // flock. Includes user-renamed agents pointing at a passed
            // provider (mirrors the prior runDefaultAgents semantics).
            val passedNames = passedProviders.map { it.id }.toSet()
            val defaultAgentIds = updatedSettings.agents
                .filter { it.provider.id in passedNames }
                .map { it.id }
            if (defaultAgentIds.isNotEmpty()) {
                val existingFlock = updatedSettings.flocks.find { it.name == com.ai.model.DEFAULT_AGENTS_FLOCK_NAME }
                updatedSettings = if (existingFlock != null) {
                    updatedSettings.copy(flocks = updatedSettings.flocks.map { if (it.id == existingFlock.id) it.copy(agentIds = defaultAgentIds) else it })
                } else {
                    val flock = Flock(java.util.UUID.randomUUID().toString(), com.ai.model.DEFAULT_AGENTS_FLOCK_NAME, defaultAgentIds)
                    updatedSettings.copy(flocks = updatedSettings.flocks + flock)
                }
            }
            onSave(updatedSettings)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "refresh", title = "Refresh", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Refresh all — runs catalogs first (six in parallel) so
            // the per-provider Models fetch and the Providers /
            // Default-agents tests see fresh capability data, then the
            // dependent steps run sequentially. Status is rendered on
            // a full-screen progress page rather than the small
            // per-task popup the individual buttons below use.
            RefreshAction(
                label = "Refresh all",
                description = "Run all six catalog sources in parallel (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis), then provider keys, model lists, and default agents in sequence.",
                enabled = !isAnyRunning,
                onClick = {
                    // Build the planned step list up front so the user sees
                    // every action that's about to happen before kickoff.
                    refreshAllSteps.clear()
                    val openRouterEnabled = openRouterApiKey.isNotBlank()
                    val aaEnabled = artificialAnalysisApiKey.isNotBlank()
                    refreshAllSteps += RefreshAllStep("openrouter", "OpenRouter",
                        if (openRouterEnabled) StepStatus.Pending else StepStatus.Skipped)
                    refreshAllSteps += RefreshAllStep("litellm", "LiteLLM")
                    refreshAllSteps += RefreshAllStep("modelsdev", "models.dev")
                    refreshAllSteps += RefreshAllStep("helicone", "Helicone")
                    refreshAllSteps += RefreshAllStep("llmprices", "llm-prices.com")
                    refreshAllSteps += RefreshAllStep("aa", "Artificial Analysis",
                        if (aaEnabled) StepStatus.Pending else StepStatus.Skipped)
                    refreshAllSteps += RefreshAllStep("providers", "Provider key tests")
                    refreshAllSteps += RefreshAllStep("models", "Model lists")
                    refreshAllSteps += RefreshAllStep("agents", "Default agents")
                    refreshAllError = null
                    refreshAllFinished = false
                    refreshAllInProgress = true

                    scope.launch {
                        try {
                            // Six catalog sources in parallel — they're
                            // independent network fetches with no shared
                            // mutable state, so awaitAll() collapses
                            // wall-clock from sum-of-latencies to
                            // max-of-latencies.
                            coroutineScope {
                                val jobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()
                                if (openRouterEnabled) jobs += async(Dispatchers.IO) {
                                    setStep("openrouter", StepStatus.Running())
                                    try {
                                        val pricing = PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                                        if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, pricing)
                                        val specs = PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
                                        openRouterResult = Triple(pricing.size, specs?.first ?: 0, specs?.second ?: 0)
                                        setStep("openrouter", StepStatus.Done("${pricing.size} priced · ${specs?.first ?: 0} specs"))
                                    } catch (e: Exception) {
                                        setStep("openrouter", StepStatus.Failed(e.message?.take(80)))
                                    }
                                }
                                jobs += async(Dispatchers.IO) {
                                    setStep("litellm", StepStatus.Running())
                                    try {
                                        val n = PricingCache.fetchLiteLLMPricingOnline(context)
                                        litellmResult = n
                                        if (n != null && n > 0) setStep("litellm", StepStatus.Done("$n priced"))
                                        else setStep("litellm", StepStatus.Failed("no entries"))
                                    } catch (e: Exception) {
                                        setStep("litellm", StepStatus.Failed(e.message?.take(80)))
                                    }
                                }
                                jobs += async(Dispatchers.IO) {
                                    setStep("modelsdev", StepStatus.Running())
                                    try {
                                        val n = PricingCache.fetchModelsDevOnline(context)
                                        modelsDevResult = n
                                        if (n != null && n > 0) setStep("modelsdev", StepStatus.Done("$n priced"))
                                        else setStep("modelsdev", StepStatus.Failed("no entries"))
                                    } catch (e: Exception) {
                                        setStep("modelsdev", StepStatus.Failed(e.message?.take(80)))
                                    }
                                }
                                jobs += async(Dispatchers.IO) {
                                    setStep("helicone", StepStatus.Running())
                                    try {
                                        val n = PricingCache.fetchHeliconeOnline(context)
                                        heliconeResult = n
                                        if (n != null && n > 0) setStep("helicone", StepStatus.Done("$n entries"))
                                        else setStep("helicone", StepStatus.Failed("no entries"))
                                    } catch (e: Exception) {
                                        setStep("helicone", StepStatus.Failed(e.message?.take(80)))
                                    }
                                }
                                jobs += async(Dispatchers.IO) {
                                    setStep("llmprices", StepStatus.Running())
                                    try {
                                        val n = PricingCache.fetchLLMPricesOnline(context)
                                        llmPricesResult = n
                                        if (n != null && n > 0) setStep("llmprices", StepStatus.Done("$n entries"))
                                        else setStep("llmprices", StepStatus.Failed("no entries"))
                                    } catch (e: Exception) {
                                        setStep("llmprices", StepStatus.Failed(e.message?.take(80)))
                                    }
                                }
                                if (aaEnabled) jobs += async(Dispatchers.IO) {
                                    setStep("aa", StepStatus.Running())
                                    try {
                                        val n = PricingCache.fetchArtificialAnalysisOnline(context, artificialAnalysisApiKey)
                                        aaResult = n
                                        if (n != null && n > 0) setStep("aa", StepStatus.Done("$n entries"))
                                        else setStep("aa", StepStatus.Failed("no entries"))
                                    } catch (e: Exception) {
                                        setStep("aa", StepStatus.Failed(e.message?.take(80)))
                                    }
                                }
                                jobs.awaitAll()
                            }
                            // Catalog answers may have shifted — refresh the
                            // precomputed vision / web-search sets so list
                            // renders pick up the new state. Single onSave
                            // covers all the catalogs that ran.
                            withContext(Dispatchers.Main) { onSave(aiSettings.recomputeAllCapabilities()) }

                            // Sequential: provider key tests
                            setStep("providers", StepStatus.Running())
                            try {
                                runProvidersWithProgress { detail -> setStep("providers", StepStatus.Running(detail)) }
                                val okCount = providerStateRows.count { it.second == "ok" }
                                val errCount = providerStateRows.count { it.second == "error" }
                                setStep("providers", StepStatus.Done("$okCount ok · $errCount failed"))
                            } catch (e: Exception) {
                                setStep("providers", StepStatus.Failed(e.message?.take(80)))
                            }

                            // Sequential: model lists per provider
                            setStep("models", StepStatus.Running())
                            try {
                                refreshResults = onRefreshAllModels(aiSettings, true) { msg ->
                                    setStep("models", StepStatus.Running(msg))
                                }
                                val total = refreshResults?.size ?: 0
                                val ok = refreshResults?.count { it.value > 0 } ?: 0
                                setStep("models", StepStatus.Done("$ok / $total providers"))
                            } catch (e: Exception) {
                                setStep("models", StepStatus.Failed(e.message?.take(80)))
                            }

                            // Sequential: default-agent generation. Uses
                            // the providers that just passed the
                            // Provider-key-tests step instead of
                            // re-testing — eliminates the
                            // "N active providers but only N-1 agents in
                            // the default flock" surface bug where a
                            // transient re-test failure dropped one
                            // provider from the flock.
                            setStep("agents", StepStatus.Running())
                            try {
                                val passedProviders = providerStateRows
                                    .filter { it.second == "ok" }
                                    .mapNotNull { (name, _) -> AppService.entries.find { it.id == name } }
                                runDefaultAgentsFromPassed(passedProviders) { detail ->
                                    setStep("agents", StepStatus.Running(detail))
                                }
                                setStep("agents", StepStatus.Done("${passedProviders.size} agents"))
                            } catch (e: Exception) {
                                setStep("agents", StepStatus.Failed(e.message?.take(80)))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            refreshAllError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        } finally {
                            refreshAllFinished = true
                        }
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

// ===== Refresh-all full-screen progress =====

/** Status of a single step in the chained Refresh all run. */
private sealed class StepStatus {
    object Pending : StepStatus()
    data class Running(val detail: String? = null) : StepStatus()
    data class Done(val detail: String? = null) : StepStatus()
    data class Failed(val detail: String? = null) : StepStatus()
    object Skipped : StepStatus()
}

private data class RefreshAllStep(
    val id: String,
    val label: String,
    val status: StepStatus = StepStatus.Pending
)

/** Full-screen progress page used by the Refresh all chain. Lists
 *  every planned step up front with a status icon + sub-detail, so
 *  the user can see what's been done, what's running, and what's
 *  still queued without a modal popup blocking the screen. The
 *  Restart-app step at the bottom is gated on a button at the foot
 *  of the screen so the user can review final results before
 *  killing the process. */
@Composable
private fun RefreshAllProgressScreen(
    steps: List<RefreshAllStep>,
    overallError: String?,
    isFinished: Boolean,
    failedProviders: List<AppService>,
    onOpenProvider: (AppService) -> Unit,
    onRestartNow: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "refresh", title = "Refresh all", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        val done = steps.count { it.status is StepStatus.Done || it.status is StepStatus.Skipped || it.status is StepStatus.Failed }
        Text(
            "Step $done / ${steps.size}",
            fontSize = 12.sp, color = AppColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (steps.isEmpty()) 0f else done.toFloat() / steps.size },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = AppColors.Orange,
            trackColor = AppColors.DividerDark
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { step ->
                val (icon, statusText, color) = when (val s = step.status) {
                    StepStatus.Pending -> Triple("⏳", "queued", AppColors.TextTertiary)
                    is StepStatus.Running -> Triple("▶", s.detail ?: "running…", AppColors.Orange)
                    is StepStatus.Done -> Triple("✓", s.detail ?: "done", AppColors.Green)
                    is StepStatus.Failed -> Triple("✗", s.detail ?: "failed", AppColors.Red)
                    StepStatus.Skipped -> Triple("—", "skipped", AppColors.TextTertiary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(icon, fontSize = 14.sp, modifier = Modifier.width(20.dp))
                    Text(step.label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Text(statusText, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp))
                }
            }
            if (overallError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Refresh aborted", fontSize = 13.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                        Text(overallError, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            // Failed-providers shortcut — appears after the Provider
            // key tests step has run, so the user can jump straight
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
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRestartNow,
            enabled = isFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text(if (isFinished) "Restart app" else "Working…", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = onBack,
            enabled = isFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Back without restart", maxLines = 1, softWrap = false) }
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
        TitleBar(helpTopic = "refresh_result", title = titleText, onBackClick = onBack)
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
