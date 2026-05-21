package com.ai.ui.cruds.models.inaccessible

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.InaccessibleModel
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudFormScaffold
import com.ai.ui.shared.AppColors

@Composable
internal fun InaccessibleModelEdit(
    item: InaccessibleModel,
    aiSettings: Settings,
    onSaved: (InaccessibleModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = InaccessibleModelForm(item, aiSettings, isAdd = false, onSaved, onBack, onNavigateHome)

@Composable
internal fun InaccessibleModelForm(
    initial: InaccessibleModel?,
    aiSettings: Settings,
    isAdd: Boolean,
    onSaved: (InaccessibleModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var providerId by remember { mutableStateOf(initial?.providerId ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var reason by remember { mutableStateOf(initial?.reason ?: "Manually added") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        com.ai.ui.other.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick inaccessible model",
            onConfirm = { (provider, m) -> providerId = provider.id; model = m; showPicker = false },
            onBack = { showPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    val hasModel = providerId.isNotBlank() && model.isNotBlank()
    CrudFormScaffold(
        title = if (isAdd) "Add inaccessible model" else "Edit inaccessible model",
        isAdd = isAdd,
        saveEnabled = hasModel,
        onSave = { onSaved(InaccessibleModel(providerId, model, reason.trim().ifBlank { "Manually added" })) },
        onBack = onBack,
        helpTopic = "inaccessible_models"
    ) {
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (hasModel) "$providerId · $model" else "Pick model…",
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = reason, onValueChange = { reason = it },
            label = { Text("Reason") },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )
    }
}
