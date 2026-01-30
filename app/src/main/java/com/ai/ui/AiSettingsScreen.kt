package com.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.data.AiService

/**
 * Main AI settings screen with navigation cards for each AI service.
 */
@Composable
fun AiSettingsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    huggingFaceApiKey: String = "",
    openRouterApiKey: String = "",
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit,
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                onSave(result.aiSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }
                result.openRouterApiKey?.let { key -> onSaveOpenRouterApiKey(key) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Analysis",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "AI Services",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )

        // Provider cards sorted alphabetically
        AiServiceNavigationCard(
            title = "Anthropic",
            accentColor = Color(0xFFD97706),
            adminUrl = AiService.ANTHROPIC.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_ANTHROPIC) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            accentColor = Color(0xFF4D6BFE),
            adminUrl = AiService.DEEPSEEK.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        // Dummy provider only visible in developer mode
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                accentColor = Color(0xFF888888),
                adminUrl = AiService.DUMMY.adminUrl,
                onEdit = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }
        AiServiceNavigationCard(
            title = "Google",
            accentColor = Color(0xFF4285F4),
            adminUrl = AiService.GOOGLE.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_GOOGLE) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            accentColor = Color(0xFFF55036),
            adminUrl = AiService.GROQ.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            accentColor = Color(0xFFFF7000),
            adminUrl = AiService.MISTRAL.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "OpenAI",
            accentColor = Color(0xFF10A37F),
            adminUrl = AiService.OPENAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_OPENAI) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            accentColor = Color(0xFF6B5AED),
            adminUrl = AiService.OPENROUTER.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            accentColor = Color(0xFF20B2AA),
            adminUrl = AiService.PERPLEXITY.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "SiliconFlow",
            accentColor = Color(0xFF00B4D8),
            adminUrl = AiService.SILICONFLOW.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_SILICONFLOW) }
        )
        AiServiceNavigationCard(
            title = "Together",
            accentColor = Color(0xFF6366F1),
            adminUrl = AiService.TOGETHER.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "xAI",
            accentColor = Color(0xFFFFFFFF),
            adminUrl = AiService.XAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_XAI) }
        )
        AiServiceNavigationCard(
            title = "Z.AI",
            accentColor = Color(0xFF6366F1),
            adminUrl = AiService.ZAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_ZAI) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Export AI configuration button
        if (aiSettings.hasAnyApiKey()) {
            Button(
                onClick = {
                    exportAiConfigToFile(context, aiSettings, huggingFaceApiKey, openRouterApiKey)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("Export AI configuration")
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Export API keys only button
            Button(
                onClick = {
                    exportApiKeysToClipboard(context, aiSettings)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                )
            ) {
                Text("Export API keys")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Import AI configuration button (opens file picker)
        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            )
        ) {
            Text("Import AI configuration")
        }

    }
}

/**
 * AI Setup hub screen with navigation cards for Providers, Prompts, and Agents.
 */
