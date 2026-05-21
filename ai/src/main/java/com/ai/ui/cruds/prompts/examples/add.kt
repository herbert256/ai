package com.ai.ui.cruds.prompts.examples

import androidx.compose.runtime.Composable
import com.ai.model.ExamplePrompt

@Composable
internal fun ExamplePromptAdd(
    onSaved: (ExamplePrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = ExamplePromptEditForm(null, onSaved, onBack, onNavigateHome)
