package com.ai.ui.cruds.prompts.internal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage
import com.ai.ui.settings.InternalPromptEditScreen
import com.ai.ui.settings.categoryDisplayName
import java.util.Locale

private sealed interface Mode {
    data object List : Mode
    data class Edit(val item: InternalPrompt) : Mode
    data object Add : Mode
}

/**
 * Uniform CRUD for the internal prompts of one [category]
 * (meta / fan_out / fan_in / icons / internal / info /
 * workers). The `internal`, `icons`, `info` and `workers` categories are
 * FIXED lists: rows are editable but can't be added, copied or deleted
 * (they're built-in templates). `workers` rows additionally carry a
 * worker list, edited via the worker-list editor.
 *
 * Edit/add reuse the existing rich [InternalPromptEditScreen].
 */
@Composable
fun InternalPromptCrud(
    aiSettings: Settings,
    category: String,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Open the Trace screen filtered to a `<category>/<prompt>` category. */
    onNavigateToTraceCategory: (String) -> Unit = {}
) {
    val fixedList = com.ai.ui.settings.isFixedListCategory(category)
    val label = categoryDisplayName(category)
    var mode by remember(category) { mutableStateOf<Mode>(Mode.List) }
    var confirmDelete by remember(category) { mutableStateOf<InternalPrompt?>(null) }
    val toList = { mode = Mode.List }

    // Set of trace categories present on disk (one cached off-thread load),
    // so a 🐞 trace-link shows only for prompts that actually ran. Each
    // internal-prompt call traces under "<category>/<name>".
    val tracedCategories by androidx.compose.runtime.produceState(emptySet<String>(), category) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ai.data.ApiTracer.getTraceFiles().mapNotNull { it.category }.toSet()
        }
    }
    fun traceCat(ip: InternalPrompt) = "${ip.category}/${ip.name}"
    fun hasTrace(ip: InternalPrompt) = traceCat(ip) in tracedCategories

    val upsert: (InternalPrompt) -> Unit = { saved ->
        val list = aiSettings.internalPrompts
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(internalPrompts = updated))
    }
    val remove: (InternalPrompt) -> Unit = { onSave(aiSettings.removeInternalPrompt(it.id)) }
    val existingNames: (String) -> Set<String> = { excludeId ->
        aiSettings.internalPrompts
            .filter { it.id != excludeId && it.category == category }
            .map { it.name.lowercase(Locale.ROOT) }.toSet()
    }

    @Composable
    fun form(initial: InternalPrompt?, onDelete: (() -> Unit)? = null) = key(initial?.id) {
        InternalPromptEditScreen(
            internalPrompt = initial,
            existingNames = existingNames(initial?.id ?: ""),
            agentNames = aiSettings.agents.map { it.name },
            aiSettings = aiSettings,
            fixedCategory = category,
            onSave = { saved -> upsert(saved) },
            onBack = toList,
            onNavigateHome = onNavigateHome,
            onTrace = initial?.takeIf { hasTrace(it) }?.let { ip -> { onNavigateToTraceCategory(traceCat(ip)) } },
            onDelete = onDelete
        )
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = label,
            subject = "Prompts the app's own flows use",
            helpTopic = "crud_internal_prompts",
            items = aiSettings.internalPrompts.filter { it.category == category }.sortedBy { it.name.lowercase() },
            line = { ip ->
                val tail = ip.title.takeIf { it.isNotBlank() }
                    ?: ip.text.lineSequence().firstOrNull().orEmpty().take(50)
                if (tail.isBlank()) ip.name else "${ip.name} · $tail"
            },
            itemKey = { it.id },
            // Tap → straight to the edit screen (the read-only view is skipped);
            // 👯 / 🗑 (non-fixed categories) live on the edit bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = if (fixedList) null else ({ mode = Mode.Add }),
            onBack = onBack,
            emptyMessage = "No ${label.lowercase()} configured",
            rowTrailing = { ip ->
                if (hasTrace(ip)) {
                    Text(
                        com.ai.data.MetadataIconsHolder.current.traces,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable { onNavigateToTraceCategory(traceCat(ip)) }
                            .padding(start = 8.dp, end = 2.dp)
                    )
                }
            }
        )
        // 🗑 delete only for non-fixed categories (built-in templates can't be
        // removed); the edit screen also gates this internally.
        is Mode.Edit -> form(m.item, onDelete = if (fixedList) null else ({ confirmDelete = m.item }))
        Mode.Add -> form(null)
    }

    confirmDelete?.let { ip ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${categoryDisplayName(category).lowercase().removeSuffix("s")}?") },
            text = { Text("Delete “${ip.name}”? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; remove(ip); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
