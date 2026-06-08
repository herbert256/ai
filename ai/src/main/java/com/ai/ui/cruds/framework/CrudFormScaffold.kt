package com.ai.ui.cruds.framework

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.DeleteConfirmationDialog
import com.ai.ui.shared.TitleBar

/**
 * Uniform CRUD add/edit form scaffold: TitleBar + the scrollable form
 * [content]. Both add.kt (isAdd=true) and edit.kt (isAdd=false) delegate here.
 *
 * Both add and edit show one top button — "Create" (add) / "Save" (edit) —
 * enabled when [current] is non-null (the form is valid); on tap it persists via
 * [onSave] then navigates back. Leaving by Back instead routes through
 * [rememberConfirmedBack], which confirms "Discard changes?" when the form was
 * edited. [current] is the built entity, or `null` when invalid (Save disabled).
 */
@Composable
fun CrudFormScaffold(
    title: String,
    isAdd: Boolean,
    current: Any?,
    onSave: () -> Unit,
    onBack: () -> Unit,
    subject: String? = null,
    helpTopic: String? = null,
    /** Optional 🧽 reset hook. On Add it clears the inputs; on Edit it
     *  discards the user's edits and shows the saved values again. Null
     *  → glyph hidden. */
    onReset: (() -> Unit)? = null,
    /** Optional 👯 duplicate hook (Edit only). Null → glyph hidden. */
    onCopy: (() -> Unit)? = null,
    /** Optional 🗑 delete hook (Edit only). Null → glyph hidden. A
     *  confirmation dialog runs first; [deleteName] names the entity. */
    onDelete: (() -> Unit)? = null,
    deleteName: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    // Back confirms "Discard changes?" when the form was edited; Save bypasses it.
    val back = com.ai.ui.shared.rememberConfirmedBack(current, onBack)
    BackHandler { back() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(16.dp)
    ) {
        TitleBar(
            helpTopic = helpTopic, title = title, subject = subject, onBackClick = back, onClear = onReset,
            onCopyReport = if (isAdd) null else onCopy,
            onDelete = if (isAdd || onDelete == null) null else ({ confirmDelete = true })
        )
        // Save (edit) / Create (add) — both persist then close.
        OutlinedButton(
            onClick = { onSave(); onBack() },
            enabled = current != null,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (isAdd) "Create" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirmationDialog(
            entityType = title,
            entityName = deleteName ?: title,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}
