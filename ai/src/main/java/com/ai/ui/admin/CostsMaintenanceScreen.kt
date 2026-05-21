package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.csvField
import com.ai.ui.shared.exportTimestamp
import com.ai.ui.shared.parseCsvRow

/**
 * Housekeeping → Costs. Bulk maintenance for manual price overrides:
 * the **Cleanup** card (drop dormant/redundant overrides) and the
 * **Layered costs** CSV export/import. Per-row override curation lives
 * in AI Setup → Costs (the manual-override crud); this screen carries
 * the two occasional bulk operations that used to share that screen.
 */
@Composable
fun CostsMaintenanceScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

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
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "cost_config", title = "Costs", onBackClick = onBack)

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
