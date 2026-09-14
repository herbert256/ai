package com.ai.ui.cruds.prompts.defaults

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.DefaultPrompt
import com.ai.ui.settings.DefaultPromptEditScreen
import java.util.Locale

@Composable
internal fun DefaultPromptEdit(
    item: DefaultPrompt,
    aiSettings: Settings,
    onSaved: (DefaultPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onDelete: (() -> Unit)? = null
) = DefaultPromptEditForm(item, aiSettings, onSaved, onBack, onNavigateHome, onDelete)

@Composable
internal fun DefaultPromptEditForm(
    item: DefaultPrompt?,
    aiSettings: Settings,
    onSaved: (DefaultPrompt) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    DefaultPromptEditScreen(
        defaultPrompt = item,
        existingNames = aiSettings.defaultPrompts
            .filter { it.id != (item?.id ?: "") }
            .map { it.name.trim().lowercase(Locale.ROOT) }.toSet(),
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome,
        onDelete = onDelete
    )
}
