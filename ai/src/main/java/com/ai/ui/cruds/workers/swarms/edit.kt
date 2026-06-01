package com.ai.ui.cruds.workers.swarms

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.Swarm
import com.ai.ui.settings.SwarmEditScreen
import java.util.Locale

@Composable
internal fun SwarmEdit(
    swarm: Swarm,
    aiSettings: Settings,
    onSaved: (Swarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onDelete: (() -> Unit)? = null
) = SwarmEditForm(swarm, aiSettings, onSaved, onBack, onNavigateHome, onDelete)

@Composable
internal fun SwarmEditForm(
    swarm: Swarm?,
    aiSettings: Settings,
    onSaved: (Swarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    SwarmEditScreen(
        swarm = swarm,
        aiSettings = aiSettings,
        existingNames = aiSettings.swarms
            .filter { it.id != (swarm?.id ?: "") }
            .map { it.name.lowercase(Locale.ROOT) }.toSet(),
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome,
        onOpenView = null,
        onDelete = onDelete
    )
}
