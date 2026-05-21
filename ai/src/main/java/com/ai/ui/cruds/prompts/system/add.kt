package com.ai.ui.cruds.prompts.system

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.SystemPrompt

@Composable
internal fun SystemPromptAdd(
    aiSettings: Settings,
    onSaved: (SystemPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = SystemPromptEditForm(null, aiSettings, onSaved, onBack, onNavigateHome)
