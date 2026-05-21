package com.ai.ui.cruds.workers.flocks

import androidx.compose.runtime.Composable
import com.ai.model.Flock
import com.ai.model.Settings
import com.ai.ui.settings.FlockEditScreen
import java.util.Locale

@Composable
internal fun FlockEdit(
    flock: Flock,
    aiSettings: Settings,
    onSaved: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = FlockEditForm(flock, aiSettings, onSaved, onBack, onNavigateHome)

@Composable
internal fun FlockEditForm(
    flock: Flock?,
    aiSettings: Settings,
    onSaved: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    FlockEditScreen(
        flock = flock,
        aiSettings = aiSettings,
        existingNames = aiSettings.flocks
            .filter { it.id != (flock?.id ?: "") }
            .map { it.name.lowercase(Locale.ROOT) }.toSet(),
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome,
        onOpenView = null
    )
}
