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
import com.ai.data.ProviderRegistry
import com.ai.data.createAppGson
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun exportTimestamp(): String =
    SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())

@Composable
fun ImportExportScreen(
    aiSettings: Settings,
    generalSettings: com.ai.viewmodel.GeneralSettings,
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onSave: (Settings) -> Unit,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onSaveArtificialAnalysisApiKey: (String) -> Unit,
    onSaveGeneral: (com.ai.viewmodel.GeneralSettings) -> Unit,
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
        writeToUri(uri, exportAiConfig(context, aiSettings, generalSettings))
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
        if (artificialAnalysisApiKey.isNotBlank()) keys["EXT_ARTIFICIALANALYSIS"] = artificialAnalysisApiKey
        writeToUri(uri, createAppGson(prettyPrint = true).toJson(keys))
        Toast.makeText(context, "${keys.size} API keys exported", Toast.LENGTH_SHORT).show()
    }

    val exportProvidersJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Dump the current registry as `{ "providers": [<ProviderDefinition>] }`
        // — same shape the on-demand "Import new providers from
        // assets/providers.json" button consumes. ProviderDefinition
        // doesn't carry an apiKey field, so this export is intrinsically
        // key-free; the per-provider configs (with keys) live in a
        // different prefs file and are not touched here.
        val defs = ProviderRegistry.getCustomProviders()
        val payload = mapOf("providers" to defs)
        writeToUri(uri, createAppGson(prettyPrint = true).toJson(payload))
        Toast.makeText(context, "Providers exported (${defs.size} entries)", Toast.LENGTH_SHORT).show()
    }

    val exportPromptsJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Drop-in shape for assets/prompts.json — top-level array of
        // {name, type, reference, category, agent, text} objects, no
        // ids (the seed loader assigns fresh UUIDs on read).
        val payload = aiSettings.internalPrompts.map {
            linkedMapOf(
                "name" to it.name,
                "type" to it.type,
                "reference" to it.reference,
                "category" to it.category,
                "agent" to it.agent,
                "text" to it.text
            )
        }
        writeToUri(uri, createAppGson(prettyPrint = true).toJson(payload))
        Toast.makeText(context, "Internal prompts exported (${payload.size} entries)", Toast.LENGTH_SHORT).show()
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

    // Layered-costs export: every (provider, model) on one row with two empty
    // columns up front for the user to fill in a new override, followed by
    // every tier's price in run-time precedence order (LITELLM > MODELSDEV
    // > OVERRIDE > OPENROUTER > DEFAULT). Re-imports through the existing
    // Costs import path: same first four columns the importer reads. The
    // "filtered" variant drops rows whose LiteLLM, models.dev, or
    // OpenRouter columns are populated — those models already have a
    // curated price and the user only wants to see the ones they'd need
    // to override manually.
    fun buildLayeredCsv(filterCovered: Boolean): Pair<String, Int> {
        fun fmt(p: Double?): String = p?.let { "%.4f".format(Locale.US, it * 1_000_000) } ?: ""
        val header = "provider,model,new_input_per_million,new_output_per_million," +
            "litellm_in,litellm_out,modelsdev_in,modelsdev_out," +
            "helicone_in,helicone_out,llmprices_in,llmprices_out," +
            "aa_in,aa_out," +
            "override_in,override_out,openrouter_in,openrouter_out," +
            "default_in,default_out"
        val rows = aiSettings.getActiveServices()
            .flatMap { svc -> aiSettings.getModels(svc).map { svc to it } }
            .sortedWith(compareBy({ it.first.id }, { it.second }))
        val lines = mutableListOf(header)
        var kept = 0
        rows.forEach { (provider, model) ->
            val b = PricingCache.getTierBreakdown(context, provider, model)
            if (filterCovered && (b.litellm != null || b.modelsDev != null || b.helicone != null || b.llmPrices != null || b.artificialAnalysis != null || b.openrouter != null)) return@forEach
            kept++
            lines.add(
                listOf(
                    provider.id, model, "", "",
                    fmt(b.litellm?.promptPrice), fmt(b.litellm?.completionPrice),
                    fmt(b.modelsDev?.promptPrice), fmt(b.modelsDev?.completionPrice),
                    fmt(b.helicone?.promptPrice), fmt(b.helicone?.completionPrice),
                    fmt(b.llmPrices?.promptPrice), fmt(b.llmPrices?.completionPrice),
                    fmt(b.artificialAnalysis?.promptPrice), fmt(b.artificialAnalysis?.completionPrice),
                    fmt(b.override?.promptPrice), fmt(b.override?.completionPrice),
                    fmt(b.openrouter?.promptPrice), fmt(b.openrouter?.completionPrice),
                    fmt(b.default.promptPrice), fmt(b.default.completionPrice)
                ).joinToString(",")
            )
        }
        return lines.joinToString("\n") to kept
    }

    val exportLayeredCostsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (csv, kept) = buildLayeredCsv(filterCovered = false)
        writeToUri(uri, csv)
        Toast.makeText(context, "$kept layered cost rows exported", Toast.LENGTH_SHORT).show()
    }

    val exportLayeredCostsFilteredLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (csv, kept) = buildLayeredCsv(filterCovered = true)
        writeToUri(uri, csv)
        Toast.makeText(context, "$kept rows without LiteLLM/OpenRouter prices exported", Toast.LENGTH_SHORT).show()
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
                    result.artificialAnalysisApiKey?.let { onSaveArtificialAnalysisApiKey(it) }
                    var gs = generalSettings
                    result.defaultTypePaths?.let { gs = gs.copy(defaultTypePaths = it) }
                    if (gs != generalSettings) onSaveGeneral(gs)
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
                            "EXT_ARTIFICIALANALYSIS" -> { onSaveArtificialAnalysisApiKey(key); count++ }
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
            "providers" -> {
                // Catalog-only update. Per-provider API keys live in
                // Settings (different prefs file) and are not touched.
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val n = ProviderRegistry.upsertFromJson(json)
                if (n < 0) Toast.makeText(context, "Could not parse providers.json", Toast.LENGTH_LONG).show()
                else Toast.makeText(context, "Updated $n provider${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
            "prompts" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val pair = com.ai.data.InternalPromptSeed.upsertFromJson(json, aiSettings.internalPrompts)
                if (pair == null) {
                    Toast.makeText(context, "Could not parse prompts.json", Toast.LENGTH_LONG).show()
                } else {
                    val (updated, n) = pair
                    onSave(aiSettings.copy(internalPrompts = updated))
                    Toast.makeText(context, "Updated $n internal prompt${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
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
                            exportConfigLauncher.launch("ai_config-${exportTimestamp()}.json")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Config", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            exportKeysLauncher.launch("ai_keys-${exportTimestamp()}.json")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("API Keys", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            exportCostsLauncher.launch("ai_costs-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Costs", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                    // Bundle-shape exports: provider catalog and internal
                    // prompts. Drop-in shape for assets/providers.json
                    // and assets/prompts.json so a developer can ship the
                    // user's tuned catalog as the new bundled defaults.
                    // Neither carries an API key.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportProvidersJsonLauncher.launch("providers.json")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("providers.json", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            exportPromptsJsonLauncher.launch("prompts.json")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("prompts.json", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Layered costs", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "One row per (provider, model). Two empty columns up front for a new override; the rest show every tier's \$/M-token price in run-time precedence order (LiteLLM > models.dev > Override > OpenRouter > Default). All exports every model; Filtered drops rows that already have a LiteLLM, models.dev, or OpenRouter price. Re-import via the Costs button — only the first four columns are read.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportLayeredCostsLauncher.launch("ai_costs_layered-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                            Text("All", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = {
                            exportLayeredCostsFilteredLauncher.launch("ai_costs_layered_filtered-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                            Text("Filtered", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            importType = "config"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Config", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            importType = "keys"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("API Keys", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            importType = "costs"; importFileLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Costs", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                    // Bundle-shape imports: provider catalog and internal
                    // prompts. Upsert by id (providers) or by name
                    // (prompts). API keys are stored in a separate prefs
                    // file and stay untouched.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            importType = "providers"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("providers.json", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            importType = "prompts"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("prompts.json", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}
