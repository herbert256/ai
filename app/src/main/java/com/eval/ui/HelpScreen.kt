package com.eval.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HelpScreen(
    onBack: () -> Unit
) {
    // Handle back navigation
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Help",
            onBackClick = onBack,
            onAiClick = onBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Welcome section
            HelpSection(
                title = "Welcome to AI",
                content = "Create and manage AI-powered reports using 9 different AI services. " +
                    "Configure providers, prompts, and agents to generate custom AI analysis on any topic."
            )

            // Getting Started
            HelpSection(
                title = "Getting Started",
                icon = "\uD83D\uDE80",
                content = "1. Go to Settings > AI Setup to configure your AI providers\n" +
                    "2. Add API keys for your preferred AI services\n" +
                    "3. Create prompts with custom templates\n" +
                    "4. Create agents that combine providers with prompts\n" +
                    "5. Use 'New AI Report' to generate reports with your agents"
            )

            // AI Hub
            HelpSection(
                title = "AI Hub",
                icon = "\uD83D\uDCDD",
                content = "The main screen provides access to AI features:\n\n" +
                    "• New AI Report - Create custom AI reports with any prompt\n" +
                    "• Prompt History - Reuse previously submitted prompts\n" +
                    "• AI History - View previously generated reports\n\n" +
                    "Reports are saved as HTML files you can view in Chrome or share."
            )

            // AI Services
            HelpSection(
                title = "Supported AI Services",
                icon = "\uD83E\uDD16",
                content = "The app supports 9 AI services:\n\n" +
                    "• ChatGPT (OpenAI)\n" +
                    "• Claude (Anthropic)\n" +
                    "• Gemini (Google)\n" +
                    "• Grok (xAI)\n" +
                    "• DeepSeek\n" +
                    "• Mistral\n" +
                    "• Perplexity\n" +
                    "• Together AI\n" +
                    "• OpenRouter\n\n" +
                    "Each service requires an API key from the provider."
            )

            // Three-Tier AI Architecture
            HelpSection(
                title = "AI Setup: Three-Tier Architecture",
                icon = "⚙",
                content = "Configure AI analysis with three components:\n\n" +
                    "1. Providers - AI services with model settings and API configuration\n\n" +
                    "2. Prompts - Reusable prompt templates. You can use placeholders like @DATE@ for dynamic content\n\n" +
                    "3. Agents - Combine a provider + model + API key + prompt to create a reusable configuration\n\n" +
                    "Create multiple agents for different analysis purposes."
            )

            // Creating Reports
            HelpSection(
                title = "Creating AI Reports",
                icon = "\uD83D\uDCCA",
                content = "To create a new AI report:\n\n" +
                    "1. Tap 'New AI Report' from the AI Hub\n" +
                    "2. Enter a title and your prompt\n" +
                    "3. Select which agents to use\n" +
                    "4. Tap 'Generate' to create the report\n\n" +
                    "Reports can include responses from multiple agents for comparison."
            )

            // Prompt History
            HelpSection(
                title = "Prompt History",
                icon = "\uD83D\uDCDC",
                content = "Previously submitted prompts are saved for reuse:\n\n" +
                    "• Access from AI Hub > Prompt History\n" +
                    "• Tap any entry to reuse that prompt\n" +
                    "• History stores up to 100 entries\n" +
                    "• Entries show title, prompt preview, and timestamp"
            )

            // AI History
            HelpSection(
                title = "AI History",
                icon = "\uD83D\uDCC2",
                content = "Generated reports are stored locally:\n\n" +
                    "• Access from AI Hub > AI History\n" +
                    "• View reports as HTML in Chrome\n" +
                    "• Share reports via email\n" +
                    "• Reports include all agent responses"
            )

            // Export Features
            HelpSection(
                title = "Export Features",
                icon = "\uD83D\uDCE4",
                content = "Share your AI reports:\n\n" +
                    "• View in Chrome - Open as interactive HTML\n" +
                    "• Share via Email - Send HTML as attachment\n" +
                    "• Use Android share sheet for other apps"
            )

            // Settings Overview
            HelpSection(
                title = "Settings Overview",
                icon = "⚙",
                content = "Customize the app:\n\n" +
                    "• General - Pagination settings\n" +
                    "• AI Setup - Providers, prompts, agents configuration\n" +
                    "• Developer - API tracking for debugging"
            )

            // API Tracing
            HelpSection(
                title = "Developer: API Tracing",
                icon = "\uD83D\uDC1B",
                content = "Enable 'Track API calls' in Developer settings to log all network requests:\n\n" +
                    "• All AI service API calls are logged\n" +
                    "• View requests/responses in the trace viewer\n" +
                    "• Useful for debugging API issues\n" +
                    "• Traces are cleared when tracking is disabled"
            )

            // About
            HelpSection(
                title = "About",
                icon = "ℹ",
                content = "AI analysis powered by OpenAI, Anthropic, Google, xAI, DeepSeek, Mistral, Perplexity, Together AI, and OpenRouter.\n\n" +
                    "All data stored locally on your device."
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    content: String,
    icon: String? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Text(
                        text = icon,
                        fontSize = 24.sp
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B9BFF)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFCCCCCC),
                lineHeight = 22.sp
            )
        }
    }
}
