package com.ai.ui.cruds.workers.swarms

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.Swarm
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.settings.WorkerSharedCards

@Composable
internal fun SwarmView(
    swarm: Swarm,
    aiSettings: Settings,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Swarm",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = swarm.name,
        helpTopic = "swarm_view"
    ) {
        CrudField("Name", swarm.name)
        CrudField(
            "Members (${swarm.members.size})",
            swarm.members.joinToString(", ") { "${it.provider.id}/${it.model}" }.ifBlank { "(none)" }
        )
        WorkerSharedCards(
            aiSettings = aiSettings,
            paramsIds = swarm.paramsIds,
            systemPromptId = swarm.systemPromptId
        )
    }
}
