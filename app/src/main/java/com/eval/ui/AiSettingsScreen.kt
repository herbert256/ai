package com.eval.ui

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
import com.eval.data.AiService

/**
 * Main AI settings screen with navigation cards for each AI service.
 */
@Composable
fun AiSettingsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val imported = importAiConfigFromFile(context, it, aiSettings)
            if (imported != null) {
                onSave(imported)
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

        AiServiceNavigationCard(
            title = "ChatGPT",
            subtitle = "OpenAI",
            accentColor = Color(0xFF10A37F),
            onClick = { onNavigate(SettingsSubScreen.AI_CHATGPT) }
        )
        AiServiceNavigationCard(
            title = "Claude",
            subtitle = "Anthropic",
            accentColor = Color(0xFFD97706),
            onClick = { onNavigate(SettingsSubScreen.AI_CLAUDE) }
        )
        AiServiceNavigationCard(
            title = "Gemini",
            subtitle = "Google",
            accentColor = Color(0xFF4285F4),
            onClick = { onNavigate(SettingsSubScreen.AI_GEMINI) }
        )
        AiServiceNavigationCard(
            title = "Grok",
            subtitle = "xAI",
            accentColor = Color(0xFFFFFFFF),
            onClick = { onNavigate(SettingsSubScreen.AI_GROK) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            subtitle = "Groq",
            accentColor = Color(0xFFF55036),
            onClick = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            subtitle = "DeepSeek AI",
            accentColor = Color(0xFF4D6BFE),
            onClick = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            subtitle = "Mistral AI",
            accentColor = Color(0xFFFF7000),
            onClick = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            subtitle = "Perplexity AI",
            accentColor = Color(0xFF20B2AA),
            onClick = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "Together",
            subtitle = "Together AI",
            accentColor = Color(0xFF6366F1),
            onClick = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            subtitle = "OpenRouter AI",
            accentColor = Color(0xFF6B5AED),
            onClick = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        // Dummy provider only visible in developer mode
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                subtitle = "For testing",
                accentColor = Color(0xFF888888),
                onClick = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Export AI configuration button
        if (aiSettings.hasAnyApiKey()) {
            Button(
                onClick = {
                    exportAiConfigToFile(context, aiSettings)
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
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val imported = importAiConfigFromFile(context, it, aiSettings)
            if (imported != null) {
                onSave(imported)
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

        Spacer(modifier = Modifier.height(8.dp))

        // Summary info
        val configuredAgents = aiSettings.agents.count { it.apiKey.isNotBlank() }
        val totalPrompts = aiSettings.prompts.size

        Text(
            text = "Configure AI services in three steps:",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // AI Providers card
        AiSetupNavigationCard(
            title = "AI Providers",
            description = "Configure model sources for each AI service",
            icon = "⚙",
            count = "${AiService.entries.size} providers",
            onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) }
        )

        // AI Prompts card
        AiSetupNavigationCard(
            title = "AI Prompts",
            description = "Create and manage prompt templates",
            icon = "📝",
            count = "$totalPrompts prompts",
            onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS) }
        )

        // AI Agents card
        AiSetupNavigationCard(
            title = "AI Agents",
            description = "Configure agents with provider, model, key, and prompts",
            icon = "🤖",
            count = "$configuredAgents configured",
            onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Export AI configuration button
        Button(
            onClick = {
                exportAiConfigToFile(context, aiSettings)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Export AI configuration")
        }

        // Import AI configuration button (opens file picker)
        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ) {
            Text("Import AI configuration")
        }
    }
}

/**
 * Navigation card for AI Setup screen.
 */
@Composable
private fun AiSetupNavigationCard(
    title: String,
    description: String,
    icon: String,
    count: String,
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
            }
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF888888)
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

        // Provider cards - navigate to individual provider screens for model config
        AiServiceNavigationCard(
            title = "ChatGPT",
            subtitle = "OpenAI",
            accentColor = Color(0xFF10A37F),
            onClick = { onNavigate(SettingsSubScreen.AI_CHATGPT) }
        )
        AiServiceNavigationCard(
            title = "Claude",
            subtitle = "Anthropic",
            accentColor = Color(0xFFD97706),
            onClick = { onNavigate(SettingsSubScreen.AI_CLAUDE) }
        )
        AiServiceNavigationCard(
            title = "Gemini",
            subtitle = "Google",
            accentColor = Color(0xFF4285F4),
            onClick = { onNavigate(SettingsSubScreen.AI_GEMINI) }
        )
        AiServiceNavigationCard(
            title = "Grok",
            subtitle = "xAI",
            accentColor = Color(0xFFFFFFFF),
            onClick = { onNavigate(SettingsSubScreen.AI_GROK) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            subtitle = "Groq",
            accentColor = Color(0xFFF55036),
            onClick = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            subtitle = "DeepSeek AI",
            accentColor = Color(0xFF4D6BFE),
            onClick = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            subtitle = "Mistral AI",
            accentColor = Color(0xFFFF7000),
            onClick = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            subtitle = "Perplexity AI",
            accentColor = Color(0xFF20B2AA),
            onClick = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "Together",
            subtitle = "Together AI",
            accentColor = Color(0xFF6366F1),
            onClick = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            subtitle = "OpenRouter AI",
            accentColor = Color(0xFF6B5AED),
            onClick = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        // Dummy provider only visible in developer mode
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                subtitle = "For testing",
                accentColor = Color(0xFF888888),
                onClick = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }
    }
}
