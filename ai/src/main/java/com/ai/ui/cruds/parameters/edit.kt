package com.ai.ui.cruds.parameters

import androidx.compose.runtime.Composable
import com.ai.model.Parameters
import com.ai.model.Settings
import com.ai.ui.settings.ParametersEditScreen
import java.util.Locale

@Composable
internal fun ParametersEdit(
    item: Parameters,
    aiSettings: Settings,
    onSaved: (Parameters) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = ParametersEditForm(item, aiSettings, onSaved, onBack, onNavigateHome)

@Composable
internal fun ParametersEditForm(
    item: Parameters?,
    aiSettings: Settings,
    onSaved: (Parameters) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    ParametersEditScreen(
        params = item,
        existingNames = aiSettings.parameters
            .filter { it.id != (item?.id ?: "") }
            .map { it.name.lowercase(Locale.ROOT) }.toSet(),
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}
