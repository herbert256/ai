package com.ai.ui.cruds.workers.flocks

import androidx.compose.runtime.Composable
import com.ai.model.Flock
import com.ai.model.Settings

@Composable
internal fun FlockAdd(
    aiSettings: Settings,
    onSaved: (Flock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = FlockEditForm(null, aiSettings, onSaved, onBack, onNavigateHome)
