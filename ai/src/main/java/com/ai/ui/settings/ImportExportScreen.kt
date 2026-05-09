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
import com.ai.ui.shared.csvField
import com.ai.ui.shared.exportTimestamp
import com.ai.ui.shared.parseCsvRow
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

// Drop-in shape for assets/prompts.json — no id field (the seed
// loader assigns fresh UUIDs on read). Used by both the standalone
// prompts.json export and the All-bundle's "prompts" section.
private fun promptEntry(p: InternalPrompt): Map<String, Any> = linkedMapOf(
    "name" to p.name,
    "title" to p.title,
    "reference" to p.reference,
    "category" to p.category,
    "agent" to p.agent,
    "text" to p.text
)

/** Apply Workers sections (agents / flocks / swarms) from a parsed
 *  bundle root to [working]. Each section upserts by id — existing
 *  entries with a matching id get replaced, missing ones append.
 *  Per-entry deserialisation is wrapped so a single bad row (e.g.
 *  references a deleted provider, which makes AppServiceAdapter
 *  throw) doesn't take down the rest. Returns the new Settings plus
 *  per-section counts for the toast. */
private data class WorkerImportResult(
    val settings: Settings,
    val agents: Int,
    val flocks: Int,
    val swarms: Int
) {
    fun isEmpty() = agents == 0 && flocks == 0 && swarms == 0
}

private fun applyWorkers(root: JsonObject, working: Settings): WorkerImportResult {
    val gson = createAppGson()
    fun <T> readList(name: String, type: Class<T>): List<T> {
        val arr = root.getAsJsonArray(name) ?: return emptyList()
        val out = mutableListOf<T>()
        arr.forEach { el ->
            try { out.add(gson.fromJson(el, type)) }
            catch (e: Exception) { android.util.Log.w("ImportExport", "Skipped $name entry: ${e.message}") }
        }
        return out
    }
    val incomingAgents = readList("agents", Agent::class.java)
    val incomingFlocks = readList("flocks", Flock::class.java)
    val incomingSwarms = readList("swarms", Swarm::class.java)

    fun <T, ID> upsert(existing: List<T>, incoming: List<T>, idOf: (T) -> ID): List<T> {
        val incomingIds = incoming.map(idOf).toSet()
        return existing.filterNot { idOf(it) in incomingIds } + incoming
    }
    val mergedAgents = upsert(working.agents, incomingAgents) { it.id }
    val mergedFlocks = upsert(working.flocks, incomingFlocks) { it.id }
    val mergedSwarms = upsert(working.swarms, incomingSwarms) { it.id }
    val updated = working.copy(agents = mergedAgents, flocks = mergedFlocks, swarms = mergedSwarms)
    return WorkerImportResult(updated, incomingAgents.size, incomingFlocks.size, incomingSwarms.size)
}

/** JSON tree of every Agent / Flock / Swarm. Same shape used by both
 *  the standalone Workers export and the All-bundle (inlined at the
 *  bundle root, not nested). */
private fun buildWorkersTree(settings: Settings): JsonObject {
    val gson = createAppGson()
    return JsonObject().apply {
        add("agents", gson.toJsonTree(settings.agents))
        add("flocks", gson.toJsonTree(settings.flocks))
        add("swarms", gson.toJsonTree(settings.swarms))
    }
}

