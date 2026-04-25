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
    var providerStateResults by remember { mutableStateOf<Map<String, String>?>(null) }
    var openRouterResult by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var showProviderStateDialog by remember { mutableStateOf(false) }
    var showOpenRouterDialog by remember { mutableStateOf(false) }
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

    if (showProviderStateDialog && providerStateResults != null) {
        AlertDialog(onDismissRequest = { showProviderStateDialog = false }, title = { Text("Provider State Results") },
            text = { Column {
                providerStateResults!!.entries.sortedBy { it.key }.forEach { (name, state) ->
                    val color = when (state) { "ok" -> AppColors.Green; "error" -> AppColors.Red; else -> AppColors.TextTertiary }
                    Text("$name: $state", fontSize = 13.sp, color = color)
                }
            }}, confirmButton = { TextButton(onClick = { showProviderStateDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    if (showOpenRouterDialog && openRouterResult != null) {
        val (pricing, specPricing, specParams) = openRouterResult!!
        AlertDialog(onDismissRequest = { showOpenRouterDialog = false }, title = { Text("OpenRouter Data") },
            text = { Text("Pricing: $pricing models\nSpec pricing: $specPricing\nSpec parameters: $specParams") },
            confirmButton = { TextButton(onClick = { showOpenRouterDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } })
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Refresh", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Refresh", fontWeight = FontWeight.Bold, color = Color.White)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            launchTask("Testing Providers") {
                                withContext(Dispatchers.IO) {
                                    val results = mutableMapOf<String, String>()
                                    for (service in AppService.entries) {
                                        val state = aiSettings.getProviderState(service)
                                        if (state == "inactive") { results[service.displayName] = "inactive"; continue }
                                        val apiKey = aiSettings.getApiKey(service)
                                        if (apiKey.isBlank()) { results[service.displayName] = "not-used"; continue }
                                        progressText = service.displayName
                                        val error = onTestApiKey(service, apiKey, aiSettings.getModel(service))
                                        val newState = if (error == null) "ok" else "error"
                                        results[service.displayName] = newState
                                        onProviderStateChange(service, newState)
                                    }
                                    providerStateResults = results
                                }
                                showProviderStateDialog = true
                            }
                        }, enabled = !isAnyRunning, modifier = Modifier.weight(1f)) { Text("Providers", fontSize = 12.sp, maxLines = 1, softWrap = false) }

                        OutlinedButton(onClick = {
                            launchTask("Refreshing Models") {
                                refreshResults = onRefreshAllModels(aiSettings, true) { msg: String -> progressText = msg }
                                showResultsDialog = true
                            }
                        }, enabled = !isAnyRunning, modifier = Modifier.weight(1f)) { Text("Models", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            launchTask("Refreshing OpenRouter") {
                                withContext(Dispatchers.IO) {
                                    val pricing = PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                                    if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, pricing)
                                    val specs = PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
                                    openRouterResult = Triple(pricing.size, specs?.first ?: 0, specs?.second ?: 0)
                                }
                                showOpenRouterDialog = true
                            }
                        }, enabled = !isAnyRunning && openRouterApiKey.isNotBlank(), modifier = Modifier.weight(1f)) { Text("OpenRouter", fontSize = 12.sp, maxLines = 1, softWrap = false) }

                        OutlinedButton(onClick = {
                            // Only test providers whose API key is set — skip the rest immediately.
                            val candidates = AppService.entries.mapNotNull { s ->
                                val key = aiSettings.getApiKey(s)
                                if (key.isBlank()) null else Triple(s, key, aiSettings.getModel(s))
                            }
                            // Seed the dialog with every candidate in "pending" state and open it right away
                            // so the user can see the work as it happens.
                            generationRows.clear()
                            generationRows.addAll(candidates.map { it.first.displayName to null })
                            showGenerationDialog = true

                            launchTask("Generating Agents") {
                                val total = candidates.size
                                val completed = java.util.concurrent.atomic.AtomicInteger(0)
                                progressText = "0 / $total"

                                // Fan out all tests in parallel; supervisorScope keeps one failure from
                                // cancelling the other in-flight checks.
                                val results: List<Pair<String, Boolean>> = supervisorScope {
                                    candidates.mapIndexed { idx, (service, key, model) ->
                                        async(Dispatchers.IO) {
                                            val error = try { onTestApiKey(service, key, model) } catch (e: Exception) { e.message ?: "error" }
                                            val success = error == null
                                            // Update the row for THIS service so the dialog recomposes immediately.
                                            withContext(Dispatchers.Main) {
                                                if (idx < generationRows.size) {
                                                    generationRows[idx] = service.displayName to success
                                                }
                                            }
                                            val done = completed.incrementAndGet()
                                            progressText = "$done / $total — ${service.displayName}"
                                            service.displayName to success
                                        }
                                    }.awaitAll()
                                }

                                withContext(Dispatchers.IO) {
                                    // Build the updated Settings sequentially (cheap, no network).
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
                                    // Create "default agents" flock
                                    val defaultAgentIds = updatedSettings.agents.filter { a -> results.any { it.first == a.provider.displayName && it.second } }.map { it.id }
                                    val existingFlock = updatedSettings.flocks.find { it.name == "default agents" }
                                    updatedSettings = if (existingFlock != null) {
                                        updatedSettings.copy(flocks = updatedSettings.flocks.map { if (it.id == existingFlock.id) it.copy(agentIds = defaultAgentIds) else it })
                                    } else {
                                        val flock = Flock(java.util.UUID.randomUUID().toString(), "default agents", defaultAgentIds)
                                        updatedSettings.copy(flocks = updatedSettings.flocks + flock)
                                    }
                                    onSave(updatedSettings)
                                }
                            }
                        }, enabled = !isAnyRunning, modifier = Modifier.weight(1f)) { Text("Default agents", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}
