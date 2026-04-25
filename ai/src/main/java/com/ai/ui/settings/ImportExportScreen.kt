package com.ai.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.data.createAppGson
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

@Composable
fun ImportExportScreen(
    aiSettings: Settings,
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    onSave: (Settings) -> Unit,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

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
                    android.util.Log.e("ImportExport", "API keys import parse error", e)
                    Toast.makeText(context, "Not valid JSON", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("ImportExport", "API keys import error", e)
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Export / Import", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

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
        }
    }
}
