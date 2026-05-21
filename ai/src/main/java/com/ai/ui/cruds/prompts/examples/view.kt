package com.ai.ui.cruds.prompts.examples

import androidx.compose.runtime.Composable
import com.ai.model.ExamplePrompt
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage

@Composable
internal fun ExamplePromptView(
    item: ExamplePrompt,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Example prompt",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = item.title.ifBlank { "(untitled)" },
        helpTopic = "example_prompts"
    ) {
        CrudField("Title", item.title.ifBlank { "(untitled)" })
        CrudField("Text", item.text.ifBlank { "(empty)" })
    }
}
