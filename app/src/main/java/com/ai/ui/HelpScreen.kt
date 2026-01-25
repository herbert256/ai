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
                content = "Create AI-powered reports using 13 different AI services simultaneously. " +
                    "Configure multiple agents with custom parameters, organize them into swarms, " +
                    "and compare responses side-by-side."
            )

            // Getting Started
            HelpSection(
                title = "Getting Started",
                icon = "\uD83D\uDE80",
                content = "1. Go to Settings > AI Setup > AI Agents\n" +
                    "2. Tap '+ Add Agent' to create your first agent\n" +
                    "3. Select a provider (ChatGPT, Claude, etc.)\n" +
                    "4. Enter your API key for that service\n" +
                    "5. Optionally configure advanced parameters\n" +
                    "6. Save and return to AI Hub\n" +
                    "7. Tap 'New AI Report' to generate your first report"
            )

            // AI Hub
            HelpSection(
                title = "AI Hub",
                icon = "\uD83D\uDCDD",
                content = "The home screen provides access to all AI features:\n\n" +
                    "• New AI Report - Create reports with custom prompts\n" +
                    "• Prompt History - Reuse previously submitted prompts\n" +
                    "• AI History - View and share generated reports\n\n" +
                    "Use the gear icon for Settings, bug icon for API traces (developer mode)."
            )

            // AI Services
            HelpSection(
                title = "Supported AI Services",
                icon = "\uD83E\uDD16",
                content = "The app supports 13 AI services:\n\n" +
                    "• ChatGPT (OpenAI) - GPT-4o, GPT-5.x, o3, o4\n" +
                    "• Claude (Anthropic) - Claude 4, Claude 3.5\n" +
                    "• Gemini (Google) - Gemini 2.0 Flash\n" +
                    "• Grok (xAI) - With optional web search\n" +
                    "• Groq - Ultra-fast inference\n" +
                    "• DeepSeek - Reasoning models with think sections\n" +
                    "• Mistral - European AI\n" +
                    "• Perplexity - Web search with citations\n" +
                    "• Together AI - Open-source models\n" +
                    "• OpenRouter - Access multiple providers\n" +
                    "• SiliconFlow - Chinese AI models\n" +
                    "• Z.AI - Latest AI models\n\n" +
                    "Each service requires an API key from the provider's website."
            )

            // AI Agents
            HelpSection(
                title = "AI Agents",
                icon = "\u2699",
                content = "Agents are your configured AI assistants:\n\n" +
                    "Each agent combines:\n" +
                    "• Name - For easy identification\n" +
                    "• Provider - Which AI service to use\n" +
                    "• Model - Which model (fetched via API or manual)\n" +
                    "• API Key - Your credentials for that service\n" +
                    "• Parameters - Optional advanced settings\n\n" +
                    "Create multiple agents to compare different services or configurations."
            )

            // AI Swarms
            HelpSection(
                title = "AI Swarms",
                icon = "\uD83D\uDC1D",
                content = "Swarms let you group agents for quick selection:\n\n" +
                    "Creating a swarm:\n" +
                    "1. Go to Settings > AI Setup > AI Swarms\n" +
                    "2. Tap 'Add Swarm'\n" +
                    "3. Enter a name and select agents to include\n" +
                    "4. Save the swarm\n\n" +
                    "Using swarms:\n" +
                    "• When generating a report, select a swarm to include all its agents\n" +
                    "• Your swarm selections are remembered for next time\n" +
                    "• Combine swarms with individual agent selection"
            )

            // Agent Parameters
            HelpSection(
                title = "Agent Parameters",
                icon = "\uD83D\uDD27",
                content = "Each agent can be customized with advanced parameters:\n\n" +
                    "• Temperature (0-2) - Creativity level. Lower = focused, higher = creative\n" +
                    "• Max Tokens - Maximum response length\n" +
                    "• System Prompt - Instructions for AI behavior\n" +
                    "• Top P / Top K - Sampling parameters\n" +
                    "• Frequency/Presence Penalty - Reduce repetition\n" +
                    "• Seed - For reproducible outputs\n\n" +
                    "Provider-specific options:\n" +
                    "• ChatGPT: JSON response format\n" +
                    "• Grok: Web search toggle\n" +
                    "• Perplexity: Citations, search recency filter\n\n" +
                    "Leave parameters empty to use provider defaults."
            )

            // Creating Reports
            HelpSection(
                title = "Creating AI Reports",
                icon = "\uD83D\uDCCA",
                content = "To create a new AI report:\n\n" +
                    "1. Tap 'New AI Report' from AI Hub\n" +
                    "2. Enter a title (optional) and your prompt\n" +
                    "3. Tap the 'Generate' button at the top\n" +
                    "4. Select agents individually or by swarm\n" +
                    "5. Watch real-time progress as agents respond\n" +
                    "6. Tap STOP to end early if needed\n\n" +
                    "Multiple agents run in parallel for fast comparison.\n\n" +
                    "Tip: Use @DATE@ in your prompt to insert the current date."
            )

            // Report Results
            HelpSection(
                title = "Report Results",
                icon = "\uD83D\uDCC4",
                content = "After generation, the results dialog shows:\n\n" +
                    "• Agent buttons - Toggle visibility of each response\n" +
                    "• Markdown rendering - Formatted AI responses\n" +
                    "• Think sections - Expandable AI reasoning (when available)\n" +
                    "• Citations & sources - When provided by Perplexity/Grok\n" +
                    "• Token usage - Input/output counts (developer mode)\n\n" +
                    "Actions:\n" +
                    "• View in Chrome - Open as interactive HTML\n" +
                    "• Share via Email - Send as attachment\n" +
                    "• Close - Return to report screen"
            )

            // Think Sections
            HelpSection(
                title = "Think Sections",
                icon = "\uD83D\uDCA1",
                content = "Some AI models show their reasoning process:\n\n" +
                    "• DeepSeek reasoning models include <think>...</think> sections\n" +
                    "• These show how the AI arrives at its answer\n" +
                    "• Click the 'Think' button to expand/collapse\n" +
                    "• Available in both in-app viewer and HTML reports\n\n" +
                    "This helps understand AI decision-making while keeping responses clean."
            )

            // Prompt History
            HelpSection(
                title = "Prompt History",
                icon = "\uD83D\uDCDC",
                content = "Previously submitted prompts are saved for reuse:\n\n" +
                    "• Access from AI Hub > Prompt History\n" +
                    "• Tap any entry to reuse that prompt\n" +
                    "• History stores up to 100 entries\n" +
                    "• Shows title, prompt preview, and timestamp\n" +
                    "• Most recent prompts appear first"
            )

            // AI History
            HelpSection(
                title = "AI History",
                icon = "\uD83D\uDCC2",
                content = "Generated reports are stored locally as HTML:\n\n" +
                    "• Access from AI Hub > AI History\n" +
                    "• Tap to view report in Chrome\n" +
                    "• Long-press or use menu to share/delete\n" +
                    "• Reports include all agent responses\n" +
                    "• Toggle agent visibility with buttons\n" +
                    "• Reports persist until manually deleted"
            )

            // Special AI Features
            HelpSection(
                title = "Special AI Features",
                icon = "\u2728",
                content = "Some AI services provide enhanced responses:\n\n" +
                    "• Perplexity - Citations with source URLs, search results, related questions\n" +
                    "• Grok - Web search results when enabled\n" +
                    "• DeepSeek - Reasoning models show thinking process\n" +
                    "• ChatGPT - JSON response format option\n\n" +
                    "These extras are automatically displayed in reports when available."
            )

            // Export/Import Configuration
            HelpSection(
                title = "Export/Import Configuration",
                icon = "\uD83D\uDCBE",
                content = "Backup and restore your AI configuration:\n\n" +
                    "Export:\n" +
                    "• Settings > AI Setup > Export Configuration\n" +
                    "• Saves providers, agents, swarms, and API keys as JSON (v6)\n" +
                    "• Share via email or other apps\n\n" +
                    "Import:\n" +
                    "• Settings > AI Setup > Import from File\n" +
                    "• Or paste JSON and tap Import from Clipboard\n" +
                    "• Supports versions 3, 4, 5, and 6\n\n" +
                    "All agent parameters and swarms are preserved in exports."
            )

            // External App Integration
            HelpSection(
                title = "External App Integration",
                icon = "\uD83D\uDD17",
                content = "Other apps can launch AI to generate reports:\n\n" +
                    "When launched from another app:\n" +
                    "• AI opens directly to New Report screen\n" +
                    "• Title and prompt are pre-filled\n" +
                    "• Select agents or swarms and generate as usual\n" +
                    "• Results stay in AI app's history\n\n" +
                    "See CALL_AI.md for integration details."
            )

            // Settings Overview
            HelpSection(
                title = "Settings Overview",
                icon = "\u2699",
                content = "Customize the app:\n\n" +
                    "General:\n" +
                    "• Pagination size (5-50 items per page)\n" +
                    "• Developer mode toggle\n" +
                    "• API call tracing (when developer mode enabled)\n\n" +
                    "AI Setup:\n" +
                    "• Configure providers and models\n" +
                    "• Create and manage agents\n" +
                    "• Create and manage swarms\n" +
                    "• Export/import configuration"
            )

            // Developer Mode
            HelpSection(
                title = "Developer Mode",
                icon = "\uD83D\uDC1B",
                content = "Enable in Settings > General > Developer Mode:\n\n" +
                    "Features unlocked:\n" +
                    "• DUMMY provider for testing (no API needed)\n" +
                    "• API tracing option (same settings card)\n" +
                    "• Token usage shown in reports\n" +
                    "• HTTP headers visible in reports\n\n" +
                    "API Tracing (when enabled):\n" +
                    "• All API calls logged with request/response\n" +
                    "• Access via bug icon in title bar\n" +
                    "• Color-coded status codes\n" +
                    "• Sensitive headers (API keys) are masked\n" +
                    "• Useful for debugging API issues"
            )

            // Privacy & Security
            HelpSection(
                title = "Privacy & Security",
                icon = "\uD83D\uDD12",
                content = "Your data stays on your device:\n\n" +
                    "• All reports stored locally\n" +
                    "• No analytics or telemetry\n" +
                    "• API keys in app's private storage\n" +
                    "• Keys masked in API traces (first 4 + last 4 chars)\n" +
                    "• Direct API calls to providers only"
            )

            // Troubleshooting
            HelpSection(
                title = "Troubleshooting",
                icon = "\uD83D\uDEE0",
                content = "Common issues:\n\n" +
                    "\"API key not configured\"\n" +
                    "\u2192 Add your API key when creating the agent\n\n" +
                    "\"Network error\"\n" +
                    "\u2192 Check internet connection (retries automatically once)\n\n" +
                    "\"Model not found\"\n" +
                    "\u2192 Model may be deprecated, try fetching fresh models\n\n" +
                    "Slow responses?\n" +
                    "\u2192 AI APIs can take minutes for complex prompts. Timeout is 7 min.\n\n" +
                    "For API debugging, enable Developer Mode and API tracing in Settings > General."
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
