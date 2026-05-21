package com.ai.ui.cruds.models.manualoverrides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ModelType
import com.ai.model.ModelTypeOverride
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudFormScaffold
import com.ai.ui.shared.AppColors
import java.util.UUID

@Composable
internal fun ManualOverrideEdit(
    item: ModelTypeOverride,
    aiSettings: Settings,
    onSaved: (ModelTypeOverride) -> Unit,
    onBack: () -> Unit
) = ManualOverrideForm(item, aiSettings, isAdd = false, onSaved, onBack)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualOverrideForm(
    initial: ModelTypeOverride?,
    aiSettings: Settings,
    isAdd: Boolean,
    onSaved: (ModelTypeOverride) -> Unit,
    onBack: () -> Unit
) {
    val allProviders = remember { AppService.entries.sortedBy { it.id } }
    var providerId by remember { mutableStateOf(initial?.providerId ?: allProviders.firstOrNull()?.id ?: "") }
    var modelId by remember { mutableStateOf(initial?.modelId ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ModelType.CHAT) }
    var supportsVision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var supportsWebSearch by remember { mutableStateOf(initial?.supportsWebSearch ?: false) }
    var supportsReasoning by remember { mutableStateOf(initial?.supportsReasoning ?: false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val knownModels = remember(providerId, aiSettings) {
        AppService.findById(providerId)?.let { aiSettings.getProvider(it).models.sorted() } ?: emptyList()
    }
    val canSave = providerId.isNotBlank() && modelId.trim().isNotBlank()

    CrudFormScaffold(
        title = if (isAdd) "Add override" else "Edit override",
        isAdd = isAdd,
        saveEnabled = canSave,
        onSave = {
            onSaved(
                ModelTypeOverride(
                    id = if (isAdd || initial == null) UUID.randomUUID().toString() else initial.id,
                    providerId = providerId, modelId = modelId.trim(), type = type,
                    supportsVision = supportsVision, supportsWebSearch = supportsWebSearch,
                    supportsReasoning = supportsReasoning
                )
            )
        },
        onBack = onBack,
        helpTopic = "manual_model_types"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = !providerExpanded }) {
                OutlinedTextField(
                    value = providerId, onValueChange = {}, readOnly = true, label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                    allProviders.forEach { p ->
                        DropdownMenuItem(text = { Text(p.id) }, onClick = {
                            if (providerId != p.id) modelId = ""
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
                    value = modelId, onValueChange = {}, readOnly = true, label = { Text("Model") },
                    placeholder = { Text(if (knownModels.isEmpty()) "No models — fetch this provider first" else "Pick a model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, knownModels.isNotEmpty()),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    knownModels.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { modelId = m; modelExpanded = false }) }
                }
            }
            Text("Type", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModelType.ALL.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t, fontSize = 11.sp) })
                        }
                    }
                }
            }
            Text("Capabilities", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            CapRow("Supports vision (image input) 👁", supportsVision) { supportsVision = it }
            CapRow("Supports web-search tool 🌐", supportsWebSearch) { supportsWebSearch = it }
            CapRow("Supports thinking 🧠", supportsReasoning) { supportsReasoning = it }
        }
    }
}

@Composable
private fun CapRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
