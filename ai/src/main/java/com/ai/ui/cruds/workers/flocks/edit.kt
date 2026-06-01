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
    onNavigateHome: () -> Unit,
    onDelete: (() -> Unit)? = null
) = FlockEditForm(flock, aiSettings, onSaved, onBack, onNavigateHome, onDelete)

@Composable
internal fun FlockEditForm(
    flock: Flock?,
    aiSettings: Settings,
    onSaved: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onDelete: (() -> Unit)? = null
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
        onOpenView = null,
        onDelete = onDelete
    )
}
