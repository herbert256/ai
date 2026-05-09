package com.ai.ui.admin

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class StatWithCost(val stat: UsageStats, val inputCost: Double, val outputCost: Double, val totalCost: Double, val pricingSource: String)
private data class ProviderCostGroup(val provider: AppService, val models: List<StatWithCost>, val totalCost: Double, val totalCalls: Int)

@Composable
fun UsageScreen(
    openRouterApiKey: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }
    var stats by remember { mutableStateOf<Map<String, UsageStats>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var pricingReady by remember { mutableStateOf(false) }
    // Saveable so the expanded-provider state survives navigation to Model
    // Info and back. List instead of Set because autoSaver doesn't reliably
    // round-trip arbitrary Set implementations through the saved Bundle.
    var expandedProvidersList by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val expandedProviders = expandedProvidersList.toSet()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stats = settingsPrefs.loadUsageStats()
            if (openRouterApiKey.isNotBlank() && PricingCache.needsOpenRouterRefresh(context)) {
                val pricing = PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, pricing)
            }
            pricingReady = true
        }
        isLoading = false
    }

    val groups = remember(stats, pricingReady) {
        if (!pricingReady || stats.isEmpty()) emptyList()
        else {
            stats.values.groupBy { it.provider }.map { (provider, providerStats) ->
                val models = providerStats.map { stat ->
                    val pricing = PricingCache.getPricing(context, stat.provider, stat.model)
                    // Rerank-kind rows bill per search-unit, not per
                    // token — input/output token counters are zero by
                    // design. Stuff the per-query cost into the input
                    // column so the existing two-column row layout
                    // surfaces it without a special case.
                    val isRerank = stat.kind == "rerank"
                    val ic = if (isRerank) stat.searchUnits * pricing.perQueryPrice
                             else stat.inputTokens * pricing.promptPrice
                    val oc = if (isRerank) 0.0
                             else stat.outputTokens * pricing.completionPrice
                    StatWithCost(stat, ic, oc, ic + oc, pricing.source)
                }.sortedByDescending { it.totalCost }
                ProviderCostGroup(provider, models, models.sumOf { it.totalCost }, models.sumOf { it.stat.callCount })
            }.sortedByDescending { it.totalCost }
        }
    }

    val totalCost = groups.sumOf { it.totalCost }
    val totalCalls = groups.sumOf { it.totalCalls }
    val totalTokens = stats.values.sumOf { it.totalTokens }

    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "statistics",
            title = "AI Usage", onBackClick = onBack,
            onDelete = if (stats.isNotEmpty()) { { confirmClear = true } } else null
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (stats.isEmpty()) {
            Text("No usage data yet. Generate reports or chat to see statistics.", color = AppColors.TextTertiary, modifier = Modifier.padding(16.dp))
        } else {
            // Summary card
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Total: $totalCalls calls, ${formatCompactNumber(totalTokens)} tokens", fontSize = 14.sp, color = Color.White)
                    Text("Cost: ${formatCurrency(totalCost)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Green)
                    Text("Pricing: ${PricingCache.getPricingStats(context)}", fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups, key = { it.provider.id }) { group ->
                    val isExpanded = group.provider.id in expandedProviders
                    UsageProviderCard(
                        group = group, isExpanded = isExpanded,
                        onToggle = { expandedProvidersList = if (isExpanded) expandedProvidersList - group.provider.id else expandedProvidersList + group.provider.id },
                        onModelClick = { model -> onNavigateToModelInfo(group.provider, model) }
                    )
                }
            }

        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all statistics?") },
            text = { Text("Resets every usage counter back to zero. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    settingsPrefs.clearUsageStats(); stats = emptyMap()
                    Toast.makeText(context, "Statistics cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

@Composable
private fun UsageProviderCard(
    group: ProviderCostGroup, isExpanded: Boolean, onToggle: () -> Unit,
    onModelClick: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.provider.id, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("${group.totalCalls} calls", fontSize = 12.sp, color = AppColors.TextTertiary)
                }
                Text(formatCurrency(group.totalCost), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.Green)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isExpanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (isExpanded) {
                HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.padding(vertical = 8.dp))
                group.models.forEach { swc ->
                    UsageModelRow(swc, onClick = { onModelClick(swc.stat.model) })
                }
            }
        }
    }
}

