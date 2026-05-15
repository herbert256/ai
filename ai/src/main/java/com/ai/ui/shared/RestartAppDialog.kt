package com.ai.ui.shared

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer

/**
 * Relaunch the launcher activity in a fresh task and kill the current
 * process. Used after operations that wholesale replace the on-disk
 * state (Backup restore, Import all, Reset, Refresh all) — the next
 * launch reads everything from disk so the in-memory singletons
 * (ProviderRegistry, ApiTracer, PromptCache, ChatHistoryManager,
 * PricingCache caches, etc.) come back in sync with the new on-disk
 * state instead of carrying stale snapshots.
 */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
}

/**
 * Non-dismissible "operation done — press OK to restart" prompt.
 * Used by every full-state-replace operation so the user always sees
 * a clear confirmation of what happened before the app comes back
 * fresh. [onConfirm] should call [restartApp] (or equivalent) — kept
 * as a callback so callers can flush any in-memory state first.
 */
@Composable
fun RestartAppDialog(message: String, onConfirm: () -> Unit) {
    AlertDialog(
        // Not dismissible — the in-memory state is out of sync with
        // disk at this point, so the user must restart before doing
        // anything else.
        onDismissRequest = {},
        title = { Text(message) },
        text = { Text("Press OK to restart the application.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) { Text("OK", maxLines = 1, softWrap = false) }
        }
    )
}

/**
 * Non-modal sibling of [RestartAppDialog] — a sticky "Restart
 * application" call-to-action button rendered at the top of a page
 * after an action (Restore, Import all, Refresh all) leaves the
 * in-memory state out of sync with disk. The user is still free to
 * navigate, but the prompt stays put until they tap it.
 */
@Composable
fun RestartAppBanner(message: String, onConfirm: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) {
            Text(
                "Restart application",
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1, softWrap = false
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            message,
            fontSize = 11.sp,
            color = AppColors.TextTertiary
        )
    }
}
