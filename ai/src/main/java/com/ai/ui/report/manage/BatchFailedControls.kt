package com.ai.ui.report.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReloadConfirmationDialog

/**
 * Shared "Remove failed / Restart failed" button row for the worker-grid
 * batch L1 screens (Tournament, Judge-the-judges, Compare-with-meta).
 * Rendered only while the run has errored rows; both actions confirm
 * first, mirroring the Fan Out L2 idiom. Restart re-fires just the
 * failed rows (completed ones are kept and not re-billed); Remove drops
 * them so the aggregate recomputes from the remaining rows.
 */
@Composable
internal fun BatchFailedControls(
    erroredCount: Int,
    /** Row noun for the dialog copy — e.g. "match" / "matches". */
    singular: String,
    plural: String,
    onRestartFailed: () -> Unit,
    onRemoveFailed: () -> Unit
) {
    if (erroredCount <= 0) return
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val noun = if (erroredCount == 1) singular else plural
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedButton(
            onClick = { confirmRemove = true },
            colors = AppColors.outlinedButtonColors(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f).heightIn(min = 32.dp)
        ) { Text("Remove failed ($erroredCount)", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        Button(
            onClick = { confirmRestart = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f).heightIn(min = 32.dp)
        ) { Text("Restart failed ($erroredCount)", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }
    Spacer(Modifier.height(8.dp))
    if (confirmRestart) {
        ReloadConfirmationDialog(
            target = "",
            title = "Restart failed $plural?",
            message = "Re-fires the $erroredCount failed $noun. Completed rows are kept and not re-billed.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestart = false
                onRestartFailed()
            },
            onDismiss = { confirmRestart = false }
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove failed $plural?") },
            text = { Text("Drops the $erroredCount failed $noun from the run; the ranking recomputes from the remaining rows. Can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoveFailed()
                }) { Text("Remove", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
