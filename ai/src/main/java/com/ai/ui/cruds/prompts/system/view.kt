package com.ai.ui.cruds.prompts.system

import androidx.compose.runtime.Composable
import com.ai.model.SystemPrompt
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage

@Composable
internal fun SystemPromptView(
    item: SystemPrompt,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "System prompt",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = item.name,
        helpTopic = "system_prompts"
    ) {
        CrudField("Name", item.name)
        CrudField("Prompt", item.prompt.ifBlank { "(empty)" })
    }
}
