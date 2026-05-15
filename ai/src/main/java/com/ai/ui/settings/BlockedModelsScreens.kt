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
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CrudListScreen
import com.ai.ui.shared.TitleBar

/**
 * AI Setup → Blocked models — CRUD list of provider/model pairs the
 * user has flagged. Auto-populated by the "Test all models" sweep and
 * hand-curated here. Blocked pairs render dimmed (still selectable) in
 * every model picker.
 */
@Composable
fun BlockedModelsListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddBlockedModel: () -> Unit,
    onEditBlockedModel: (String) -> Unit
) {
    CrudListScreen(
        title = "Blocked models",
        helpTopic = "blocked_models",
        items = aiSettings.blockedModels,
        addLabel = "Add blocked model",
        emptyMessage = "No blocked models",
        sortKey = { it.key },
        itemTitle = { "${it.providerId} · ${it.model}" },
        itemSubtitle = { it.reason.ifBlank { "(no reason)" } },
        onAdd = onAddBlockedModel,
        onEdit = { onEditBlockedModel(it.key) },
        onDelete = { onSave(aiSettings.removeBlockedModel(it.providerId, it.model)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Blocked model",
        deleteEntityName = { "${it.providerId} · ${it.model}" },
        itemKey = { it.key }
    )
}

/** Add / edit one blocked model. Mirrors [SwarmEditScreen]'s overlay
 *  use of the New-Report "+model" picker. */
@Composable
fun BlockedModelEditScreen(
    item: BlockedModel?,
    aiSettings: Settings,
    onSave: (BlockedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = item != null

    var providerId by remember { mutableStateOf(item?.providerId ?: "") }
    var model by remember { mutableStateOf(item?.model ?: "") }
    var reason by remember { mutableStateOf(item?.reason ?: "") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        // Same picker the New Report's "+Model" button uses. Blocked
        // pairs render dimmed inside it but stay selectable.
        com.ai.ui.report.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick model to block",
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
            helpTopic = "blocked_model_edit",
            title = if (isEditing) "Edit blocked model" else "Add blocked model",
            subject = if (hasModel) "$providerId · $model" else "",
            onBackClick = onBack
        )

        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
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

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(BlockedModel(providerId, model, reason.trim())) },
                enabled = hasModel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text(if (isEditing) "Save" else "Add", maxLines = 1, softWrap = false) }
        }
    }
}