@Composable
fun AiSetupScreen(
    aiSettings: AiSettings,
    developerMode: Boolean = false,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit = {},
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                onSave(result.aiSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }
                result.openRouterApiKey?.let { key -> onSaveOpenRouterApiKey(key) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Setup",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        // Import AI configuration button - only show if no flock named "default agents"
        val hasDefaultAgentsFlock = aiSettings.flocks.any { it.name.equals("default agents", ignoreCase = true) }
        if (!hasDefaultAgentsFlock) {
            Button(
                onClick = {
                    filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Import AI configuration")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Summary info (exclude DUMMY agents when not in developer mode)
        val configuredAgents = aiSettings.agents.count { agent ->
            aiSettings.getEffectiveApiKeyForAgent(agent).isNotBlank() && (developerMode || agent.provider != AiService.DUMMY)
        }

        // Providers card (exclude DUMMY when not in developer mode)
        val providerCount = if (developerMode) AiService.entries.size else AiService.entries.size - 1
        AiSetupNavigationCard(
            title = "Providers",
            description = "Configure model sources for each AI service",
            icon = "⚙",
            count = "$providerCount providers",
            onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) }
        )

        // Agents card
        val hasApiKey = aiSettings.hasAnyApiKey()
        AiSetupNavigationCard(
            title = "Agents",
            description = "Configure agents with provider, model, and API key",
            icon = "🤖",
            count = "$configuredAgents configured",
            onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) },
            enabled = hasApiKey
        )

        // Flocks card
        val configuredFlocks = aiSettings.flocks.size
        AiSetupNavigationCard(
            title = "Flocks",
            description = "Group agents into flocks for report generation",
            icon = "🦆",
            count = "$configuredFlocks configured",
            onClick = { onNavigate(SettingsSubScreen.AI_SWARMS) },
            enabled = hasApiKey
        )

        // Swarms card
        val configuredSwarms = aiSettings.swarms.size
        AiSetupNavigationCard(
            title = "Swarms",
            description = "Group provider/model combinations for report generation",
            icon = "🐝",
            count = "$configuredSwarms configured",
            onClick = { onNavigate(SettingsSubScreen.AI_FLOCKS) },
            enabled = hasApiKey
        )

    }
}

/**
 * AI Settings hub screen - contains Prompts and Costs navigation.
 */
@Composable
fun AiAiSettingsContentScreen(
    aiSettings: AiSettings,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onNavigateToCostConfig: () -> Unit = {}
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Settings",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        // Parameters card
        val configuredParams = aiSettings.params.size
        AiSetupNavigationCard(
            title = "Parameters",
            description = "Reusable parameter presets for agents",
            icon = "🎛",
            count = "$configuredParams configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PARAMS) }
        )

        // Prompts card
        val configuredPrompts = aiSettings.prompts.size
        AiSetupNavigationCard(
            title = "Prompts",
            description = "Internal prompts for AI-powered features",
            icon = "📝",
            count = "$configuredPrompts configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS) }
        )

        // Costs card - use aiSettings as key to refresh after import
        val manualPricingCount = remember(aiSettings) {
            com.ai.data.PricingCache.getAllManualPricing(context).size
        }
        AiSetupNavigationCard(
            title = "Costs",
            description = "Configure manual price overrides per model",
            icon = "💰",
            count = "$manualPricingCount configured",
            onClick = onNavigateToCostConfig
        )
    }
}

/**
 * Navigation card for AI Setup screen.
 */
@Composable
internal fun AiSetupNavigationCard(
    title: String,
    description: String,
    icon: String,
    count: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1A1A1A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium,
                color = if (enabled) Color.Unspecified else Color(0xFF555555)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else Color(0xFF555555)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFFAAAAAA) else Color(0xFF444444)
                )
            }
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFF00E676) else Color(0xFF444444)
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Color(0xFF888888) else Color(0xFF333333)
                )
            }
        }
    }
}

/**
 * AI Providers screen - configure model source for each provider.
 */
