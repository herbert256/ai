package com.ai.ui

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
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack
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
            onAiClick = onNavigateHome
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
                content = "Create and manage AI-powered reports using 10 different AI services. " +
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
                content = "The app supports 10 AI services:\n\n" +
                    "• ChatGPT (OpenAI)\n" +
                    "• Claude (Anthropic)\n" +
                    "• Gemini (Google)\n" +
                    "• Grok (xAI)\n" +
                    "• Groq (ultra-fast inference)\n" +
                    "• DeepSeek\n" +
                    "• Mistral\n" +
                    "• Perplexity (with web search)\n" +
                    "• Together AI\n" +
                    "• OpenRouter (access multiple models)\n\n" +
                    "Each service requires an API key from the provider."
            )

            // Three-Tier AI Architecture
            HelpSection(
                title = "AI Setup: Three-Tier Architecture",
                icon = "⚙",
                content = "Configure AI analysis with three components:\n\n" +
                    "1. AI Providers - Configure model source (API or Manual) and available models per service\n\n" +
                    "2. AI Prompts - Create reusable prompt templates. Use @DATE@ placeholder for current date\n\n" +
                    "3. AI Agents - Your working configurations combining:\n" +
                    "   • Name for easy identification\n" +
                    "   • Provider (which AI service)\n" +
                    "   • Model (which model to use)\n" +
                    "   • API Key (your credentials)\n\n" +
                    "Create multiple agents to compare responses from different AI services."
            )

            // Creating Reports
            HelpSection(
                title = "Creating AI Reports",
                icon = "\uD83D\uDCCA",
                content = "To create a new AI report:\n\n" +
                    "1. Tap 'New AI Report' from the AI Hub\n" +
                    "2. Enter a title and your prompt\n" +
                    "3. Select which agents to use (one or multiple)\n" +
                    "4. Tap 'Generate' to create the report\n\n" +
                    "Multiple agents run in parallel for fast comparison. " +
                    "Toggle individual agent responses on/off in the report viewer."
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
                    "• Reports include all agent responses\n" +
                    "• Markdown formatting rendered as HTML\n" +
                    "• Toggle visibility of individual agent responses"
            )

            // Special AI Features
            HelpSection(
                title = "Special AI Features",
                icon = "✨",
                content = "Some AI services provide enhanced responses:\n\n" +
                    "• Perplexity - Includes citations, search results, and related questions\n" +
                    "• Grok - May include web search results\n" +
                    "• DeepSeek - Reasoning models show thinking process\n\n" +
                    "These extras are automatically included in HTML reports when available."
            )

            // Export Features
            HelpSection(
                title = "Export Features",
                icon = "\uD83D\uDCE4",
                content = "Share your AI reports:\n\n" +
                    "• View in Chrome - Open as interactive HTML\n" +
                    "• Share via Email - Send HTML as attachment (email saved for reuse)\n" +
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

            // Export/Import Configuration
            HelpSection(
                title = "Export/Import Configuration",
                icon = "\uD83D\uDCBE",
                content = "Share your AI configuration between devices:\n\n" +
                    "• Export: Settings > AI Setup > Export Configuration\n" +
                    "• Saves providers, prompts, and agents as JSON\n" +
                    "• Import: Settings > AI Setup > Import from Clipboard\n" +
                    "• Copy JSON to clipboard, then import\n\n" +
                    "Note: API keys are NOT included in exports for security."
            )

            // API Tracing
            HelpSection(
                title = "Developer: API Tracing",
                icon = "\uD83D\uDC1B",
                content = "Enable 'Track API calls' in Developer settings to log all network requests:\n\n" +
                    "• All AI service API calls are logged\n" +
                    "• View requests and responses in trace viewer\n" +
                    "• See HTTP status codes (color-coded)\n" +
                    "• Inspect full JSON request/response bodies\n" +
                    "• Sensitive headers (API keys) are masked\n" +
                    "• Access via bug icon in title bar\n" +
                    "• Useful for debugging API issues"
            )

            // About
            HelpSection(
                title = "About",
                icon = "ℹ",
                content = "AI analysis powered by OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, and OpenRouter.\n\n" +
                    "• All data stored locally on your device\n" +
                    "• No analytics or telemetry\n" +
                    "• API keys stored securely in app's private storage"
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
