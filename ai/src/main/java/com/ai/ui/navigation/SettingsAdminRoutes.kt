package com.ai.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ai.data.*
import com.ai.model.*
import com.ai.viewmodel.*
import com.ai.ui.chat.*
import com.ai.ui.hub.*
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*
import com.ai.ui.history.*
import com.ai.ui.models.*
import com.ai.ui.search.*
import com.ai.ui.settings.*
import com.ai.ui.admin.*
import com.ai.ui.shared.*

/**
 * Main navigation host for the app.
 */
import androidx.navigation.NavGraphBuilder

internal fun NavGraphBuilder.settingsAdminRoutes(
    navController: NavHostController,
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    chatViewModel: ChatViewModel,
    safePopBack: () -> Unit,
    navigateHome: () -> Unit
) {
        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                onNavigateToAgentView = { id -> navController.navigate(NavRoutes.aiAgentView(id)) },
                onNavigateToFlockView = { id -> navController.navigate(NavRoutes.aiFlockView(id)) },
                onNavigateToSwarmView = { id -> navController.navigate(NavRoutes.aiSwarmView(id)) })
        }
        composable(NavRoutes.AI_SETUP) {
            SetupScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) })
        }

        // ===== Reports =====
        composable(NavRoutes.AI_COST_CONFIG) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.cruds.costsmanualoverride.CostManualOverridesCrud(
                aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome)
        }
        composable(NavRoutes.AI_COSTS_MAINTENANCE) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.admin.CostsMaintenanceScreen(
                aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome)
        }

        // ===== Models =====
        composable(NavRoutes.AI_MODEL_INFO) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            if (provider != null) {
                // OpenRouter key has two storage slots: External Services
                // (generalSettings.openRouterApiKey) and the OpenRouter
                // chat-provider entry (aiSettings.getApiKey(OPENROUTER)).
                // Prefer the External Services one — that's the canonical
                // catalog-lookup key — and fall back to the provider key
                // for users who only ever used OpenRouter as a chat host.
                val orKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                }
                ModelInfoScreen(provider = provider, modelName = model,
                    openRouterApiKey = orKey,
                    huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey, aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    onSaveSettings = { appViewModel.updateSettings(it) },
                    onTestAiModel = { s, k, m -> appViewModel.testAiModel(s, k, m) },
                    onFetchModels = appViewModel::fetchModels,
                    onStartChat = { p, m -> navController.navigate(NavRoutes.aiChatParams(p.id, m)) },
                    onNavigateToTracesForModel = { p, m -> navController.navigate(NavRoutes.traceListForModel(p.id, m)) },
                    onNavigateToAddManualOverride = { p, m -> navController.navigate(NavRoutes.aiManualOverrideAdd(p.id, m)) },
                    onNavigateToAddCostOverride = { p, m -> navController.navigate(NavRoutes.aiManualCostOverrideAdd(p.id, m)) },
                    onNavigateToProviderEdit = { p -> navController.navigate(NavRoutes.settingsProviderEdit(p.id)) },
                    onNavigateToBlockedModels = { navController.navigate(NavRoutes.SETTINGS_BLOCKED_MODELS) },
                    onNavigateToInaccessibleModels = { navController.navigate(NavRoutes.SETTINGS_INACCESSIBLE_MODELS) },
                    onNavigateToCooldowns = { navController.navigate(NavRoutes.SETTINGS_MODEL_COOLDOWNS) },
                    onNavigateToModelTypes = { navController.navigate(NavRoutes.SETTINGS_MANUAL_MODEL_TYPES) },
                    onNavigateToAgentEdit = { aid -> navController.navigate(NavRoutes.settingsAgentEdit(aid)) },
                    onNavigateToFlockEdit = { fid -> navController.navigate(NavRoutes.settingsFlockEdit(fid)) },
                    onNavigateToSwarmEdit = { sid -> navController.navigate(NavRoutes.settingsSwarmEdit(sid)) },
                    onOpenReport = { rid ->
                        scope.launch {
                            reportViewModel.restoreCompletedReport(context, rid)
                            navController.navigate(NavRoutes.AI_REPORTS)
                        }
                    },
                    onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                    onOpenView = { navController.navigate(NavRoutes.aiModelInfoView(provider.id, model)) },
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome)
            }
        }
        // View-flavoured Model Info — read-only sibling of AI_MODEL_INFO
        // reached from any model-name click on a View Report screen.
        composable(NavRoutes.AI_MODEL_INFO_VIEW) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            if (provider != null) {
                val orKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                }
                ViewSubScreenWithTitleNav(
                    navController = navController,
                    currentReportId = uiState.currentReportId
                ) {
                    com.ai.ui.models.ModelInfoViewScreen(
                        provider = provider,
                        modelName = model,
                        openRouterApiKey = orKey,
                        huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey,
                        aiSettings = uiState.aiSettings,
                        repository = appViewModel.repository,
                        onOpenReport = { rid ->
                            scope.launch {
                                reportViewModel.restoreCompletedReport(context, rid)
                                navController.navigate(NavRoutes.AI_REPORTS)
                            }
                        },
                        onOpenReportAtAgent = { rid, aid ->
                            scope.launch {
                                reportViewModel.restoreCompletedReport(context, rid)
                                navController.navigate(NavRoutes.aiReportViewAtAgent(aid))
                            }
                        },
                        onOpenProvider = { p ->
                            navController.navigate(NavRoutes.aiProviderView(p.id))
                        },
                        onOpenManage = { navController.navigate(NavRoutes.aiModelInfo(provider.id, model)) },
                        onBack = safePopBack
                    )
                }
            }
        }
        composable(NavRoutes.AI_AGENT_VIEW) { entry ->
            val agentId = entry.arguments?.getString("agentId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = uiState.currentReportId
            ) {
                com.ai.ui.settings.AgentViewScreen(
                    agentId = agentId,
                    aiSettings = uiState.aiSettings,
                    onOpenManage = { navController.navigate(NavRoutes.settingsAgentEdit(agentId)) },
                    onBack = safePopBack
                )
            }
        }
        composable(NavRoutes.AI_FLOCK_VIEW) { entry ->
            val flockId = entry.arguments?.getString("flockId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = uiState.currentReportId
            ) {
                com.ai.ui.settings.FlockViewScreen(
                    flockId = flockId,
                    aiSettings = uiState.aiSettings,
                    onOpenManage = { navController.navigate(NavRoutes.settingsFlockEdit(flockId)) },
                    onBack = safePopBack
                )
            }
        }
        composable(NavRoutes.AI_SWARM_VIEW) { entry ->
            val swarmId = entry.arguments?.getString("swarmId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = uiState.currentReportId
            ) {
                com.ai.ui.settings.SwarmViewScreen(
                    swarmId = swarmId,
                    aiSettings = uiState.aiSettings,
                    onOpenManage = { navController.navigate(NavRoutes.settingsSwarmEdit(swarmId)) },
                    onBack = safePopBack
                )
            }
        }
        composable(NavRoutes.AI_PROVIDER_VIEW) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                ViewSubScreenWithTitleNav(
                    navController = navController,
                    currentReportId = uiState.currentReportId
                ) {
                    com.ai.ui.settings.ProviderViewScreen(
                        provider = provider,
                        onOpenManage = { navController.navigate(NavRoutes.settingsProviderEdit(provider.id)) },
                        onBack = safePopBack
                    )
                }
            }
        }
        composable(NavRoutes.AI_MANUAL_OVERRIDE_ADD) { entry ->
            val providerId = entry.arguments?.getString("provider") ?: ""
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            ManualModelOverrideEntryScreen(
                aiSettings = uiState.aiSettings,
                providerId = providerId,
                modelId = model,
                onSave = { appViewModel.updateSettings(it) },
                onBack = safePopBack
            )
        }
        composable(NavRoutes.AI_MANUAL_COST_OVERRIDE_ADD) { entry ->
            val providerId = entry.arguments?.getString("provider") ?: ""
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            ManualCostOverrideEntryScreen(
                aiSettings = uiState.aiSettings,
                providerId = providerId,
                modelId = model,
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }

        // ===== Chat =====
        composable(NavRoutes.SETTINGS_PROVIDER_EDIT) { entry ->
            val pid = entry.arguments?.getString("providerId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT,
                initialProviderId = pid)
        }
        composable(NavRoutes.SETTINGS_AGENT_EDIT) { entry ->
            val aid = entry.arguments?.getString("agentId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_AGENT_EDIT,
                initialEditingAgentId = aid)
        }
        composable(NavRoutes.SETTINGS_INTERNAL_PROMPT_EDIT) { entry ->
            val pid = entry.arguments?.getString("promptId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT,
                initialEditingInternalPromptId = pid)
        }
        composable(NavRoutes.SETTINGS_AGENTS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_AGENTS)
        }
        composable(NavRoutes.SETTINGS_FLOCK_EDIT) { entry ->
            val fid = entry.arguments?.getString("flockId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_FLOCK_EDIT,
                initialEditingFlockId = fid)
        }
        composable(NavRoutes.SETTINGS_SWARM_EDIT) { entry ->
            val sid = entry.arguments?.getString("swarmId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_SWARM_EDIT,
                initialEditingSwarmId = sid)
        }
        composable(NavRoutes.SETTINGS_BLOCKED_MODELS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_BLOCKED_MODELS)
        }
        composable(NavRoutes.SETTINGS_INACCESSIBLE_MODELS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_INACCESSIBLE_MODELS)
        }
        composable(NavRoutes.SETTINGS_MODEL_COOLDOWNS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_MODEL_COOLDOWNS)
        }
        composable(NavRoutes.SETTINGS_MANUAL_MODEL_TYPES) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_MANUAL_MODEL_TYPES)
        }
        composable(NavRoutes.SETTINGS_FLOCKS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_FLOCKS)
        }
        composable(NavRoutes.SETTINGS_SWARMS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_SWARMS)
        }
        composable(NavRoutes.SETTINGS_INTERNAL_PROMPTS_BY_CATEGORY) { entry ->
            val cat = try {
                java.net.URLDecoder.decode(entry.arguments?.getString("category") ?: "meta", "UTF-8")
            } catch (_: Exception) { "meta" }
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS,
                initialInternalPromptCategory = cat)
        }
}