@Composable
fun AiProvidersScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    // Toggle between showing only active ("ok") providers and all providers
    var showAll by remember { mutableStateOf(false) }

    // Provider definitions: service, title, accent color, settings screen
    data class ProviderEntry(
        val service: AiService,
        val title: String,
        val accentColor: Color,
        val screen: SettingsSubScreen
    )

    val allProviders = listOf(
        ProviderEntry(AiService.AI21, "AI21", Color(0xFFFF6F00), SettingsSubScreen.AI_AI21),
        ProviderEntry(AiService.ANTHROPIC, "Anthropic", Color(0xFFD97706), SettingsSubScreen.AI_ANTHROPIC),
        ProviderEntry(AiService.BAICHUAN, "Baichuan", Color(0xFF1E88E5), SettingsSubScreen.AI_BAICHUAN),
        ProviderEntry(AiService.CEREBRAS, "Cerebras", Color(0xFF00A3E0), SettingsSubScreen.AI_CEREBRAS),
        ProviderEntry(AiService.COHERE, "Cohere", Color(0xFF39594D), SettingsSubScreen.AI_COHERE),
        ProviderEntry(AiService.DASHSCOPE, "DashScope", Color(0xFFFF6A00), SettingsSubScreen.AI_DASHSCOPE),
        ProviderEntry(AiService.DEEPSEEK, "DeepSeek", Color(0xFF4D6BFE), SettingsSubScreen.AI_DEEPSEEK),
        ProviderEntry(AiService.FIREWORKS, "Fireworks", Color(0xFFE34234), SettingsSubScreen.AI_FIREWORKS),
        ProviderEntry(AiService.GOOGLE, "Google", Color(0xFF4285F4), SettingsSubScreen.AI_GOOGLE),
        ProviderEntry(AiService.GROQ, "Groq", Color(0xFFF55036), SettingsSubScreen.AI_GROQ),
        ProviderEntry(AiService.MINIMAX, "MiniMax", Color(0xFFEC407A), SettingsSubScreen.AI_MINIMAX),
        ProviderEntry(AiService.MISTRAL, "Mistral", Color(0xFFFF7000), SettingsSubScreen.AI_MISTRAL),
        ProviderEntry(AiService.MOONSHOT, "Moonshot", Color(0xFF7C3AED), SettingsSubScreen.AI_MOONSHOT),
        ProviderEntry(AiService.OPENAI, "OpenAI", Color(0xFF10A37F), SettingsSubScreen.AI_OPENAI),
        ProviderEntry(AiService.OPENROUTER, "OpenRouter", Color(0xFF6B5AED), SettingsSubScreen.AI_OPENROUTER),
        ProviderEntry(AiService.PERPLEXITY, "Perplexity", Color(0xFF20B2AA), SettingsSubScreen.AI_PERPLEXITY),
        ProviderEntry(AiService.SAMBANOVA, "SambaNova", Color(0xFF6B21A8), SettingsSubScreen.AI_SAMBANOVA),
        ProviderEntry(AiService.SILICONFLOW, "SiliconFlow", Color(0xFF00B4D8), SettingsSubScreen.AI_SILICONFLOW),
        ProviderEntry(AiService.STEPFUN, "StepFun", Color(0xFF00BFA5), SettingsSubScreen.AI_STEPFUN),
        ProviderEntry(AiService.TOGETHER, "Together", Color(0xFF6366F1), SettingsSubScreen.AI_TOGETHER),
        ProviderEntry(AiService.XAI, "xAI", Color(0xFFFFFFFF), SettingsSubScreen.AI_XAI),
        ProviderEntry(AiService.ZAI, "Z.AI", Color(0xFF6366F1), SettingsSubScreen.AI_ZAI)
    )

    val visibleProviders = if (showAll) {
        allProviders
    } else {
        allProviders.filter { aiSettings.getProviderState(it.service) == "ok" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Providers",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        // Provider cards
        visibleProviders.forEach { entry ->
            AiServiceNavigationCard(
                title = entry.title,
                accentColor = entry.accentColor,
                providerState = aiSettings.getProviderState(entry.service),
                adminUrl = entry.service.adminUrl,
                onEdit = { onNavigate(entry.screen) }
            )
        }

        // Dummy provider only visible in developer mode (always last)
        if (developerMode && (showAll || aiSettings.getProviderState(AiService.DUMMY) == "ok")) {
            AiServiceNavigationCard(
                title = "Dummy",
                accentColor = Color(0xFF888888),
                providerState = "ok",
                adminUrl = AiService.DUMMY.adminUrl,
                onEdit = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }

        // Toggle button: switch between active-only and all providers
        val activeCount = allProviders.count { aiSettings.getProviderState(it.service) == "ok" }
        OutlinedButton(
            onClick = { showAll = !showAll },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (showAll) "Show active providers ($activeCount)"
                else "Show all providers (${allProviders.size})"
            )
        }
    }
}
