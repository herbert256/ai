package com.ai.ui.admin

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
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.csvField
import com.ai.ui.shared.exportTimestamp
import com.ai.ui.shared.parseCsvRow
import java.util.Locale

/** Full-screen home for everything that touches the manual override
 *  tier of the layered pricing lookup: the cleanup pass that prunes
 *  redundant entries, plus the layered-cost CSV export/import that
 *  bulk-edits overrides across every (provider, model) row. Was a
 *  collapsed card in Housekeeping ("Manual cost overrides") and a
 *  card in Export/Import ("Layered costs"); both are now here since
 *  they configure the same thing. */
@Composable
fun ManualCostOverridesScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    fun writeToUri(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    fun readFromUri(uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    // Layered-costs CSV: every (provider, model) on one row with two
    // empty columns up front for the user to fill in a new override,
    // followed by every tier's price in run-time precedence order.
    // The "filtered" variant drops rows already covered by any
    // catalog tier (the user only wants to see ones they'd need to
    // override manually).
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
        Toast.makeText(context, "$kept rows not covered by any catalog tier exported", Toast.LENGTH_SHORT).show()
    }

    val importLayeredCostsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Most rows are intentionally blank in columns 3 and 4 (no
        // override added) — ignore them silently instead of counting
        // as skipped. Only rows where the user filled in
        // new_input_per_million + new_output_per_million become
        // manual overrides.
        val csv = readFromUri(uri)
        if (csv.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "manual_cost_overrides", title = "Manual cost overrides", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cleanup", fontWeight = FontWeight.Bold, color = Color.White)
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

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Layered costs", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "CSV showing every catalog tier's \$/M-token price per (provider, model). Fill the two leading override columns and re-import — only rows with values apply. Filtered drops rows already covered by a catalog tier.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportLayeredCostsLauncher.launch("ai_costs_layered-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                            Text("Export all", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = {
                            exportLayeredCostsFilteredLauncher.launch("ai_costs_layered_filtered-${exportTimestamp()}.csv")
                        }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                            Text("Export filtered", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                    OutlinedButton(onClick = {
                        importLayeredCostsLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                    }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                        Text("Import manual changed costs", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
