package com.ai.ui.cruds.workers.swarms

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.Swarm

@Composable
internal fun SwarmAdd(
    aiSettings: Settings,
    onSaved: (Swarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = SwarmEditForm(null, aiSettings, onSaved, onBack, onNavigateHome)