@Composable
private fun UsageModelRow(swc: StatWithCost, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(swc.stat.model, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Type pill — only shown for non-default kinds (rerank,
                // summarize). The default "report" kind matches the
                // implicit assumption and would just be visual noise.
                // Defensive cast: legacy rows written before the kind
                // field existed deserialize to a runtime-null String
                // even though the property is declared non-null;
                // SettingsPreferences.loadUsageStats backfills these on
                // load but coerce again here so an in-flight write
                // can't trip the renderer.
                @Suppress("USELESS_CAST")
                val kind = (swc.stat.kind as String?) ?: "report"
                if (kind != "report") {
                    val kindColor = when (kind) {
                        "rerank" -> AppColors.Orange
                        "summarize" -> AppColors.Indigo
                        "compare" -> AppColors.Purple
                        "moderation" -> AppColors.Red
                        "translate" -> AppColors.Blue
                        else -> AppColors.TextDim
                    }
                    Text(
                        text = kind,
                        fontSize = 9.sp,
                        color = kindColor,
                        modifier = Modifier.padding(start = 6.dp).background(AppColors.SurfaceDark, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            val secondaryLine = if (swc.stat.kind == "rerank") {
                "${swc.stat.callCount} calls, ${formatCompactNumber(swc.stat.searchUnits)} search units"
            } else {
                "${swc.stat.callCount} calls, ${formatCompactNumber(swc.stat.inputTokens)}/${formatCompactNumber(swc.stat.outputTokens)} tokens"
            }
            Text(secondaryLine, fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatCurrency(swc.totalCost), fontSize = 13.sp, color = AppColors.Green)
            val sourceColor = when (swc.pricingSource) {
                "OVERRIDE" -> AppColors.Orange; "OPENROUTER" -> AppColors.Blue; "LITELLM" -> AppColors.Purple
                else -> AppColors.TextDim
            }
            Text(swc.pricingSource, fontSize = 10.sp, color = sourceColor)
        }
    }
}

// ===== Cost Configuration Screen =====

@Composable
fun CostConfigurationScreen(aiSettings: Settings, onBack: () -> Unit, onNavigateHome: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editInputPrice by remember { mutableStateOf("") }
    var editOutputPrice by remember { mutableStateOf("") }
    var showAddScreen by remember { mutableStateOf(false) }

    // Also re-read on ON_RESUME — manual overrides edited from a
    // sibling cost-override screen would otherwise stay invisible until
    // the user pokes refreshTrigger by saving locally.
    val resumeTick = com.ai.ui.shared.resumeRefreshTick()
    val manualPricing = remember(refreshTrigger, resumeTick) { PricingCache.getAllManualPricing(context) }

    // Full-screen overlay for adding new override
    if (showAddScreen) {
        AddManualOverrideScreen(aiSettings = aiSettings, onSave = { provider, model, inp, outp ->
            PricingCache.setManualPricing(context, provider, model, inp, outp)
            showAddScreen = false; refreshTrigger++
        }, onBack = { showAddScreen = false }, onNavigateHome = onNavigateHome)
        return
    }

    // Layered-cost CSV launchers. Lifted from the former Housekeeping
    // → Manual cost overrides screen so the bulk-edit flow lives next
    // to the per-row Add / Edit form.
    fun writeToUri(uri: android.net.Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }
    fun readFromUri(uri: android.net.Uri): String? {
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }
    fun buildLayeredCsv(filterCovered: Boolean): Pair<String, Int> {
        fun fmt(p: Double?): String = p?.let { "%.4f".format(java.util.Locale.US, it * 1_000_000) } ?: ""
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
                    csvField(provider.id), csvField(model), "", "",
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
    val exportLayeredAllLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (csv, kept) = buildLayeredCsv(filterCovered = false)
        writeToUri(uri, csv)
        Toast.makeText(context, "$kept layered cost rows exported", Toast.LENGTH_SHORT).show()
    }
    val exportLayeredFilteredLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (csv, kept) = buildLayeredCsv(filterCovered = true)
        writeToUri(uri, csv)
        Toast.makeText(context, "$kept rows not covered by any catalog tier exported", Toast.LENGTH_SHORT).show()
    }
    val importLayeredLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val csv = readFromUri(uri)
        if (csv.isNullOrBlank()) {
            Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult
        }
        var imported = 0; var skipped = 0
        csv.lines().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val parts = parseCsvRow(line)
            if (parts.size < 4) return@forEach
            val rawIn = parts[2].trim()
            val rawOut = parts[3].trim()
            if (rawIn.isEmpty() && rawOut.isEmpty()) return@forEach
            val provider = AppService.findById(parts[0].trim())
            val model = parts[1].trim()
            val inp = rawIn.toDoubleOrNull()?.div(1_000_000)
            val outp = rawOut.toDoubleOrNull()?.div(1_000_000)
            if (provider != null && model.isNotBlank() && inp != null && outp != null) {
                PricingCache.setManualPricing(context, provider, model, inp, outp); imported++
            } else skipped++
        }
        Toast.makeText(context, "Imported $imported overrides" + (if (skipped > 0) ", skipped $skipped" else ""), Toast.LENGTH_SHORT).show()
        refreshTrigger++
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "cost_config", title = "Cost Config", onBackClick = onBack)

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Button(onClick = { showAddScreen = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                ) { Text("Add Manual Override", maxLines = 1, softWrap = false) }
            }

            if (manualPricing.isEmpty()) {
                item {
                    Text("No manual price overrides configured.\nPricing uses automatic lookup: LiteLLM > OpenRouter > Default.",
                        fontSize = 13.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(16.dp))
                }
            } else {
                val sorted = manualPricing.entries.sortedBy { it.key }
                items(sorted, key = { it.key }) { (key, pricing) ->
                    val parts = key.split(":", limit = 2)
                    val provider = AppService.findById(parts.getOrElse(0) { "" })
                    val model = parts.getOrElse(1) { "" }
                    val isEditing = editingKey == key

                    CostConfigCard(
                        providerName = provider?.id ?: parts[0], model = model,
                        inputPrice = pricing.promptPrice, outputPrice = pricing.completionPrice,
                        isEditing = isEditing, editInputPrice = editInputPrice, editOutputPrice = editOutputPrice,
                        onEditInputChange = { editInputPrice = it }, onEditOutputChange = { editOutputPrice = it },
                        onEdit = {
                            editingKey = key
                            editInputPrice = "%.4f".format(pricing.promptPrice * 1_000_000)
                            editOutputPrice = "%.4f".format(pricing.completionPrice * 1_000_000)
                        },
                        onSave = {
                            val inp = editInputPrice.toDoubleOrNull()?.div(1_000_000)
                            val outp = editOutputPrice.toDoubleOrNull()?.div(1_000_000)
                            if (inp != null && outp != null && provider != null) {
                                PricingCache.setManualPricing(context, provider, model, inp, outp)
                                editingKey = null; refreshTrigger++
                            }
                        },
                        onCancel = { editingKey = null },
                        onRemove = {
                            if (provider != null) { PricingCache.removeManualPricing(context, provider, model); refreshTrigger++ }
                        }
                    )
                }
            }

            // Footer: maintenance cards. Collapsed by default — the
            // user's main task on this screen is curating per-row
            // overrides; cleanup and the bulk CSV flow are occasional.
            item {
                CollapsibleCard("Cleanup") {
                    Text(
                        "Drops every manual price override that is dormant or redundant: covered by a catalog tier (LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis, OpenRouter), equal to the built-in default, or equal to what the lookup would return without it.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = {
                            val n = PricingCache.cleanupRedundantManualOverrides(context)
                            Toast.makeText(
                                context,
                                if (n == 0) "No redundant overrides to remove" else "Removed $n manual cost override${if (n == 1) "" else "s"}",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshTrigger++
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                    ) { Text("Cleanup redundant overrides", maxLines = 1, softWrap = false) }
                }
            }
            item {
                CollapsibleCard("Layered costs") {
                    Text(
                        "CSV showing every catalog tier's \$/M-token price per (provider, model). Fill the two leading override columns and re-import — only rows with values apply. Filtered drops rows already covered by a catalog tier.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportLayeredAllLauncher.launch("ai_costs_layered-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                            Text("Export all", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = {
                            exportLayeredFilteredLauncher.launch("ai_costs_layered_filtered-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                            Text("Export filtered", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                    OutlinedButton(onClick = {
                        importLayeredLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                    }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                        Text("Import manual changed costs", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun CostConfigCard(
    providerName: String, model: String, inputPrice: Double, outputPrice: Double,
    isEditing: Boolean, editInputPrice: String, editOutputPrice: String,
    onEditInputChange: (String) -> Unit, onEditOutputChange: (String) -> Unit,
    onEdit: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(providerName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            Text(model, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (isEditing) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editInputPrice, onValueChange = onEditInputChange,
                    label = { Text("Input $/1M tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(value = editOutputPrice, onValueChange = onEditOutputChange,
                    label = { Text("Output $/1M tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, colors = AppColors.outlinedButtonColors()) { Text("Cancel", maxLines = 1, softWrap = false) }
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)) { Text("Save", maxLines = 1, softWrap = false) }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Input: ${formatTokenPricePerMillion(inputPrice)}", fontSize = 12.sp, color = AppColors.TextTertiary)
                Text("Output: ${formatTokenPricePerMillion(outputPrice)}", fontSize = 12.sp, color = AppColors.TextTertiary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRemove, colors = AppColors.outlinedButtonColors()) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = onEdit, colors = AppColors.outlinedButtonColors()) { Text("Edit", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/**
 * Direct-entry wrapper around [AddManualOverrideScreen] for the "Add
 * manual cost override" link on Model Info — opens the same form
 * pre-filled with the given provider/model. If a cost override already
 * exists for that pair, its current input/output prices are loaded so
 * the screen doubles as an edit form. Saving writes through PricingCache
 * .setManualPricing and pops back to the caller.
 */
@Composable
fun ManualCostOverrideEntryScreen(
    aiSettings: Settings,
    providerId: String,
    modelId: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    val existing = AppService.findById(providerId)?.let { svc ->
        PricingCache.getManualPricing(context, svc, modelId)
    }
    AddManualOverrideScreen(
        aiSettings = aiSettings,
        initialProviderId = providerId,
        initialModel = modelId,
        initialInputPerMillion = existing?.promptPrice?.times(1_000_000),
        initialOutputPerMillion = existing?.completionPrice?.times(1_000_000),
        onSave = { provider, model, inp, outp ->
            PricingCache.setManualPricing(context, provider, model, inp, outp)
            onBack()
        },
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}

@Composable
internal fun AddManualOverrideScreen(
    aiSettings: Settings,
    onSave: (AppService, String, Double, Double) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    initialProviderId: String? = null,
    initialModel: String? = null,
    initialInputPerMillion: Double? = null,
    initialOutputPerMillion: Double? = null
) {
    BackHandler { onBack() }
    var selectedProvider by remember {
        mutableStateOf(initialProviderId?.let { AppService.findById(it) } ?: AppService.entries.firstOrNull())
    }
    var model by remember { mutableStateOf(initialModel ?: "") }
    var inputPrice by remember { mutableStateOf(initialInputPerMillion?.let { "%.4f".format(Locale.US, it) } ?: "") }
    var outputPrice by remember { mutableStateOf(initialOutputPerMillion?.let { "%.4f".format(Locale.US, it) } ?: "") }
    var showProviderSelect by remember { mutableStateOf(false) }
    var showModelSelect by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Full-screen overlays for selection
    if (showProviderSelect && selectedProvider != null) {
        com.ai.ui.shared.SelectProviderScreen(aiSettings = aiSettings,
            onSelectProvider = { selectedProvider = it; showProviderSelect = false },
            onBack = { showProviderSelect = false }, onNavigateHome = onNavigateHome)
        return
    }
    if (showModelSelect && selectedProvider != null) {
        com.ai.ui.shared.SelectModelScreen(provider = selectedProvider!!, aiSettings = aiSettings, currentModel = model,
            onSelectModel = { model = it; showModelSelect = false },
            onBack = { showModelSelect = false }, onNavigateHome = onNavigateHome)
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "cost_override", title = "Add Override", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showProviderSelect = true }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                Text("Provider: ${selectedProvider?.id ?: "Select"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model") }, modifier = Modifier.weight(1f), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedButton(onClick = { showModelSelect = true }, colors = AppColors.outlinedButtonColors()) { Text("Select", maxLines = 1, softWrap = false) }
            }

            // Show current pricing for reference
            if (selectedProvider != null && model.isNotBlank()) {
                val current = PricingCache.getPricingWithoutOverride(context, selectedProvider!!, model)
                Text("Current: input ${formatTokenPricePerMillion(current.promptPrice)}, output ${formatTokenPricePerMillion(current.completionPrice)} (${current.source})",
                    fontSize = 11.sp, color = AppColors.TextTertiary)
            }

            OutlinedTextField(value = inputPrice, onValueChange = { inputPrice = it },
                label = { Text("Input price ($/1M tokens)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = outputPrice, onValueChange = { outputPrice = it },
                label = { Text("Output price ($/1M tokens)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            val inp = inputPrice.toDoubleOrNull()?.div(1_000_000)
            val outp = outputPrice.toDoubleOrNull()?.div(1_000_000)
            if (inp != null && outp != null && selectedProvider != null && model.isNotBlank()) onSave(selectedProvider!!, model, inp, outp)
        }, enabled = selectedProvider != null && model.isNotBlank() && inputPrice.toDoubleOrNull() != null && outputPrice.toDoubleOrNull() != null,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Save", maxLines = 1, softWrap = false) }
    }
}

// Formatting helpers
private fun formatCurrency(value: Double): String {
    return if (value < 0.01 && value > 0) String.format(Locale.US, "$%.6f", value)
    else String.format(Locale.US, "$%.4f", value)
}
