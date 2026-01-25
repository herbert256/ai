package com.ai.ui

import androidx.compose.foundation.background
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
 * ChatGPT settings screen.
 */
@Composable
fun ChatGptSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.chatGptApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.chatGptModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.chatGptManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "ChatGPT",
        subtitle = "OpenAI",
        accentColor = Color(0xFF10A37F),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(chatGptApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.CHATGPT, apiKey, "gpt-4o-mini") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(chatGptModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(chatGptManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Claude settings screen.
 */
@Composable
fun ClaudeSettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.claudeApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.claudeModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.claudeManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Claude",
        subtitle = "Anthropic",
        accentColor = Color(0xFFD97706),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(claudeApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.CLAUDE, apiKey, "claude-sonnet-4-20250514") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(), // Claude doesn't have API for listing models
            isLoadingModels = false,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(claudeModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(claudeManualModels = it))
            },
            onFetchModels = { } // No-op for Claude
        )
    }
}

/**
 * Gemini settings screen.
 */
@Composable
fun GeminiSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.geminiApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.geminiModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.geminiManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Gemini",
        subtitle = "Google",
        accentColor = Color(0xFF4285F4),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(geminiApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.GEMINI, apiKey, "gemini-2.0-flash") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(geminiModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(geminiManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Grok settings screen.
 */
@Composable
fun GrokSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.grokApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.grokModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.grokManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Grok",
        subtitle = "xAI",
        accentColor = Color(0xFFFFFFFF),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(grokApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.GROK, apiKey, "grok-3-mini") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(grokModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(grokManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Groq settings screen.
 */
@Composable
fun GroqSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.groqApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.groqModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.groqManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Groq",
        subtitle = "Groq",
        accentColor = Color(0xFFF55036),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(groqApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.GROQ, apiKey, "llama-3.3-70b-versatile") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(groqModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(groqManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * DeepSeek settings screen.
 */
@Composable
fun DeepSeekSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.deepSeekApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.deepSeekModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.deepSeekManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "DeepSeek",
        subtitle = "DeepSeek AI",
        accentColor = Color(0xFF4D6BFE),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(deepSeekApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.DEEPSEEK, apiKey, "deepseek-chat") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(deepSeekModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(deepSeekManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Mistral settings screen.
 */
@Composable
fun MistralSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.mistralApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.mistralModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.mistralManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Mistral",
        subtitle = "Mistral AI",
        accentColor = Color(0xFFFF7000),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(mistralApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.MISTRAL, apiKey, "mistral-small-latest") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(mistralModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(mistralManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Perplexity settings screen.
 */
@Composable
fun PerplexitySettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.perplexityApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.perplexityModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.perplexityManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Perplexity",
        subtitle = "Perplexity AI",
        accentColor = Color(0xFF20B2AA),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(perplexityApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.PERPLEXITY, apiKey, "sonar") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(),  // Perplexity has no model list API
            isLoadingModels = false,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(perplexityModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(perplexityManualModels = it))
            },
            onFetchModels = { }  // No-op for Perplexity
        )
    }
}

/**
 * Together AI settings screen.
 */
@Composable
fun TogetherSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.togetherApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.togetherModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.togetherManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Together",
        subtitle = "Together AI",
        accentColor = Color(0xFF6366F1),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(togetherApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.TOGETHER, apiKey, "meta-llama/Llama-3.3-70B-Instruct-Turbo") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(togetherModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(togetherManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * OpenRouter AI settings screen.
 */
@Composable
fun OpenRouterSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.openRouterApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.openRouterModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.openRouterManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "OpenRouter",
        subtitle = "OpenRouter AI",
        accentColor = Color(0xFF6B5AED),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(openRouterApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.OPENROUTER, apiKey, "anthropic/claude-3.5-sonnet") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(openRouterModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(openRouterManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * SiliconFlow settings screen.
 */
@Composable
fun SiliconFlowSettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.siliconFlowApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.siliconFlowModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.siliconFlowManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "SiliconFlow",
        subtitle = "SiliconFlow AI",
        accentColor = Color(0xFF00B4D8),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(siliconFlowApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.SILICONFLOW, apiKey, "Qwen/Qwen2.5-7B-Instruct") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(),  // SiliconFlow doesn't have a list API
            isLoadingModels = false,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(siliconFlowModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(siliconFlowManualModels = it))
            },
            onFetchModels = { }  // No API for fetching models
        )
    }
}

/**
 * Dummy settings screen (for testing with local HTTP server).
 */
@Composable
fun DummySettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?
) {
    var apiKey by remember { mutableStateOf(aiSettings.dummyApiKey) }
    var modelSource by remember { mutableStateOf(aiSettings.dummyModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.dummyManualModels) }

    AiServiceSettingsScreenTemplate(
        title = "Dummy",
        subtitle = "Local test server (port 54321)",
        accentColor = Color(0xFF888888),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome
    ) {
        // Info card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A3A2A)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Test Provider",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = "Uses local HTTP server on port 54321. API key must be \"dummy\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
                Text(
                    text = "Response: \"Hi, Greetings from AI\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E676)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = {
                apiKey = it
                onSave(aiSettings.copy(dummyApiKey = it))
            },
            onTestApiKey = { onTestApiKey(AiService.DUMMY, apiKey, "abc") }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = {
                modelSource = it
                onSave(aiSettings.copy(dummyModelSource = it))
            },
            onManualModelsChange = {
                manualModels = it
                onSave(aiSettings.copy(dummyManualModels = it))
            },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}
