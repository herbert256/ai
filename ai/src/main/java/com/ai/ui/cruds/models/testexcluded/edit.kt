package com.ai.ui.cruds.models.testexcluded

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ai.model.Settings
import com.ai.model.TestExcludedModel
import com.ai.ui.cruds.framework.CrudFormScaffold

@Composable
internal fun TestExcludedModelEdit(
    item: TestExcludedModel,
    aiSettings: Settings,
    onSaved: (TestExcludedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = TestExcludedModelForm(item, aiSettings, isAdd = false, onSaved, onBack, onNavigateHome)

@Composable
internal fun TestExcludedModelForm(
    initial: TestExcludedModel?,
    aiSettings: Settings,
    isAdd: Boolean,
    onSaved: (TestExcludedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var providerId by remember { mutableStateOf(initial?.providerId ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        com.ai.ui.other.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick model to exclude from Test all models",
            onConfirm = { (provider, m) -> providerId = provider.id; model = m; showPicker = false },
            onBack = { showPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    val hasModel = providerId.isNotBlank() && model.isNotBlank()
    CrudFormScaffold(
        title = if (isAdd) "Add test-excluded model" else "Edit test-excluded model",
        isAdd = isAdd,
        saveEnabled = hasModel,
        onSave = { onSaved(TestExcludedModel(providerId, model)) },
        onBack = onBack,
        helpTopic = "test_excluded_models"
    ) {
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (hasModel) "$providerId · $model" else "Pick model…",
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