@Composable
fun ImportExportScreen(
    aiSettings: Settings,
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onSave: (Settings) -> Unit,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onSaveArtificialAnalysisApiKey: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    var importType by remember { mutableStateOf("keys") }

    fun writeToUri(uri: Uri, content: String) {
        // Force UTF-8 — toByteArray() and bufferedReader() default to
        // the platform charset; on a non-UTF-8 device that mangles
        // any non-ASCII content (Chinese / Cyrillic / emoji in agent
        // or provider names) when JSON crosses the SAF boundary.
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    fun readFromUri(uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    val exportKeysLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = buildApiKeysJson(aiSettings, huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey)
        // Count is just the populated key count — re-derive from the
        // payload to keep the toast in step with the helper's logic.
        val count = JsonParser.parseString(json).asJsonObject.size()
        writeToUri(uri, json)
        Toast.makeText(context, "$count API keys exported", Toast.LENGTH_SHORT).show()
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
        // {name, title, reference, category, agent, text} objects, no
        // ids (the seed loader assigns fresh UUIDs on read).
        val payload = aiSettings.internalPrompts.map { promptEntry(it) }
        writeToUri(uri, createAppGson(prettyPrint = true).toJson(payload))
        Toast.makeText(context, "Internal prompts exported (${payload.size} entries)", Toast.LENGTH_SHORT).show()
    }

    val exportWorkersLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // { agents: [...], flocks: [...], swarms: [...] } — same shape
        // the All bundle inlines and the Workers import reads back.
        val tree = buildWorkersTree(aiSettings)
        writeToUri(uri, createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(
            context,
            "Workers exported (${aiSettings.agents.size} agents, ${aiSettings.flocks.size} flocks, ${aiSettings.swarms.size} swarms)",
            Toast.LENGTH_SHORT
        ).show()
    }

    // "All" bundle: single JSON file carrying every section the
    // individual buttons would have written. Structure:
    //   { "apiKeys": {<keys-json>}, "costs": [...], "providers": [...],
    //     "prompts": [...], "agents": [...], "flocks": [...], "swarms": [...] }
    // Each section is the same payload the matching individual export
    // produces, so the importer can hand each one to its existing
    // handler without a separate schema. costs are converted from CSV
    // to a JSON array since the bundle is JSON-only; workers are
    // inlined (agents/flocks/swarms at the bundle root) so the same
    // applyWorkers() helper handles both standalone and bundled.
    val exportAllLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val gson = createAppGson(prettyPrint = true)
        val bundle = JsonObject()
        bundle.add(
            "apiKeys",
            JsonParser.parseString(buildApiKeysJson(aiSettings, huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey))
        )
        val costsArr = JsonArray()
        val manual = PricingCache.getAllManualPricing(context)
        manual.forEach { (key, pricing) ->
            val parts = key.split(":", limit = 2)
            val obj = JsonObject().apply {
                addProperty("provider", parts[0])
                addProperty("model", parts.getOrElse(1) { "" })
                addProperty("inputPerMillion", pricing.promptPrice * 1_000_000)
                addProperty("outputPerMillion", pricing.completionPrice * 1_000_000)
            }
            costsArr.add(obj)
        }
        bundle.add("costs", costsArr)
        val providers = ProviderRegistry.getCustomProviders()
        bundle.add("providers", gson.toJsonTree(providers))
        val prompts = aiSettings.internalPrompts.map { promptEntry(it) }
        bundle.add("prompts", gson.toJsonTree(prompts))
        val workers = buildWorkersTree(aiSettings)
        workers.entrySet().forEach { (k, v) -> bundle.add(k, v) }
        writeToUri(uri, gson.toJson(bundle))
        val keysCount = bundle.getAsJsonObject("apiKeys").size()
        Toast.makeText(
            context,
            "Bundle exported ($keysCount keys, ${manual.size} costs, ${providers.size} providers, " +
                "${prompts.size} prompts, ${aiSettings.agents.size} agents, ${aiSettings.flocks.size} flocks, ${aiSettings.swarms.size} swarms)",
            Toast.LENGTH_LONG
        ).show()
    }

    val exportCostsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val manual = PricingCache.getAllManualPricing(context)
        val lines = mutableListOf("provider,model,input_per_million,output_per_million")
        manual.forEach { (key, pricing) ->
            val parts = key.split(":", limit = 2)
            lines.add(listOf(
                csvField(parts[0]),
                csvField(parts.getOrElse(1) { "" }),
                "${pricing.promptPrice * 1_000_000}",
                "${pricing.completionPrice * 1_000_000}"
            ).joinToString(","))
        }
        writeToUri(uri, lines.joinToString("\n"))
        Toast.makeText(context, "${manual.size} cost entries exported", Toast.LENGTH_SHORT).show()
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (importType) {
            "keys" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                try {
                    val result = applyApiKeysJson(json, aiSettings)
                    if (result == null) {
                        Toast.makeText(context, "Expected a JSON object like {\"OPENAI\": \"sk-...\"}", Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    result.huggingFaceApiKey?.let { onSaveHuggingFaceApiKey(it) }
                    result.openRouterApiKey?.let { onSaveOpenRouterApiKey(it) }
                    result.artificialAnalysisApiKey?.let { onSaveArtificialAnalysisApiKey(it) }
                    onSave(result.settings)
                    val msg = "${result.imported} API keys imported" + if (result.skipped > 0) ", ${result.skipped} skipped" else ""
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } catch (e: ConfigBundleMistakenForKeysException) {
                    Toast.makeText(context, "This looks like a full config bundle, not an API keys file.", Toast.LENGTH_LONG).show()
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
                    val parts = parseCsvRow(line)
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
            "workers" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Workers file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val res = applyWorkers(root, aiSettings)
                if (res.isEmpty()) {
                    Toast.makeText(context, "No agents / flocks / swarms found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(res.settings)
                    Toast.makeText(
                        context,
                        "Imported ${res.agents} agents, ${res.flocks} flocks, ${res.swarms} swarms",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            "all" -> {
                // All-bundle: { apiKeys, costs, providers, prompts }.
                // Each section is optional; missing or malformed sections
                // are skipped, present ones are dispatched through the
                // same logic the standalone importers use. Settings
                // updates are folded together (keys + prompts) so the
                // single onSave at the end carries both.
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Bundle is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val parts = mutableListOf<String>()
                var working = aiSettings

                root.getAsJsonObject("apiKeys")?.let { keysObj ->
                    try {
                        val res = applyApiKeysJson(keysObj.toString(), working)
                        if (res != null) {
                            res.huggingFaceApiKey?.let { onSaveHuggingFaceApiKey(it) }
                            res.openRouterApiKey?.let { onSaveOpenRouterApiKey(it) }
                            res.artificialAnalysisApiKey?.let { onSaveArtificialAnalysisApiKey(it) }
                            working = res.settings
                            parts.add("${res.imported} keys")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ImportExport", "Bundle apiKeys section failed: ${e.message}")
                    }
                }

                root.getAsJsonArray("costs")?.let { arr ->
                    var imported = 0
                    arr.forEach { el ->
                        val o = (el as? JsonObject) ?: return@forEach
                        val provId = o.get("provider")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                        val model = o.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                        val inp = o.get("inputPerMillion")?.takeIf { it.isJsonPrimitive }?.asDouble?.div(1_000_000) ?: return@forEach
                        val outp = o.get("outputPerMillion")?.takeIf { it.isJsonPrimitive }?.asDouble?.div(1_000_000) ?: return@forEach
                        val provider = AppService.findById(provId) ?: return@forEach
                        if (model.isNotBlank()) {
                            PricingCache.setManualPricing(context, provider, model, inp, outp); imported++
                        }
                    }
                    if (imported > 0) parts.add("$imported costs")
                }

                root.getAsJsonArray("providers")?.let { arr ->
                    val wrapped = JsonObject().apply { add("providers", arr) }
                    val n = ProviderRegistry.upsertFromJson(wrapped.toString())
                    if (n > 0) parts.add("$n providers")
                }

                root.getAsJsonArray("prompts")?.let { arr ->
                    val pair = com.ai.data.InternalPromptSeed.upsertFromJson(arr.toString(), working.internalPrompts)
                    if (pair != null) {
                        working = working.copy(internalPrompts = pair.first)
                        if (pair.second > 0) parts.add("${pair.second} prompts")
                    }
                }

                run {
                    val w = applyWorkers(root, working)
                    if (!w.isEmpty()) {
                        working = w.settings
                        if (w.agents > 0) parts.add("${w.agents} agents")
                        if (w.flocks > 0) parts.add("${w.flocks} flocks")
                        if (w.swarms > 0) parts.add("${w.swarms} swarms")
                    }
                }

                if (working !== aiSettings) onSave(working)
                Toast.makeText(
                    context,
                    if (parts.isEmpty()) "Bundle had no recognised sections" else "Imported: " + parts.joinToString(", "),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "import_export", title = "Export / Import", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportKeysLauncher.launch("ai_keys-${exportTimestamp()}.json")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("API Keys", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            exportCostsLauncher.launch("ai_costs-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Costs Overrides", fontSize = 12.sp, maxLines = 1, softWrap = false) }
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
                    // Agents + Flocks + Swarms in one file. Half-width
                    // button paired with a spacer so it lines up with
                    // the columns above.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportWorkersLauncher.launch("ai_workers-${exportTimestamp()}.json")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Workers", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // "All": single JSON file bundling every section
                    // above. Round-trips through the matching All import.
                    OutlinedButton(onClick = {
                        exportAllLauncher.launch("ai_bundle-${exportTimestamp()}.json")
                    }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                        Text("All", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            importType = "keys"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("API Keys", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = {
                            importType = "costs"; importFileLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Costs Overrides", fontSize = 12.sp, maxLines = 1, softWrap = false) }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            importType = "workers"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Workers", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = {
                        importType = "all"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                    }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                        Text("All", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

        }
    }
}
