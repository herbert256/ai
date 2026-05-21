package com.ai.ui.cruds.models.cooldowns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ai.data.AppService
import com.ai.data.ModelCooldownStore
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudFormScaffold
import com.ai.ui.shared.AppColors

@Composable
internal fun CooldownEdit(
    item: ModelCooldownStore.CooldownEntry,
    aiSettings: Settings,
    onSaved: (providerId: String, model: String, untilMs: Long) -> Unit,
    onBack: () -> Unit
) = CooldownForm(item, aiSettings, isAdd = false, onSaved, onBack)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CooldownForm(
    initial: ModelCooldownStore.CooldownEntry?,
    aiSettings: Settings,
    isAdd: Boolean,
    onSaved: (providerId: String, model: String, untilMs: Long) -> Unit,
    onBack: () -> Unit
) {
    val allProviders = remember { AppService.entries.sortedBy { it.id } }
    var providerId by remember { mutableStateOf(initial?.providerId ?: allProviders.firstOrNull()?.id ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
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

    val knownModels = remember(providerId, aiSettings) {
        AppService.findById(providerId)?.let { aiSettings.getProvider(it).models.sorted() } ?: emptyList()
    }
    val hours = hoursText.trim().toLongOrNull()
    val canSave = providerId.isNotBlank() && model.trim().isNotBlank() && hours != null && hours > 0

    CrudFormScaffold(
        title = if (isAdd) "Add cooldown" else "Edit cooldown",
        isAdd = isAdd,
        saveEnabled = canSave,
        onSave = {
            val h = hoursText.trim().toLongOrNull() ?: return@CrudFormScaffold
            onSaved(providerId, model.trim(), System.currentTimeMillis() + h * 3_600_000L)
        },
        onBack = onBack,
        helpTopic = "model_cooldowns"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = !providerExpanded }) {
                OutlinedTextField(
                    value = providerId, onValueChange = {}, readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                    allProviders.forEach { p ->
                        DropdownMenuItem(text = { Text(p.id) }, onClick = {
                            if (providerId != p.id) model = ""
                            providerId = p.id; providerExpanded = false
                        })
                    }
                }
            }
            ExposedDropdownMenuBox(
                expanded = modelExpanded && knownModels.isNotEmpty(),
                onExpandedChange = { if (knownModels.isNotEmpty()) modelExpanded = !modelExpanded }
            ) {
                OutlinedTextField(
                    value = model, onValueChange = {}, readOnly = true,
                    label = { Text("Model") },
                    placeholder = { Text(if (knownModels.isEmpty()) "No models — fetch this provider first" else "Pick a model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, knownModels.isNotEmpty()),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    knownModels.forEach { mm ->
                        DropdownMenuItem(text = { Text(mm) }, onClick = { model = mm; modelExpanded = false })
                    }
                }
            }
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
    }
}
