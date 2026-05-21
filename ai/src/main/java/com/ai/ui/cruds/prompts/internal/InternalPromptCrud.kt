package com.ai.ui.cruds.prompts.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudListPage
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.settings.InternalPromptEditScreen
import com.ai.ui.settings.categoryDisplayName
import java.util.Locale
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: InternalPrompt) : Mode
    data class Edit(val item: InternalPrompt) : Mode
    data object Add : Mode
}

/**
 * Uniform CRUD for the internal prompts of one [category]
 * (meta / fan_out / fan_in / fan-in-model / icons / internal). The
 * `internal` and `icons` categories are FIXED lists: rows are editable
 * but can't be added, copied or deleted (they're built-in templates).
 *
 * Edit/add reuse the existing rich [InternalPromptEditScreen].
 */
@Composable
fun InternalPromptCrud(
    aiSettings: Settings,
    category: String,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val fixedList = category == "internal" || category == "icons"
    val label = categoryDisplayName(category)
    var mode by remember(category) { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }

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
    fun form(initial: InternalPrompt?) = key(initial?.id) {
        InternalPromptEditScreen(
            internalPrompt = initial,
            existingNames = existingNames(initial?.id ?: ""),
            agentNames = aiSettings.agents.map { it.name },
            fixedCategory = category,
            onSave = { saved -> upsert(saved); toList() },
            onBack = toList,
            onNavigateHome = onNavigateHome
        )
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = label,
            helpTopic = "internal_prompts_list",
            items = aiSettings.internalPrompts.filter { it.category == category }.sortedBy { it.name.lowercase() },
            line = { ip ->
                val tail = ip.title.takeIf { it.isNotBlank() }
                    ?: ip.text.lineSequence().firstOrNull().orEmpty().take(50)
                if (tail.isBlank()) ip.name else "${ip.name} · $tail"
            },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = if (fixedList) null else ({ mode = Mode.Add }),
            onBack = onBack,
            emptyMessage = "No ${label.lowercase()} configured"
        )
        is Mode.View -> CrudViewPage(
            title = label.removeSuffix("s"),
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = if (fixedList) null else ({ mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) }),
            onDelete = if (fixedList) null else ({ remove(m.item); toList() }),
            onBack = toList,
            deleteName = m.item.name,
            helpTopic = "internal_prompts_list"
        ) {
            CrudField("Name", m.item.name)
            if (m.item.title.isNotBlank()) CrudField("Title", m.item.title)
            CrudField("Category", categoryDisplayName(m.item.category))
            if (m.item.agent.isNotBlank() && m.item.agent != "*select" && m.item.agent != "*n/a")
                CrudField("Agent", m.item.agent)
            if (m.item.reference) CrudField("Reference", "Appends reference legend")
            CrudField("Template", m.item.text.ifBlank { "(empty)" })
        }
        is Mode.Edit -> form(m.item)
        Mode.Add -> form(null)
    }
}
