package com.ai.ui.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Settings
import com.ai.ui.navigation.NavRoutes

/**
 * Full-screen replacements for the old `ParametersSelectorDialog` /
 * `SystemPromptSelectorDialog`. Opened from the 🌡️ / 🎭 bottom-bar
 * icons everywhere they appear. Each shows the currently active
 * selection at the top, then the list of presets (paged by swipe when
 * it overflows the screen; selection is by tapping a row). The 🗑
 * bottom-bar icon clears the selection; the ✏️ icon opens the full
 * Parameters / System-prompts CRUD management screen.
 *
 * The selection contract mirrors the dialogs exactly so every call
 * site keeps its existing state + apply lambda:
 *   • Parameters  — multi-select: `selectedIds` + `onConfirm`.
 *   • System prompt — single-select: `selectedId` + `onSelect`.
 * Both apply live (on each tap and on trash).
 */
@Composable
fun ParametersSelectScreen(
    aiSettings: Settings,
    selectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val navigate = LocalNavigateToRoute.current
    val selected = selectedIds.toSet()
    val activeNames = aiSettings.parameters.filter { it.id in selected }.joinToString(", ") { it.name }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "select_parameters",
            title = "Configure API parameters",
            subject = if (activeNames.isNotEmpty()) "Current active: $activeNames" else null,
            onBackClick = onBack,
            onDelete = if (selected.isNotEmpty()) ({ onConfirm(emptyList()) }) else null,
            onEdit = { navigate(NavRoutes.SETTINGS_PARAMETERS) }
        )
        PresetPagedList(
            count = aiSettings.parameters.size,
            emptyMessage = "No parameter presets configured"
        ) { index ->
            val p = aiSettings.parameters[index]
            val isChecked = p.id in selected
            val details = listOfNotNull(
                p.temperature?.let { "temp=$it" },
                p.maxTokens?.let { "max=$it" },
                p.topP?.let { "topP=$it" }
            ).joinToString(", ")
            PresetRow(
                name = p.name,
                detail = details,
                selected = isChecked,
                multiSelect = true,
                onClick = {
                    val next = if (isChecked) selected - p.id else selected + p.id
                    onConfirm(next.toList())
                }
            )
        }
    }
}

@Composable
fun SystemPromptSelectScreen(
    aiSettings: Settings,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val navigate = LocalNavigateToRoute.current
    val activeName = selectedId?.let { id -> aiSettings.systemPrompts.firstOrNull { it.id == id }?.name }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "select_system_prompt",
            title = "Define AI model system prompt",
            subject = activeName?.let { "Current active: $it" },
            onBackClick = onBack,
            onDelete = if (selectedId != null) ({ onSelect(null) }) else null,
            onEdit = { navigate(NavRoutes.SETTINGS_SYSTEM_PROMPTS) }
        )
        PresetPagedList(
            count = aiSettings.systemPrompts.size,
            emptyMessage = "No system prompts configured"
        ) { index ->
            val sp = aiSettings.systemPrompts[index]
            PresetRow(
                name = sp.name,
                detail = sp.prompt.take(80),
                selected = selectedId == sp.id,
                multiSelect = false,
                onClick = { onSelect(sp.id) }
            )
        }
    }
}

/**
 * Shared paged list body. Renders as many fixed-height rows as fit the
 * screen; swipe (horizontal or vertical) pages through the rest. Mirrors
 * [com.ai.ui.cruds.framework.CrudListPage]'s paging so the gesture feel
 * matches the rest of the app.
 */
@Composable
private fun PresetPagedList(
    count: Int,
    emptyMessage: String,
    row: @Composable (index: Int) -> Unit
) {
    if (count == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyMessage, color = AppColors.TextTertiary, fontSize = 16.sp)
        }
        return
    }
    val rowHeight = 64.dp
    val indicatorReserve = 28.dp
    var page by remember(count) { mutableStateOf(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageSize = maxOf(1, ((maxHeight - indicatorReserve) / rowHeight).toInt())
        val totalPages = (count + pageSize - 1) / pageSize
        val safePage = page.coerceIn(0, totalPages - 1)
        val start = safePage * pageSize
        val end = minOf(start + pageSize, count)

        Column(
            modifier = Modifier.fillMaxSize()
                .horizontalSwipeNavigation(
                    key1 = safePage, key2 = totalPages,
                    atFirst = safePage <= 0, atLast = safePage >= totalPages - 1,
                    onSwipeLeft = { if (safePage < totalPages - 1) page = safePage + 1 },
                    onSwipeRight = { if (safePage > 0) page = safePage - 1 }
                )
                .verticalSwipeNavigation(
                    key1 = safePage, key2 = totalPages,
                    atFirst = safePage <= 0, atLast = safePage >= totalPages - 1,
                    onSwipeUp = { if (safePage > 0) page = safePage - 1 },
                    onSwipeDown = { if (safePage < totalPages - 1) page = safePage + 1 }
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (totalPages > 1) {
                Text(
                    "Page ${safePage + 1} / $totalPages · $count presets · swipe to page · tap to select",
                    color = AppColors.TextTertiary, fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                )
            }
            for (i in start until end) row(i)
        }
    }
}

@Composable
private fun PresetRow(
    name: String,
    detail: String,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AppColors.IndigoHighlight else AppColors.CardBackgroundAlt
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (multiSelect) Checkbox(checked = selected, onCheckedChange = { onClick() })
            else RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) {
                    Text(detail, color = AppColors.TextTertiary, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
