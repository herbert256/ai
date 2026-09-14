package com.ai.ui.cruds.prompts.defaults

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.DefaultPrompt

@Composable
internal fun DefaultPromptAdd(
    aiSettings: Settings,
    onSaved: (DefaultPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = DefaultPromptEditForm(null, aiSettings, onSaved, onBack, onNavigateHome)
