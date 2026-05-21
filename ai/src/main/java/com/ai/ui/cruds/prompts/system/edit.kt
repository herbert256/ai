package com.ai.ui.cruds.prompts.system

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.SystemPrompt
import com.ai.ui.settings.SystemPromptEditScreen
import java.util.Locale

@Composable
internal fun SystemPromptEdit(
    item: SystemPrompt,
    aiSettings: Settings,
    onSaved: (SystemPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = SystemPromptEditForm(item, aiSettings, onSaved, onBack, onNavigateHome)

@Composable
internal fun SystemPromptEditForm(
    item: SystemPrompt?,
    aiSettings: Settings,
    onSaved: (SystemPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    SystemPromptEditScreen(
        systemPrompt = item,
        existingNames = aiSettings.systemPrompts
            .filter { it.id != (item?.id ?: "") }
            .map { it.name.lowercase(Locale.ROOT) }.toSet(),
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}
