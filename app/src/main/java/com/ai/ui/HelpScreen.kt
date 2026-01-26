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
                content = "Create AI-powered reports and have conversations using 13 different AI services. " +
                    "Configure multiple agents with custom parameters, organize them into swarms, " +
                    "compare responses side-by-side, and chat with any AI model."
            )

            // Getting Started
            HelpSection(
                title = "Getting Started",
                icon = "\uD83D\uDE80",
                content = "Quick setup:\n\n" +
                    "1. Go to AI Setup > AI Providers\n" +
                    "2. Select a provider and enter your API key\n" +
                    "3. Go to AI Setup > AI Agents\n" +
                    "4. Tap '+ Add Agent' to create your first agent\n" +
                    "5. Go to AI Setup > AI Swarms\n" +
                    "6. Create a swarm with your agents\n" +
                    "7. Return to AI Hub and start using the app!\n\n" +
                    "The home screen will guide you with warnings if setup is incomplete."
            )

            // AI Hub
            HelpSection(
                title = "AI Hub (Home)",
                icon = "\uD83C\uDFE0",
                content = "The central hub showing all features:\n\n" +
                    "• AI Report - Generate reports with multiple agents\n" +
                    "• AI Chat - Start or continue conversations\n" +
                    "• AI Models - Search and explore all available models\n" +
                    "• AI History - View generated reports\n" +
                    "• AI Statistics - Track usage metrics\n" +
                    "• AI Setup - Configure providers, agents, swarms\n" +
                    "• Settings - App preferences\n" +
                    "• Help - This documentation\n\n" +
                    "Cards appear based on your setup status. Warnings guide you through initial configuration."
            )

            // AI Chat
            HelpSection(
                title = "AI Chat",
                icon = "\uD83D\uDCAC",
                content = "Have multi-turn conversations with any AI:\n\n" +
                    "Starting a chat:\n" +
                    "1. Tap 'AI Chat' on the home screen\n" +
                    "2. Select a provider (shows only configured ones)\n" +
                    "3. Select a model\n" +
                    "4. Optionally configure parameters\n" +
                    "5. Start chatting!\n\n" +
                    "Chat features:\n" +
                    "• System prompt for AI behavior instructions\n" +
                    "• Temperature, max tokens, and other parameters\n" +
                    "• Web search (Grok) and citations (Perplexity)\n" +
                    "• Auto-saved conversations\n" +
                    "• Continue previous chats from Chat History"
            )

            // Chat History
            HelpSection(
                title = "Chat History",
                icon = "\uD83D\uDCDD",
                content = "Your conversations are automatically saved:\n\n" +
                    "• Access from AI Hub when starting a new chat\n" +
                    "• Shows preview of first message\n" +
                    "• Displays provider, model, and timestamp\n" +
                    "• Tap to continue any previous conversation\n" +
                    "• All messages and parameters restored\n" +
                    "• Paginated list for easy browsing"
            )

            // AI Models / Model Search
            HelpSection(
                title = "AI Models",
                icon = "\uD83D\uDD0D",
                content = "Search and explore models across all providers:\n\n" +
                    "• Unified search across all configured providers\n" +
                    "• Filter by model name\n" +
                    "• Color-coded by provider\n\n" +
                    "When you tap a model, choose:\n" +
                    "• Start AI Chat - Begin chatting with this model\n" +
                    "• Create AI Agent - Create an agent using this model\n" +
                    "• Model Info - View detailed model information"
            )

            // Model Info
            HelpSection(
                title = "Model Info",
                icon = "\u2139",
                content = "View detailed information about any model:\n\n" +
                    "Data from OpenRouter (if API key configured):\n" +
                    "• Context length and max tokens\n" +
                    "• Pricing per token\n" +
                    "• Architecture and modality\n\n" +
                    "Data from Hugging Face (if API key configured):\n" +
                    "• Author and organization\n" +
                    "• Downloads and likes\n" +
                    "• Tags and license info\n\n" +
                    "AI-generated description:\n" +
                    "• Create an agent named 'model_info' to auto-generate introductions"
            )

            // AI Services
            HelpSection(
                title = "Supported AI Services",
                icon = "\uD83E\uDD16",
                content = "13 AI services supported:\n\n" +
                    "• ChatGPT (OpenAI) - GPT-4o, GPT-5.x, o3, o4\n" +
                    "• Claude (Anthropic) - Claude 4, Claude 3.5\n" +
                    "• Gemini (Google) - Gemini 2.0 Flash\n" +
                    "• Grok (xAI) - With optional web search\n" +
                    "• Groq - Ultra-fast inference\n" +
                    "• DeepSeek - Reasoning with think sections\n" +
                    "• Mistral - European AI\n" +
                    "• Perplexity - Web search with citations\n" +
                    "• Together AI - Open-source models\n" +
                    "• OpenRouter - Multiple providers\n" +
                    "• SiliconFlow - Qwen, DeepSeek models\n" +
                    "• Z.AI - GLM models\n\n" +
                    "Each requires an API key from the provider's website."
            )

            // AI Agents
            HelpSection(
                title = "AI Agents",
                icon = "\u2699",
                content = "Agents are your configured AI assistants:\n\n" +
                    "Each agent has:\n" +
                    "• Name - For easy identification\n" +
                    "• Provider - Which AI service\n" +
                    "• Model - Which model to use\n" +
                    "• API Key - Your credentials\n" +
                    "• Parameters - Advanced settings\n\n" +
                    "Agent actions:\n" +
                    "• Edit - Modify configuration\n" +
                    "• Duplicate - Copy with same settings\n" +
                    "• Delete - Remove agent\n\n" +
                    "Create multiple agents to compare services or configurations."
            )

            // AI Swarms
            HelpSection(
                title = "AI Swarms",
                icon = "\uD83D\uDC1D",
                content = "Group agents for quick selection:\n\n" +
                    "Creating a swarm:\n" +
                    "1. Go to AI Setup > AI Swarms\n" +
                    "2. Tap 'Add Swarm'\n" +
                    "3. Enter a name\n" +
                    "4. Select agents to include\n" +
                    "5. Save\n\n" +
                    "Using swarms:\n" +
                    "• Select swarms when generating reports\n" +
                    "• Combine with individual agent selection\n" +
                    "• Selections are remembered for next time"
            )

            // Agent Parameters
            HelpSection(
                title = "Agent Parameters",
                icon = "\uD83D\uDD27",
                content = "Customize agent behavior:\n\n" +
                    "Common parameters:\n" +
                    "• Temperature (0-2) - Creativity level\n" +
                    "• Max Tokens - Response length limit\n" +
                    "• System Prompt - AI behavior instructions\n" +
                    "• Top P / Top K - Sampling settings\n" +
                    "• Frequency/Presence Penalty - Reduce repetition\n" +
                    "• Seed - Reproducible outputs\n\n" +
                    "Provider-specific:\n" +
                    "• ChatGPT: JSON response format\n" +
                    "• Grok: Web search toggle\n" +
                    "• Perplexity: Citations, search recency\n\n" +
                    "Leave empty to use provider defaults."
            )

            // Creating Reports
            HelpSection(
                title = "Creating AI Reports",
                icon = "\uD83D\uDCCA",
                content = "Generate reports with multiple agents:\n\n" +
                    "1. Tap 'AI Report' from AI Hub\n" +
                    "2. Enter a title (optional) and your prompt\n" +
                    "3. Tap 'Generate' button\n" +
                    "4. Select agents and/or swarms\n" +
                    "5. Watch real-time progress\n" +
                    "6. Tap STOP to end early if needed\n\n" +
                    "Special features:\n" +
                    "• Use @DATE@ to insert current date\n" +
                    "• Multiple agents run in parallel\n" +
                    "• Reports auto-saved to AI History"
            )

            // Report Results
            HelpSection(
                title = "Report Results",
                icon = "\uD83D\uDCC4",
                content = "View and share your results:\n\n" +
                    "Result features:\n" +
                    "• Agent buttons - Toggle visibility\n" +
                    "• Markdown rendering - Formatted responses\n" +
                    "• Think sections - Expandable AI reasoning\n" +
                    "• Citations - Source URLs (Perplexity/Grok)\n" +
                    "• Token usage - In developer mode\n\n" +
                    "Actions:\n" +
                    "• View in Chrome - Interactive HTML\n" +
                    "• Share via Email - Send as attachment\n" +
                    "• Close - Return to report screen"
            )

            // AI History
            HelpSection(
                title = "AI History",
                icon = "\uD83D\uDCC2",
                content = "Browse and manage generated reports:\n\n" +
                    "• Search by title, prompt, or content\n" +
                    "• Paginated list view\n" +
                    "• View reports in Chrome\n" +
                    "• Share via email\n" +
                    "• Delete unwanted reports\n\n" +
                    "Reports are stored locally as HTML files and persist until deleted."
            )

            // AI Statistics
            HelpSection(
                title = "AI Statistics",
                icon = "\uD83D\uDCCA",
                content = "Track your AI usage:\n\n" +
                    "• Total API calls made\n" +
                    "• Input and output token counts\n" +
                    "• Usage per provider and model\n" +
                    "• Clear statistics button\n\n" +
                    "Useful for monitoring costs and usage patterns."
            )

            // Prompt History
            HelpSection(
                title = "Prompt History",
                icon = "\uD83D\uDCDC",
                content = "Reuse previous prompts:\n\n" +
                    "• Automatically saves submitted prompts\n" +
                    "• Stores up to 100 entries\n" +
                    "• Shows title, prompt, and timestamp\n" +
                    "• Tap to reuse in New Report\n" +
                    "• Most recent prompts first"
            )

            // Think Sections
            HelpSection(
                title = "Think Sections",
                icon = "\uD83D\uDCA1",
                content = "View AI reasoning process:\n\n" +
                    "• DeepSeek reasoning models show thinking\n" +
                    "• Displayed as collapsible 'Think' buttons\n" +
                    "• Click to expand/collapse\n" +
                    "• Available in-app and in HTML reports\n\n" +
                    "Helps understand how the AI reaches its conclusions."
            )

            // Export/Import Configuration
            HelpSection(
                title = "Export/Import Configuration",
                icon = "\uD83D\uDCBE",
                content = "Backup and restore your setup:\n\n" +
                    "Export (AI Setup page):\n" +
                    "• Saves all providers, agents, swarms\n" +
                    "• Includes API keys and parameters\n" +
                    "• Includes Hugging Face API key\n" +
                    "• JSON format (version 7)\n\n" +
                    "Import:\n" +
                    "• From file picker or clipboard\n" +
                    "• Supports versions 3-7\n" +
                    "• All settings restored\n\n" +
                    "Refresh model lists: Updates all API-source providers at once."
            )

            // Settings Overview
            HelpSection(
                title = "Settings",
                icon = "\u2699",
                content = "Customize the app:\n\n" +
                    "General:\n" +
                    "• Username for chat display\n" +
                    "• Developer mode toggle\n" +
                    "• API call tracing (dev mode)\n\n" +
                    "External Services:\n" +
                    "• Hugging Face API Key - For model info\n\n" +
                    "AI Setup:\n" +
                    "• Provider configuration\n" +
                    "• Agent management\n" +
                    "• Swarm management\n" +
                    "• Refresh model lists\n" +
                    "• Export/Import configuration"
            )

            // Developer Mode
            HelpSection(
                title = "Developer Mode",
                icon = "\uD83D\uDC1B",
                content = "Enable in Settings:\n\n" +
                    "Features unlocked:\n" +
                    "• DUMMY provider for testing\n" +
                    "• API call tracing option\n" +
                    "• Token usage in reports\n" +
                    "• HTTP headers in reports\n\n" +
                    "API Tracing:\n" +
                    "• Logs all API requests/responses\n" +
                    "• Access via bug icon in title bar\n" +
                    "• Color-coded status codes\n" +
                    "• API keys are masked for security"
            )

            // External App Integration
            HelpSection(
                title = "External App Integration",
                icon = "\uD83D\uDD17",
                content = "Other apps can launch AI:\n\n" +
                    "• Opens directly to New Report\n" +
                    "• Title and prompt pre-filled\n" +
                    "• Select agents and generate\n" +
                    "• Results saved to AI History\n\n" +
                    "See CALL_AI.md for integration details."
            )

            // Privacy & Security
            HelpSection(
                title = "Privacy & Security",
                icon = "\uD83D\uDD12",
                content = "Your data stays on your device:\n\n" +
                    "• All reports stored locally\n" +
                    "• No analytics or telemetry\n" +
                    "• API keys in private storage\n" +
                    "• Keys masked in traces\n" +
                    "• Direct API calls only\n" +
                    "• HTTPS encryption in transit"
            )

            // Troubleshooting
            HelpSection(
                title = "Troubleshooting",
                icon = "\uD83D\uDEE0",
                content = "Common issues:\n\n" +
                    "\"No API key configured\"\n" +
                    "→ Add API key in AI Setup > AI Providers\n\n" +
                    "\"Network error\"\n" +
                    "→ Check internet, retries once automatically\n\n" +
                    "\"Model not found\"\n" +
                    "→ Use 'Refresh model lists' in AI Setup\n\n" +
                    "Slow responses?\n" +
                    "→ Complex prompts can take minutes (7 min timeout)\n\n" +
                    "For debugging, enable Developer Mode and API tracing."
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
