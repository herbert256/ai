package com.ai.ui.cruds.prompts.examples

import androidx.compose.runtime.Composable
import com.ai.model.ExamplePrompt
import com.ai.ui.settings.ExamplePromptEditScreen

@Composable
internal fun ExamplePromptEdit(
    item: ExamplePrompt,
    onSaved: (ExamplePrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = ExamplePromptEditForm(item, onSaved, onBack, onNavigateHome)

@Composable
internal fun ExamplePromptEditForm(
    item: ExamplePrompt?,
    onSaved: (ExamplePrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    ExamplePromptEditScreen(
        examplePrompt = item,
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}
