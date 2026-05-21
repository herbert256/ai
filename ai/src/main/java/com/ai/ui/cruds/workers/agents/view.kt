package com.ai.ui.cruds.workers.agents

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.model.Agent
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.settings.WorkerSharedCards
import com.ai.ui.shared.shortModelName

@Composable
internal fun AgentView(
    agent: Agent,
    aiSettings: Settings,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Agent",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = agent.name,
        helpTopic = "agent_view"
    ) {
        CrudField("Name", agent.name)
        CrudField("Provider", agent.provider.id)
        CrudField("Model", shortModelName(aiSettings.getEffectiveModelForAgent(agent)))
        if (!agent.endpointId.isNullOrBlank()) CrudField("Endpoint", agent.endpointId!!)
        if (agent.apiKey.isNotBlank()) CrudField("API key", "•••• (set)")
        WorkerSharedCards(
            aiSettings = aiSettings,
            paramsIds = agent.paramsIds,
            systemPromptId = agent.systemPromptId
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}
