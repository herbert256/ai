package com.ai.ui.admin

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ai.data.*
import com.ai.model.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.settings.exportAiConfig
import com.ai.ui.settings.importAiConfigFromFile
import com.ai.ui.shared.*
import com.ai.viewmodel.GeneralSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@Composable
fun HousekeepingScreen(
    aiSettings: Settings,
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int>,
    onTestApiKey: suspend (AppService, String, String) -> String?,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onProviderStateChange: (AppService, String) -> Unit
) {
    BackHandler { onBackToHome() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var progressTitle by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    val isAnyRunning by remember { derivedStateOf { progressTitle.isNotBlank() } }
    var taskError by remember { mutableStateOf<String?>(null) }

    // Result states
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

    // File import/export
    var importType by remember { mutableStateOf("config") }

    fun writeToUri(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
    }

    fun readFromUri(uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val settingsPrefs = SettingsPreferences(prefs, context.filesDir)
        val gs = settingsPrefs.loadGeneralSettings()
        val json = exportAiConfig(context, aiSettings, gs)
        writeToUri(uri, json)
        Toast.makeText(context, "Configuration exported", Toast.LENGTH_SHORT).show()
    }

    val exportKeysLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val keys = mutableMapOf<String, String>()
        for (service in AppService.entries) {
            val apiKey = aiSettings.getApiKey(service)
            if (apiKey.isNotBlank()) keys[service.id] = apiKey
        }
        // External-services keys use an "EXT_" prefix so they can't collide with provider IDs
        // (note: "HUGGINGFACE" is also an AppService id — the old key name was ambiguous).
        if (huggingFaceApiKey.isNotBlank()) keys["EXT_HUGGINGFACE"] = huggingFaceApiKey
        if (openRouterApiKey.isNotBlank()) keys["EXT_OPENROUTER"] = openRouterApiKey
        writeToUri(uri, createAppGson(prettyPrint = true).toJson(keys))
        Toast.makeText(context, "${keys.size} API keys exported", Toast.LENGTH_SHORT).show()
    }

    val exportCostsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val manual = PricingCache.getAllManualPricing(context)
        val lines = mutableListOf("provider,model,input_per_million,output_per_million")
        manual.forEach { (key, pricing) ->
            val parts = key.split(":", limit = 2)
            lines.add("${parts[0]},${parts.getOrElse(1) { "" }},${pricing.promptPrice * 1_000_000},${pricing.completionPrice * 1_000_000}")
        }
        writeToUri(uri, lines.joinToString("\n"))
        Toast.makeText(context, "${manual.size} cost entries exported", Toast.LENGTH_SHORT).show()
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (importType) {
            "config" -> {
                val result = importAiConfigFromFile(context, uri, aiSettings)
                if (result != null) {
                    onSave(result.aiSettings)
                    result.huggingFaceApiKey?.let { onSaveHuggingFaceApiKey(it) }
                    result.openRouterApiKey?.let { onSaveOpenRouterApiKey(it) }
                }
            }
            "keys" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                try {
                    // Parse as JsonObject so we can be robust about value types and detect the
                    // common mistake of picking a full config JSON in the "API Keys" button.
                    @Suppress("DEPRECATION")
                    val root = JsonParser().parse(json)
                    if (!root.isJsonObject) {
                        Toast.makeText(context, "Expected a JSON object like {\"OPENAI\": \"sk-...\"}", Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    val obj: JsonObject = root.asJsonObject
                    if (obj.has("version") && obj.has("providers")) {
                        Toast.makeText(context, "This looks like a full config — use the Config import button instead.", Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    var updated = aiSettings; var count = 0; var skipped = 0
                    for (entry in obj.entrySet()) {
                        val id = entry.key
                        val valueEl = entry.value
                        val prim = if (valueEl != null && valueEl.isJsonPrimitive) valueEl.asJsonPrimitive else null
                        val key = if (prim != null && prim.isString) prim.asString else { skipped++; continue }
                        if (key.isBlank()) { skipped++; continue }
                        when (id) {
                            // New canonical names.
                            "EXT_HUGGINGFACE" -> { onSaveHuggingFaceApiKey(key); count++ }
                            "EXT_OPENROUTER" -> { onSaveOpenRouterApiKey(key); count++ }
                            // Legacy aliases kept for backward-compatible imports of older keys.json files.
                            "HUGGINGFACE" -> { onSaveHuggingFaceApiKey(key); count++ }
                            "OPENROUTER_KEY" -> { onSaveOpenRouterApiKey(key); count++ }
                            else -> {
                                val service = AppService.findById(id)
                                if (service != null) { updated = updated.withApiKey(service, key); count++ } else skipped++
                            }
                        }
                    }
                    onSave(updated)
                    val msg = "$count API keys imported" + if (skipped > 0) ", $skipped skipped" else ""
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } catch (e: JsonSyntaxException) {
                    android.util.Log.e("Housekeeping", "API keys import parse error", e)
                    Toast.makeText(context, "Not valid JSON", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("Housekeeping", "API keys import error", e)
                    Toast.makeText(context, "Import failed (${e.javaClass.simpleName}); see logcat", Toast.LENGTH_LONG).show()
                }
            }
            "costs" -> {
                val csv = readFromUri(uri)
                if (csv.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                var imported = 0; var skipped = 0
                csv.lines().drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        val provider = AppService.findById(parts[0].trim())
                        val model = parts[1].trim()
                        val inp = parts[2].trim().toDoubleOrNull()?.div(1_000_000)
                        val outp = parts[3].trim().toDoubleOrNull()?.div(1_000_000)
                        if (provider != null && model.isNotBlank() && inp != null && outp != null) {
                            PricingCache.setManualPricing(context, provider, model, inp, outp); imported++
                        } else skipped++
                    } else skipped++
                }
                Toast.makeText(context, "Imported $imported costs" + (if (skipped > 0) ", skipped $skipped" else ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchTask(title: String, initialText: String = "", block: suspend () -> Unit) {
        scope.launch {
            progressTitle = title; progressText = initialText
            try { block() }
            catch (e: Exception) { taskError = e.message }
            finally { progressTitle = ""; progressText = "" }
        }
    }

    // Progress dialog
    if (isAnyRunning) {
        AlertDialog(onDismissRequest = {}, title = { Text(progressTitle) },
            text = { Column {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                if (progressText.isNotBlank()) { Spacer(modifier = Modifier.height(8.dp)); Text(progressText, fontSize = 12.sp, color = AppColors.TextTertiary) }
            }}, confirmButton = {})
    }

    // Error dialog
    taskError?.let { error ->
        AlertDialog(onDismissRequest = { taskError = null }, title = { Text("Error") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { taskError = null }) { Text("OK", maxLines = 1, softWrap = false) } })
    }

    // Result dialogs
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
        TitleBar(title = "Housekeeping", onBackClick = onBackToHome, onAiClick = onBackToHome)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ===== Refresh Card =====
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

            // ===== Export Card =====
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportConfigLauncher.launch("ai_config.json")
                        }, modifier = Modifier.weight(1f)) { Text("Config", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            exportKeysLauncher.launch("ai_keys.json")
                        }, modifier = Modifier.weight(1f)) { Text("API Keys", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            exportCostsLauncher.launch("ai_costs.csv")
                        }, modifier = Modifier.weight(1f)) { Text("Costs", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                }
            }

            // ===== Import Card =====
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            importType = "config"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f)) { Text("Config", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            importType = "keys"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f)) { Text("API Keys", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            importType = "costs"; importFileLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                        }, modifier = Modifier.weight(1f)) { Text("Costs", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                }
            }

            // ===== Clean Up Card =====
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clean Up", fontWeight = FontWeight.Bold, color = Color.White)

                    OutlinedButton(onClick = {
                        val deleted = ApiTracer.deleteTracesOlderThan(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                        Toast.makeText(context, "Deleted $deleted old traces", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Delete Traces Older Than 7 Days", maxLines = 1, softWrap = false) }

                    OutlinedButton(onClick = {
                        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                        val reports = ReportStorage.getAllReports(context).filter { it.timestamp < cutoff }
                        reports.forEach { ReportStorage.deleteReport(context, it.id) }
                        Toast.makeText(context, "Deleted ${reports.size} old reports", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Delete Reports Older Than 30 Days", maxLines = 1, softWrap = false) }

                    OutlinedButton(onClick = {
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                        val settingsPrefs = SettingsPreferences(prefs, context.filesDir)
                        settingsPrefs.clearUsageStats()
                        Toast.makeText(context, "Usage statistics cleared", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Clear Usage Statistics", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
