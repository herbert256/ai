package com.ai.ui.cruds.models.blocked

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
import com.ai.model.BlockedModel
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudFormScaffold
import com.ai.ui.shared.AppColors

/** Edit an existing blocked model. */
@Composable
internal fun BlockedModelEdit(
    item: BlockedModel,
    aiSettings: Settings,
    onSaved: (BlockedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = BlockedModelForm(item, aiSettings, isAdd = false, onSaved, onBack, onNavigateHome)

/** Shared add/edit form for a blocked model. Picks a (provider, model)
 *  via the app's model picker and an optional reason. */
@Composable
internal fun BlockedModelForm(
    initial: BlockedModel?,
    aiSettings: Settings,
    isAdd: Boolean,
    onSaved: (BlockedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var providerId by remember { mutableStateOf(initial?.providerId ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var reason by remember { mutableStateOf(initial?.reason ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        com.ai.ui.other.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick model to block",
            onConfirm = { (provider, m) -> providerId = provider.id; model = m; showPicker = false },
            onBack = { showPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    val hasModel = providerId.isNotBlank() && model.isNotBlank()
    CrudFormScaffold(
        title = if (isAdd) "Add blocked model" else "Edit blocked model",
        isAdd = isAdd,
        saveEnabled = hasModel,
        onSave = { onSaved(BlockedModel(providerId, model, reason.trim())) },
        onBack = onBack,
        helpTopic = "blocked_model_edit"
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
            label = { Text("Reason for blocking") },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )
    }
}
