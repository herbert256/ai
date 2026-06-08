package com.ai.ui.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Back guard for a CRUD edit screen: returns a "back" lambda that confirms
 * before discarding unsaved edits. The explicit Save button persists + closes;
 * leaving by Back instead routes through this so typed-but-unsaved edits aren't
 * silently lost.
 *
 * Remembers the FIRST [current] seen as the baseline. When the returned lambda
 * is invoked while `current != baseline` (the form was edited) it shows a
 * "Discard changes?" dialog — Discard → [onBack], Keep editing → stay. When the
 * form is unchanged it calls [onBack] directly (no dialog).
 *
 * [current] is the built entity, or `null` when the form is invalid — the same
 * value the Save button persists, so dirty-detection and validity share one
 * source of truth.
 */
@Composable
fun rememberConfirmedBack(current: Any?, onBack: () -> Unit): () -> Unit {
    val baseline = remember { current }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits haven't been saved. Discard them?") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onBack() }) {
                    Text("Discard", color = AppColors.DangerAccent, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text("Keep editing", maxLines = 1, softWrap = false)
                }
            }
        )
    }
    return { if (current != baseline) confirm = true else onBack() }
}
