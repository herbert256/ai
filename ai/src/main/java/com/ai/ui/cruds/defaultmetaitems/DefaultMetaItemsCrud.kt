package com.ai.ui.cruds.defaultmetaitems

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.DefaultMetaItem
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudFormScaffold
import com.ai.ui.cruds.framework.CrudListPage
import com.ai.ui.shared.AppColors
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class Edit(val item: DefaultMetaItem) : Mode
    data object Add : Mode
}

/** Human-readable target of a [DefaultMetaItem]: provider+model wins,
 *  else the agent name, else a placeholder. */
private fun DefaultMetaItem.targetLabel(): String = when {
    providerName.isNotBlank() && modelName.isNotBlank() -> "$providerName / $modelName"
    agentName.isNotBlank() -> "agent: $agentName"
    else -> "(no target)"
}

/**
 * CRUD for [Settings.defaultMetaItems] — the rows that auto-run a
 * category-`meta` Internal Prompt against a target when a report's
 * agents all finish. Modeled on the Internal Prompt CRUD + the generic
 * CRUD framework. Reached from AI Setup → "Default meta items".
 */
@Composable
fun DefaultMetaItemsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }

    val upsert: (DefaultMetaItem) -> Unit = { saved ->
        val list = aiSettings.defaultMetaItems
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(defaultMetaItems = updated))
    }
    val remove: (DefaultMetaItem) -> Unit = { item ->
        onSave(aiSettings.copy(defaultMetaItems = aiSettings.defaultMetaItems.filterNot { it.id == item.id }))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Default meta items",
            subject = "Meta prompts auto-run when a report finishes",
            helpTopic = "crud_default_meta_items",
            items = aiSettings.defaultMetaItems.sortedBy { it.metaName.lowercase() },
            line = { "${if (it.active) "" else "⛔ "}${it.metaName} · ${it.targetLabel()}" },
            itemKey = { it.id },
            // Tap → straight to the edit screen (the read-only view is
            // skipped); 👯 / 🗑 live on the edit bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No default meta items configured"
        )
        is Mode.Edit -> key(m.item.id) {
            DefaultMetaItemForm(m.item, aiSettings, isAdd = false,
                onSaved = { upsert(it) }, onBack = toList, onNavigateHome = onNavigateHome,
                onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString())) },
                onDelete = { remove(m.item); toList() })
        }
        Mode.Add -> DefaultMetaItemForm(null, aiSettings, isAdd = true,
            onSaved = { upsert(it) }, onBack = toList, onNavigateHome = onNavigateHome)
    }
}

