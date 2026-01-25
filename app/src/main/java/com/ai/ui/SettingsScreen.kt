package com.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.data.AiService

/**
 * Settings sub-screen navigation enum.
 */
enum class SettingsSubScreen {
    MAIN,
    GENERAL_SETTINGS,
    DEVELOPER_SETTINGS,
    // AI settings structure
    AI_SETTINGS,
    AI_CHATGPT,
    AI_CLAUDE,
    AI_GEMINI,
    AI_GROK,
    AI_GROQ,
    AI_DEEPSEEK,
    AI_MISTRAL,
    AI_PERPLEXITY,
    AI_TOGETHER,
    AI_OPENROUTER,
    AI_DUMMY,
    // AI architecture
    AI_SETUP,       // Hub with navigation cards
    AI_PROVIDERS,   // Provider model configuration
    AI_AGENTS       // Agents CRUD
}

/**
 * Root settings screen that manages navigation between settings sub-screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: AiSettings,
    availableChatGptModels: List<String>,
    isLoadingChatGptModels: Boolean,
    availableGeminiModels: List<String>,
    isLoadingGeminiModels: Boolean,
    availableGrokModels: List<String>,
    isLoadingGrokModels: Boolean,
    availableGroqModels: List<String>,
    isLoadingGroqModels: Boolean,
    availableDeepSeekModels: List<String>,
    isLoadingDeepSeekModels: Boolean,
    availableMistralModels: List<String>,
    isLoadingMistralModels: Boolean,
    availablePerplexityModels: List<String>,
    isLoadingPerplexityModels: Boolean,
    availableTogetherModels: List<String>,
    isLoadingTogetherModels: Boolean,
    availableOpenRouterModels: List<String>,
    isLoadingOpenRouterModels: Boolean,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onTrackApiCallsChanged: (Boolean) -> Unit = {},
    onSaveAi: (AiSettings) -> Unit,
    onFetchChatGptModels: (String) -> Unit,
    onFetchGeminiModels: (String) -> Unit,
    onFetchGrokModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onFetchDeepSeekModels: (String) -> Unit,
    onFetchMistralModels: (String) -> Unit,
    onFetchPerplexityModels: (String) -> Unit,
    onFetchTogetherModels: (String) -> Unit,
    onFetchOpenRouterModels: (String) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null }
) {
    var currentSubScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }

    // Handle Android back button
    BackHandler {
        when (currentSubScreen) {
            SettingsSubScreen.MAIN -> onBack()
            SettingsSubScreen.AI_CHATGPT,
            SettingsSubScreen.AI_CLAUDE,
            SettingsSubScreen.AI_GEMINI,
            SettingsSubScreen.AI_GROK,
            SettingsSubScreen.AI_GROQ,
            SettingsSubScreen.AI_DEEPSEEK,
            SettingsSubScreen.AI_MISTRAL,
            SettingsSubScreen.AI_PERPLEXITY,
            SettingsSubScreen.AI_TOGETHER,
            SettingsSubScreen.AI_OPENROUTER,
            SettingsSubScreen.AI_DUMMY -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            // AI screens navigate back to AI_SETUP
            SettingsSubScreen.AI_PROVIDERS,
            SettingsSubScreen.AI_AGENTS -> currentSubScreen = SettingsSubScreen.AI_SETUP
            else -> currentSubScreen = SettingsSubScreen.MAIN
        }
    }

    when (currentSubScreen) {
        SettingsSubScreen.MAIN -> SettingsMainScreen(
            onBack = onBack,
            onNavigateHome = onNavigateHome,
            onNavigate = { currentSubScreen = it }
        )
        SettingsSubScreen.GENERAL_SETTINGS -> GeneralSettingsScreen(
            generalSettings = generalSettings,
            onBackToSettings = { currentSubScreen = SettingsSubScreen.MAIN },
            onBackToHome = onNavigateHome,
            onSave = onSaveGeneral
        )
        SettingsSubScreen.DEVELOPER_SETTINGS -> DeveloperSettingsScreen(
            generalSettings = generalSettings,
            onBackToSettings = { currentSubScreen = SettingsSubScreen.MAIN },
            onBackToHome = onNavigateHome,
            onSave = onSaveGeneral,
            onTrackApiCallsChanged = onTrackApiCallsChanged
        )
        SettingsSubScreen.AI_SETTINGS -> AiSettingsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToSettings = { currentSubScreen = SettingsSubScreen.MAIN },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it },
            onSave = onSaveAi
        )
        SettingsSubScreen.AI_CHATGPT -> ChatGptSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableChatGptModels,
            isLoadingModels = isLoadingChatGptModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchChatGptModels
        )
        SettingsSubScreen.AI_CLAUDE -> ClaudeSettingsScreen(
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi
        )
        SettingsSubScreen.AI_GEMINI -> GeminiSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableGeminiModels,
            isLoadingModels = isLoadingGeminiModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGeminiModels
        )
        SettingsSubScreen.AI_GROK -> GrokSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableGrokModels,
            isLoadingModels = isLoadingGrokModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGrokModels
        )
        SettingsSubScreen.AI_GROQ -> GroqSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableGroqModels,
            isLoadingModels = isLoadingGroqModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGroqModels
        )
        SettingsSubScreen.AI_DEEPSEEK -> DeepSeekSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableDeepSeekModels,
            isLoadingModels = isLoadingDeepSeekModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchDeepSeekModels
        )
        SettingsSubScreen.AI_MISTRAL -> MistralSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableMistralModels,
            isLoadingModels = isLoadingMistralModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchMistralModels
        )
        SettingsSubScreen.AI_PERPLEXITY -> PerplexitySettingsScreen(
            aiSettings = aiSettings,
            availableModels = availablePerplexityModels,
            isLoadingModels = isLoadingPerplexityModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchPerplexityModels
        )
        SettingsSubScreen.AI_TOGETHER -> TogetherSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableTogetherModels,
            isLoadingModels = isLoadingTogetherModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchTogetherModels
        )
        SettingsSubScreen.AI_OPENROUTER -> OpenRouterSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableOpenRouterModels,
            isLoadingModels = isLoadingOpenRouterModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchOpenRouterModels
        )
        SettingsSubScreen.AI_DUMMY -> DummySettingsScreen(
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi
        )
        // Three-tier AI architecture screens
        SettingsSubScreen.AI_SETUP -> AiSetupScreen(
            aiSettings = aiSettings,
            onBackToSettings = { currentSubScreen = SettingsSubScreen.MAIN },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it },
            onSave = onSaveAi
        )
        SettingsSubScreen.AI_PROVIDERS -> AiProvidersScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it }
        )
        SettingsSubScreen.AI_AGENTS -> AiAgentsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            availableChatGptModels = availableChatGptModels,
            availableGeminiModels = availableGeminiModels,
            availableGrokModels = availableGrokModels,
            availableGroqModels = availableGroqModels,
            availableDeepSeekModels = availableDeepSeekModels,
            availableMistralModels = availableMistralModels,
            availablePerplexityModels = availablePerplexityModels,
            availableTogetherModels = availableTogetherModels,
            availableOpenRouterModels = availableOpenRouterModels,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestAiModel = onTestAiModel
        )
    }
}

/**
 * Main settings menu screen with navigation cards.
 */
@Composable
private fun SettingsMainScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title bar
        AiTitleBar(
            title = "Settings",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // General settings card
        SettingsNavigationCard(
            title = "General settings",
            description = "App-wide settings",
            onClick = { onNavigate(SettingsSubScreen.GENERAL_SETTINGS) }
        )

        // AI Setup settings card
        SettingsNavigationCard(
            title = "AI Setup",
            description = "Providers and agents",
            onClick = { onNavigate(SettingsSubScreen.AI_SETUP) }
        )

        // Developer settings card
        SettingsNavigationCard(
            title = "Developer settings",
            description = "Developer mode, API tracking",
            onClick = { onNavigate(SettingsSubScreen.DEVELOPER_SETTINGS) }
        )
    }
}

/**
 * Reusable navigation card for settings menu.
 */
@Composable
private fun SettingsNavigationCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAAAAAA)
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF888888)
            )
        }
    }
}
