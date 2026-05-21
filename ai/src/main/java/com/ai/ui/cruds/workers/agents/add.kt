package com.ai.ui.cruds.workers.agents

import androidx.compose.runtime.Composable
import com.ai.model.Agent
import com.ai.model.Settings

@Composable
internal fun AgentAdd(
    aiSettings: Settings,
    deps: AgentEditDeps,
    onSaved: (Agent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = AgentEditForm(agent = null, aiSettings = aiSettings, deps = deps,
    onSaved = onSaved, onBack = onBack, onNavigateHome = onNavigateHome)
