package com.ai.ui.cruds.workers.agents

import androidx.compose.runtime.Composable
import com.ai.data.AppService
import com.ai.model.Agent
import com.ai.model.Endpoint
import com.ai.model.Settings
import com.ai.ui.settings.AgentEditScreen
import java.util.Locale

/** Dependencies the rich agent form needs (provider/model fetch, test,
 *  endpoint persistence, trace links). Threaded from the host. */
class AgentEditDeps(
    val onTestAiModel: suspend (AppService, String, String) -> String?,
    val onFetchModels: (AppService, String) -> Unit,
    val loadingModelsFor: Set<AppService>,
    val fetchModelsErrors: Map<String, com.ai.viewmodel.FetchModelsError>,
    val onNavigateToTrace: ((String) -> Unit)?,
    val onAddEndpoint: (AppService, Endpoint) -> Unit
)

@Composable
internal fun AgentEdit(
    agent: Agent,
    aiSettings: Settings,
    deps: AgentEditDeps,
    onSaved: (Agent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = AgentEditForm(agent, aiSettings, deps, onSaved, onBack, onNavigateHome)

/** Reuses the existing rich [AgentEditScreen] for both add and edit;
 *  [agent] null = add. */
@Composable
internal fun AgentEditForm(
    agent: Agent?,
    aiSettings: Settings,
    deps: AgentEditDeps,
    onSaved: (Agent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    AgentEditScreen(
        agent = agent,
        aiSettings = aiSettings,
        existingNames = aiSettings.agents
            .filter { it.id != (agent?.id ?: "") }
            .map { it.name.lowercase(Locale.ROOT) }.toSet(),
        onTestAiModel = deps.onTestAiModel,
        onFetchModels = deps.onFetchModels,
        onSave = onSaved,
        onBack = onBack,
        onNavigateHome = onNavigateHome,
        loadingModelsFor = deps.loadingModelsFor,
        fetchModelsErrors = deps.fetchModelsErrors,
        onNavigateToTrace = deps.onNavigateToTrace,
        onAddEndpoint = deps.onAddEndpoint,
        onOpenView = null
    )
}
