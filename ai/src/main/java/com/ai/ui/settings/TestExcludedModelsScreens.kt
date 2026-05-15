package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.ai.model.Settings
import com.ai.model.TestExcludedModel
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CrudListScreen
import com.ai.ui.shared.TitleBar

/**
 * AI Setup → Test-excluded models — CRUD list of provider/model pairs
 * the "Test all models" sweep skips. Auto-populated when a probe costs
 * more than 5¢; hand-curated here.
 */
@Composable
fun TestExcludedModelsListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddTestExcluded: () -> Unit,
    onEditTestExcluded: (String) -> Unit
) {
    CrudListScreen(
        title = "Test-excluded models",
        helpTopic = "test_excluded_models",
        items = aiSettings.testExcludedModels,
        addLabel = "Add test-excluded model",
        emptyMessage = "No test-excluded models",
        sortKey = { it.key },
        itemTitle = { "${it.providerId} · ${it.model}" },
        itemSubtitle = { "Skipped by Test all models" },
        onAdd = onAddTestExcluded,
        onEdit = { onEditTestExcluded(it.key) },
        onDelete = { onSave(aiSettings.removeTestExcluded(it.providerId, it.model)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Test-excluded model",
        deleteEntityName = { "${it.providerId} · ${it.model}" },
        itemKey = { it.key }
    )
}

/** Add / edit one test-excluded entry. Mirrors [BlockedModelEditScreen]
 *  minus the reason field. */
@Composable
fun TestExcludedModelEditScreen(
    item: TestExcludedModel?,
    aiSettings: Settings,
    onSave: (TestExcludedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = item != null

    var providerId by remember { mutableStateOf(item?.providerId ?: "") }
    var model by remember { mutableStateOf(item?.model ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        com.ai.ui.report.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick model to exclude from Test all models",
            onConfirm = { (provider, m) ->
                providerId = provider.id
                model = m
                showPicker = false
            },
            onBack = { showPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    val hasModel = providerId.isNotBlank() && model.isNotBlank()

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "test_excluded_model_edit",
            title = if (isEditing) "Edit test-excluded model" else "Add test-excluded model",
            subject = if (hasModel) "$providerId · $model" else "",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (hasModel) "$providerId · $model" else "Pick model…",
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(TestExcludedModel(providerId, model)) },
                enabled = hasModel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text(if (isEditing) "Save" else "Add", maxLines = 1, softWrap = false) }
        }
    }
}
