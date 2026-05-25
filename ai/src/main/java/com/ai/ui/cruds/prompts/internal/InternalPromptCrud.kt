package com.ai.ui.cruds.prompts.internal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudListPage
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.settings.InternalPromptEditScreen
import com.ai.ui.settings.categoryDisplayName
import com.ai.ui.settings.categorySingularName
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
 * (meta / fan_out / fan_in / fan-in-model / icons / internal / info /
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
    fun form(initial: InternalPrompt?) = key(initial?.id) {
        InternalPromptEditScreen(
            internalPrompt = initial,
            existingNames = existingNames(initial?.id ?: ""),
            agentNames = aiSettings.agents.map { it.name },
            aiSettings = aiSettings,
            fixedCategory = category,
            onSave = { saved -> upsert(saved); toList() },
            onBack = toList,
            onNavigateHome = onNavigateHome,
            onTrace = initial?.takeIf { hasTrace(it) }?.let { ip -> { onNavigateToTraceCategory(traceCat(ip)) } }
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
            onView = { mode = Mode.View(it) },
            onAdd = if (fixedList) null else ({ mode = Mode.Add }),
            onBack = onBack,
            emptyMessage = "No ${label.lowercase()} configured",
            rowTrailing = { ip ->
                if (hasTrace(ip)) {
                    Text(
                        "🐞",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable { onNavigateToTraceCategory(traceCat(ip)) }
                            .padding(start = 8.dp, end = 2.dp)
                    )
                }
            }
        )
        is Mode.View -> CrudViewPage(
            title = categorySingularName(category),
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = if (fixedList) null else ({ mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) }),
            onDelete = if (fixedList) null else ({ remove(m.item); toList() }),
            onBack = toList,
            deleteName = m.item.name,
            helpTopic = "crud_internal_prompts",
            onTrace = if (hasTrace(m.item)) ({ onNavigateToTraceCategory(traceCat(m.item)) }) else null
        ) {
            CrudField("Name", m.item.name)
            if (m.item.title.isNotBlank()) CrudField("Title", m.item.title)
            CrudField("Category", categoryDisplayName(m.item.category))
            if (m.item.workers.isNotEmpty())
                CrudField("Workers", m.item.workers.mapIndexed { i, w ->
                    val pick = if (w.agent != "*N/A" && w.agent.isNotBlank()) w.agent else "${w.provider} / ${w.model}"
                    "${i + 1}. $pick"
                }.joinToString("\n"))
            else if (!m.item.provider.isNullOrBlank() && !m.item.model.isNullOrBlank())
                CrudField("Provider / Model", "${m.item.provider} / ${m.item.model}")
            else if (m.item.agent.isNotBlank() && m.item.agent != "*select" && m.item.agent != "*n/a")
                CrudField("Agent", m.item.agent)
            if (m.item.reference) CrudField("Reference", "Appends reference legend")
            if (m.item.parameters != "*NONE") CrudField("Parameters", m.item.parameters)
            if (m.item.systemPrompt != "*NONE") CrudField("System prompt", m.item.systemPrompt)
            CrudField("Template", m.item.text.ifBlank { "(empty)" })
        }
        is Mode.Edit -> form(m.item)
        Mode.Add -> form(null)
    }
}
