package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ModelCooldownStore
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CrudListScreen
import com.ai.ui.shared.DeleteConfirmationDialog
import com.ai.ui.shared.TitleBar

/**
 * AI Setup → Model cooldowns. CRUD list of rate-limited
 * (provider, model) pairs benched by a >1h 429 (and any the user
 * adds manually). Each entry carries the epoch-ms time the model
 * becomes selectable again; the model pickers gray out anything
 * still in cooldown.
 *
 * Writes straight to the [ModelCooldownStore] singleton — the
 * cooldown map is not part of [Settings], so there's no onSave.
 */
@Composable
fun ModelCooldownsScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTrace: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    var editing by remember { mutableStateOf<ModelCooldownStore.CooldownEntry?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    // Recompute off the cooldowns StateFlow, but read full entries
    // (with traceFile) from the store — every traceMap mutation is
    // paired with a cooldownMap publish, so this key stays in sync.
    val cooldownMap by ModelCooldownStore.cooldowns.collectAsState()
    val items = remember(cooldownMap) { ModelCooldownStore.entries() }

    if (addingNew || editing != null) {
        ModelCooldownEditScreen(
            initial = editing,
            aiSettings = aiSettings,
            onCancel = { editing = null; addingNew = false },
            onSave = { providerId, model, untilMs ->
                ModelCooldownStore.markUnavailable(providerId, model, untilMs)
                editing = null
                addingNew = false
            }
        )
        return
    }

    CrudListScreen(
        title = "Model cooldowns",
        helpTopic = "model_cooldowns_list",
        items = items,
        addLabel = "+ Add cooldown",
        emptyMessage = "No cooldowns. Models rate-limited by a >1h 429 land here automatically; you can also add one manually.",
        sortKey = { "${it.providerId}/${it.model}" },
        itemTitle = { "${it.providerId} / ${com.ai.ui.shared.shortModelName(it.model)}" },
        itemSubtitle = {
            if (it.availableAtMs > System.currentTimeMillis())
                ModelCooldownStore.cooldownCaption(it.availableAtMs)
            else "expired — tap ✕ to clear"
        },
        onAdd = { addingNew = true },
        onEdit = { editing = it },
        onDelete = { ModelCooldownStore.remove(it.providerId, it.model) },
        onBack = onBack,
        onHome = onNavigateHome,
        deleteEntityType = "Cooldown",
        deleteEntityName = { "${it.providerId}/${it.model}" },
        itemKey = { "${it.providerId}:${it.model}" },
        // 🐞 → the API trace of the 429 call that produced this
        // cooldown. Shown only when tracing is on and a trace was
        // captured (manually-added cooldowns have none).
        itemTrailing = { entry ->
            val tf = entry.traceFile
            if (ApiTracer.isTracingEnabled && tf != null) {
                Text(
                    "🐞", fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { onNavigateToTrace(tf) }
                        .padding(horizontal = 6.dp)
                )
            }
        },
        headerContent = {
            if (items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmClearAll = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Clear all", maxLines = 1, softWrap = false, color = AppColors.Red) }
            }
        }
    )

    if (confirmClearAll) {
        DeleteConfirmationDialog(
            entityType = "all cooldowns",
            entityName = "${items.size} entr${if (items.size == 1) "y" else "ies"}",
            onConfirm = {
                ModelCooldownStore.clearAll()
                confirmClearAll = false
            },
            onDismiss = { confirmClearAll = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelCooldownEditScreen(
    initial: ModelCooldownStore.CooldownEntry?,
    aiSettings: Settings,
    onCancel: () -> Unit,
    onSave: (providerId: String, model: String, untilMs: Long) -> Unit
) {
    BackHandler { onCancel() }

    val allProviders = remember { AppService.entries.sortedBy { it.id } }
    var providerId by remember {
        mutableStateOf(initial?.providerId ?: allProviders.firstOrNull()?.id ?: "")
    }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    // Default 24h for a new entry; for an edit, prefill the hours
    // still remaining (rounded up, min 1).
    var hoursText by remember {
        mutableStateOf(
            if (initial == null) "24"
            else {
                val remainMs = initial.availableAtMs - System.currentTimeMillis()
                ((remainMs + 3_600_000L - 1) / 3_600_000L).coerceAtLeast(1).toString()
            }
        )
    }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    // Provider + model are the cooldown key — fixed once created.
    val keyLocked = initial != null
    val knownModels = remember(providerId, aiSettings) {
        AppService.findById(providerId)?.let { aiSettings.getProvider(it).models.sorted() } ?: emptyList()
    }
    val hours = hoursText.trim().toLongOrNull()
    val canSave = providerId.isNotBlank() && model.trim().isNotBlank() && hours != null && hours > 0

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "model_cooldowns",
            title = if (initial == null) "Add cooldown" else "Edit cooldown",
            onBackClick = onCancel
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Provider picker — locked on edit (it's part of the key).
            ExposedDropdownMenuBox(
                expanded = providerExpanded && !keyLocked,
                onExpandedChange = { if (!keyLocked) providerExpanded = !providerExpanded }
            ) {
                OutlinedTextField(
                    value = providerId,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = {
                        if (!keyLocked) ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                    },
                    modifier = Modifier.fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, !keyLocked),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                    allProviders.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.id) },
                            onClick = {
                                if (providerId != p.id) model = ""
                                providerId = p.id
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            // Model picker — locked on edit; dropdown over the
            // provider's known models otherwise.
            ExposedDropdownMenuBox(
                expanded = modelExpanded && !keyLocked && knownModels.isNotEmpty(),
                onExpandedChange = { if (!keyLocked && knownModels.isNotEmpty()) modelExpanded = !modelExpanded }
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Model") },
                    placeholder = { Text(if (knownModels.isEmpty()) "No models — fetch this provider first" else "Pick a model") },
                    trailingIcon = {
                        if (!keyLocked) ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                    },
                    modifier = Modifier.fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, !keyLocked && knownModels.isNotEmpty()),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    knownModels.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = { model = m; modelExpanded = false }
                        )
                    }
                }
            }

            // Cooldown duration from now.
            OutlinedTextField(
                value = hoursText,
                onValueChange = { hoursText = it.filter { c -> c.isDigit() } },
                label = { Text("Available again in (hours)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedFieldColors()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Cancel", maxLines = 1, softWrap = false) }
            Button(
                onClick = {
                    val h = hoursText.trim().toLongOrNull() ?: return@Button
                    onSave(providerId, model.trim(), System.currentTimeMillis() + h * 3_600_000L)
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("Save", maxLines = 1, softWrap = false, color = Color.White) }
        }
    }
}