@Composable
private fun DefaultMetaItemForm(
    initial: DefaultMetaItem?,
    aiSettings: Settings,
    isAdd: Boolean,
    onSaved: (DefaultMetaItem) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val metaNames = remember(aiSettings) {
        aiSettings.internalPrompts.filter { it.category == "meta" }.map { it.name }.sortedBy { it.lowercase() }
    }
    val agentNames = remember(aiSettings) { aiSettings.agents.map { it.name }.sortedBy { it.lowercase() } }

    var resetTick by remember { mutableStateOf(0) }
    var metaName by remember(resetTick) { mutableStateOf(initial?.metaName ?: "") }
    var agentName by remember(resetTick) { mutableStateOf(initial?.agentName ?: "") }
    var providerName by remember(resetTick) { mutableStateOf(initial?.providerName ?: "") }
    var modelName by remember(resetTick) { mutableStateOf(initial?.modelName ?: "") }
    var active by remember(resetTick) { mutableStateOf(initial?.active ?: true) }
    var metaMenuOpen by remember { mutableStateOf(false) }
    var agentMenuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        com.ai.ui.other.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick provider / model",
            onConfirm = { (provider, m) -> providerName = provider.id; modelName = m; showPicker = false },
            onBack = { showPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    val hasModel = providerName.isNotBlank() && modelName.isNotBlank()
    val saveEnabled = metaName.isNotBlank() && (agentName.isNotBlank() || hasModel)

    CrudFormScaffold(
        title = if (isAdd) "Add default meta item" else "Edit default meta item",
        subject = "Auto-run a meta prompt on report completion",
        isAdd = isAdd,
        current = if (saveEnabled) DefaultMetaItem(id = initial?.id ?: "", metaName = metaName.trim(), agentName = agentName.trim(), providerName = providerName.trim(), modelName = modelName.trim(), active = active) else null,
        onSave = {
            onSaved(
                DefaultMetaItem(
                    id = initial?.id ?: UUID.randomUUID().toString(),
                    metaName = metaName.trim(),
                    agentName = agentName.trim(),
                    providerName = providerName.trim(),
                    modelName = modelName.trim(),
                    active = active
                )
            )
        },
        onBack = onBack,
        helpTopic = "crud_default_meta_items",
        onReset = { resetTick++ },
        onCopy = onCopy,
        onDelete = onDelete,
        deleteName = metaName
    ) {
        // ---- Active flag ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Active", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = active, onCheckedChange = { active = it })
        }
        Text(
            if (active) "Runs automatically when a report finishes." else "Kept but skipped at autostart time.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(12.dp))
        // ---- Meta prompt (required) ----
        Text("Meta prompt", fontSize = 12.sp, color = AppColors.TextTertiary)
        Box {
            OutlinedButton(onClick = { metaMenuOpen = true }, modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()) {
                Text(metaName.ifBlank { "Pick a meta prompt…" }, modifier = Modifier.weight(1f),
                    fontSize = 13.sp, color = if (metaName.isBlank()) AppColors.TextTertiary else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾", color = AppColors.TextTertiary)
            }
            DropdownMenu(expanded = metaMenuOpen, onDismissRequest = { metaMenuOpen = false },
                modifier = Modifier.background(Color(0xFF2D2D2D))) {
                if (metaNames.isEmpty()) DropdownMenuItem(enabled = false,
                    text = { Text("No meta prompts defined", fontSize = 13.sp, color = AppColors.TextTertiary) },
                    onClick = {})
                metaNames.forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n, fontSize = 13.sp, color = if (metaName == n) AppColors.InfoAccent else Color.White) },
                        onClick = { metaName = n; metaMenuOpen = false })
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ---- Target: provider+model (wins) ----
        Text("Target — provider / model (optional)", fontSize = 12.sp, color = AppColors.TextTertiary)
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()) {
            Text(if (hasModel) "$providerName · $modelName" else "Pick provider / model…",
                modifier = Modifier.weight(1f), fontSize = 13.sp,
                color = if (hasModel) Color.White else AppColors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hasModel) Text(com.ai.data.MetadataIconsHolder.current.closeMark, color = AppColors.TextTertiary,
                modifier = Modifier.clickableNoRipple { providerName = ""; modelName = "" })
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ---- Target: agent (used when provider/model is blank) ----
        Text("Target — agent (used when no provider/model set)", fontSize = 12.sp, color = AppColors.TextTertiary)
        Box {
            OutlinedButton(onClick = { agentMenuOpen = true }, modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()) {
                Text(agentName.ifBlank { "(none)" }, modifier = Modifier.weight(1f), fontSize = 13.sp,
                    color = if (agentName.isBlank()) AppColors.TextTertiary else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾", color = AppColors.TextTertiary)
            }
            DropdownMenu(expanded = agentMenuOpen, onDismissRequest = { agentMenuOpen = false },
                modifier = Modifier.background(Color(0xFF2D2D2D))) {
                DropdownMenuItem(
                    text = { Text("(none)", fontSize = 13.sp, color = if (agentName.isBlank()) AppColors.InfoAccent else Color.White) },
                    onClick = { agentName = ""; agentMenuOpen = false })
                agentNames.forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n, fontSize = 13.sp, color = if (agentName == n) AppColors.InfoAccent else Color.White) },
                        onClick = { agentName = n; agentMenuOpen = false })
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "When a report's models all finish, a “$metaName” meta result is auto-created " +
                "using " + (if (hasModel) "$providerName / $modelName" else if (agentName.isNotBlank()) "agent “$agentName”" else "the chosen target") + ".",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
    }
}

/** Tap handler with no ripple, for the inline ✕ clear affordance. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null
    ) { onClick() }
