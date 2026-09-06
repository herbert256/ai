package com.ai.ui.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.ai.data.ReportSaveRecovery

@Composable
fun ReportSaveRecoveryDialog() {
    val changes by ReportSaveRecovery.changes.collectAsState()
    val change = changes.firstOrNull() ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Report changes not saved (${changes.size})") },
        text = { Text(change.message) },
        confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { ReportSaveRecovery.retry(change.id) } }) { Text("Retry save") } },
        dismissButton = { TextButton(onClick = { scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Unsaved report changes", change.text)))
            ReportSaveRecovery.dismiss(change.id)
        } }) { Text("Copy unsaved changes") } }
    )
}
