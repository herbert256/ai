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
            catch (e: Exception) { taskError = e.message }
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
        AlertDialog(onDismissRequest = { showResultsDialog = false }, title = { Text("Model Refresh Results") },
            text = { Column {
                refreshResults!!.entries.sortedBy { it.key }.forEach { (name, count) ->
                    val color = if (count > 0) AppColors.Green else AppColors.Red
                    Text("$name: ${if (count > 0) "$count models" else "failed"}", fontSize = 13.sp, color = color)
                }
            }}, confirmButton = { TextButton(onClick = { showResultsDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showProviderStateDialog) {
        val doneCount = providerStateRows.count { it.second != null }
        val totalCount = providerStateRows.size
        AlertDialog(
            onDismissRequest = { showProviderStateDialog = false },
            title = { Text(if (totalCount > 0 && doneCount < totalCount) "Provider State Results — $doneCount / $totalCount" else "Provider State Results") },
            text = { Column {
                providerStateRows.forEach { (name, state) ->
                    val (text, color) = when (state) {
                        null -> "pending" to AppColors.TextTertiary
                        "ok" -> "ok" to AppColors.Green
                        "error" -> "error" to AppColors.Red
                        else -> state to AppColors.TextTertiary
                    }
                    Text("$name: $text", fontSize = 13.sp, color = color)
                }
            }},
            confirmButton = { TextButton(onClick = { showProviderStateDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } }
        )
    }

    if (showOpenRouterDialog && openRouterResult != null) {
        val (pricing, specPricing, specParams) = openRouterResult!!
        AlertDialog(onDismissRequest = { showOpenRouterDialog = false }, title = { Text("OpenRouter Data") },
            text = { Text("Pricing: $pricing models\nSpec pricing: $specPricing\nSpec parameters: $specParams") },
            confirmButton = { TextButton(onClick = { showOpenRouterDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showLiteLLMDialog) {
        val n = litellmResult
        AlertDialog(onDismissRequest = { showLiteLLMDialog = false }, title = { Text("LiteLLM Pricing") },
            text = {
                Text(
                    if (n == null) "Failed to fetch from BerriAI/litellm GitHub. Check connectivity and try again."
                    else "Refreshed $n priced models from the litellm GitHub source."
                )
            },
            confirmButton = { TextButton(onClick = { showLiteLLMDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showModelsDevDialog) {
        val n = modelsDevResult
        AlertDialog(onDismissRequest = { showModelsDevDialog = false }, title = { Text("models.dev") },
            text = {
                Text(
                    if (n == null) "Failed to fetch from models.dev. Check connectivity and try again."
                    else "Refreshed $n priced models from the models.dev catalog. Used as a LiteLLM fallback."
                )
            },
            confirmButton = { TextButton(onClick = { showModelsDevDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showHeliconeDialog) {
        val n = heliconeResult
        AlertDialog(onDismissRequest = { showHeliconeDialog = false }, title = { Text("Helicone") },
            text = {
                Text(
                    if (n == null) "Failed to fetch from Helicone. Check connectivity and try again."
                    else "Loaded $n Helicone entries (exact + pattern). Used as a pricing fallback."
                )
            },
            confirmButton = { TextButton(onClick = { showHeliconeDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showLLMPricesDialog) {
        val n = llmPricesResult
        AlertDialog(onDismissRequest = { showLLMPricesDialog = false }, title = { Text("llm-prices.com") },
            text = {
                Text(
                    if (n == null) "Failed to fetch from simonw/llm-prices. Check connectivity and try again."
                    else "Loaded $n curated entries across 10 vendors from llm-prices.com."
                )
            },
            confirmButton = { TextButton(onClick = { showLLMPricesDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showAaDialog) {
        val n = aaResult
        AlertDialog(onDismissRequest = { showAaDialog = false }, title = { Text("Artificial Analysis") },
            text = {
                Text(
                    when {
                        n == null && artificialAnalysisApiKey.isBlank() -> "Add the Artificial Analysis API key under External Services first."
                        n == null -> "Failed to fetch from Artificial Analysis. Check the API key and try again."
                        else -> "Loaded $n entries (pricing + intelligence/speed scores) from Artificial Analysis."
                    }
                )
            },
            confirmButton = { TextButton(onClick = { showAaDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showGenerationDialog) {
        val doneCount = generationRows.count { it.second != null }
        val totalCount = generationRows.size
        AlertDialog(
            onDismissRequest = { showGenerationDialog = false },
            title = { Text(if (totalCount > 0 && doneCount < totalCount) "Default Agent Generation — $doneCount / $totalCount" else "Default Agent Generation") },
            text = {
                Column {
                    generationRows.forEach { (name, result) ->
                        val (text, color) = when (result) {
                            null  -> "pending" to AppColors.TextTertiary
                            true  -> "OK" to AppColors.Green
                            false -> "failed" to AppColors.Red
                        }
                        Text("$name: $text", fontSize = 13.sp, color = color)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGenerationDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } }
        )
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
        TitleBar(title = "Refresh", onBackClick = onBack, onAiClick = onNavigateHome)
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
