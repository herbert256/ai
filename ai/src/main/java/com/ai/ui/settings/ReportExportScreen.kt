package com.ai.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppLog
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.writeReportZip
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.exportTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Write one `ai_report_<title>_<ts>.zip` per [reportIds] entry into
 *  the SAF-picked folder [treeUri]. Each zip is the same bundle
 *  [writeReportZip] stages for the share flow (report JSON + every
 *  secondary + every trace). Same-titled reports collide on name; the
 *  DocumentsProvider auto-suffixes ("(1)", …) so none clobbers.
 *  Returns (succeeded, failed) — a single bad report is logged and
 *  skipped so the rest of the batch still lands. */
private fun exportReportsToTree(
    context: Context,
    treeUri: Uri,
    reportIds: List<String>
): Pair<Int, Int> {
    val resolver = context.contentResolver
    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )
    val ts = exportTimestamp()
    var ok = 0
    var failed = 0
    for (id in reportIds) {
        try {
            val report = ReportStorage.getReport(context, id) ?: error("Report $id not found")
            // Same sanitisation the single-report share export used:
            // strip path-hostile chars, cap length, and fall back to
            // "report" when an all-non-ASCII title collapses to blank.
            val safeTitle = report.title.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .take(40)
                .trim('_', '.', '-')
                .ifBlank { "report" }
            val name = "ai_report_${safeTitle}_$ts.zip"
            val docUri = DocumentsContract.createDocument(
                resolver, parentDocUri, "application/zip", name
            ) ?: error("Could not create $name")
            resolver.openOutputStream(docUri)?.use { out ->
                writeReportZip(context, id, out)
            } ?: error("Could not open output stream for $name")
            ok++
        } catch (e: Exception) {
            AppLog.e("ImportExport", "Report folder-export failed for $id", e)
            failed++
        }
    }
    return ok to failed
}

/** Full-screen multi-select report exporter. Replaces the old
 *  single-report "Pick a report…" dropdown on the Reports card: lists
 *  every persisted report with a checkbox, the top Export button
 *  enabled once one or more are ticked. Tapping Export opens the
 *  system folder picker; on a folder choice one zip per selected
 *  report is written into it. */
@Composable
fun ReportExportScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allReports by produceState(initialValue = emptyList<Report>()) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    val selectedIds = remember { mutableStateListOf<String>() }
    var exporting by remember { mutableStateOf(false) }

    // Folder picker: pick a destination directory once, then write one
    // zip per selected report into it. OpenDocumentTree (not the share
    // chooser) because the batch writes N separate files to one place.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val (ok, failed) = withContext(Dispatchers.IO) {
                exportReportsToTree(context, treeUri, ids)
            }
            exporting = false
            val msg = if (failed == 0) "Exported $ok report${if (ok == 1) "" else "s"}"
            else "Exported $ok, $failed failed"
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_export",
            title = "Export reports",
            subject = "Pick one or more, then choose a folder",
            onBackClick = onBack
        )

        // Primary action at the top, per the user's request. Enabled
        // once at least one report is ticked and no export is in flight.
        OutlinedButton(
            onClick = { if (selectedIds.isNotEmpty()) pickFolder.launch(null) },
            enabled = selectedIds.isNotEmpty() && !exporting,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) {
            Text(
                if (exporting) "Exporting…"
                else if (selectedIds.isEmpty()) "Export"
                else "Export (${selectedIds.size})",
                maxLines = 1, softWrap = false
            )
        }

        if (allReports.isNotEmpty()) {
            // Select-all / clear toggle — flips on whether every report
            // is already ticked.
            val allSelected = selectedIds.size == allReports.size
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        if (allSelected) selectedIds.clear()
                        else {
                            selectedIds.clear()
                            selectedIds.addAll(allReports.map { it.id })
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = AppColors.PrimaryAccent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (allSelected) "Clear all" else "Select all",
                    color = AppColors.TextSecondary, fontSize = 13.sp
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            if (allReports.isEmpty()) {
                Text(
                    "No reports on this device.",
                    color = AppColors.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                allReports.forEach { r ->
                    val checked = r.id in selectedIds
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (checked) selectedIds.remove(r.id)
                                else selectedIds.add(r.id)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = AppColors.PrimaryAccent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            r.title.ifBlank { "(untitled)" },
                            color = AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
