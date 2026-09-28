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
 *
 * Call it ABOVE any `if (showPicker) { Picker(...); return }` overlay: an
 * overlay takes this call out of composition, and closing it would re-capture
 * the already-edited form as the baseline (no confirm on Back). Where the call
 * lives below such a return (inside [com.ai.ui.cruds.framework.CrudFormScaffold]),
 * pass a [baseline] taken above the return via [rememberFormBaseline].
 */
@Composable
fun rememberConfirmedBack(current: Any?, onBack: () -> Unit, baseline: FormBaseline? = null): () -> Unit {
    val ownBaseline = remember { current }
    val base = if (baseline != null) baseline.value else ownBaseline
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
    return { if (current != base) confirm = true else onBack() }
}

/** A form's first built value, held for [rememberConfirmedBack]. */
class FormBaseline(val value: Any?)

/** Remember [current] as the form's "unchanged" baseline. Call it above the
 *  form's full-screen picker returns and hand the result to
 *  [rememberConfirmedBack] (or `CrudFormScaffold(baseline = …)`). */
@Composable
fun rememberFormBaseline(current: Any?): FormBaseline = remember { FormBaseline(current) }
