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
                content = "Create AI-powered reports and have conversations using ${com.ai.data.AiService.entries.size} different AI services. " +
                    "Configure multiple agents with custom parameters and endpoints, organize them into flocks, " +
                    "compare responses side-by-side, track costs, and chat with any AI model using real-time streaming."
            )

            // Getting Started
            HelpSection(
                title = "Getting Started",
                icon = "\uD83D\uDE80",
                content = "Quick setup:\n\n" +
                    "1. Go to AI Setup > AI Providers\n" +
                    "2. Select a provider and enter your API key\n" +
                    "3. (Optional) Configure endpoints if needed\n" +
                    "4. Go to AI Setup > AI Agents\n" +
                    "5. Tap '+ Add Agent' or 'Create default agents'\n" +
                    "6. Go to AI Setup > AI Flocks\n" +
                    "7. Create a flock with your agents\n" +
                    "8. Return to AI Hub and start using the app!\n\n" +
                    "Tip: Use 'Create default agents' to quickly create one agent per configured provider.\n\n" +
                    "The home screen will guide you with warnings if setup is incomplete."
            )

            // AI Hub
            HelpSection(
                title = "AI Hub (Home)",
                icon = "\uD83C\uDFE0",
                content = "The central hub showing all features:\n\n" +
                    "• AI Reports - Generate multi-agent reports\n" +
                    "• AI Chat - Start or continue conversations with streaming\n" +
                    "• AI Models - Search and explore all available models\n" +
                    "• AI Statistics - Track API calls and token usage\n" +
                    "• AI Costs - View estimated costs per model\n" +
                    "• AI Setup - Configure providers, agents, flocks\n" +
                    "• Settings - App preferences and cost configuration\n" +
                    "• Help - This documentation\n" +
                    "• Developer Options - API Test and Traces (developer mode)\n\n" +
                    "Cards appear based on your setup status. Warnings guide you through initial configuration."
            )

            // AI Reports
            HelpSection(
                title = "AI Reports",
                icon = "\uD83D\uDCDD",
                content = "Generate reports using multiple AI agents:\n\n" +
                    "Reports Hub:\n" +
                    "• New Report - Create a new AI report\n" +
                    "• Prompt History - Reuse previous prompts\n" +
                    "• AI History - View past reports\n\n" +
                    "Creating a report:\n" +
                    "1. Enter a title (optional) and your prompt\n" +
                    "2. Use @DATE@ to insert current date automatically\n" +
                    "3. Tap 'Generate' and select agents/flocks\n" +
                    "4. Watch real-time progress with token counts\n" +
                    "5. View results, share, or open in browser\n\n" +
                    "Reports are stored persistently and can be exported as JSON or HTML."
            )

            // Report Results
            HelpSection(
                title = "Report Results",
                icon = "\uD83D\uDCC4",
                content = "View and share your results:\n\n" +
                    "Progress screen shows:\n" +
                    "• Agent name, provider, model\n" +
                    "• Status (pending, running, success, error)\n" +
                    "• Input/Output token counts\n" +
                    "• Cost in cents per agent\n\n" +
                    "Actions when complete:\n" +
                    "• View - See formatted responses in-app\n" +
                    "• Share - Export as JSON or HTML\n" +
                    "• Browser - Open interactive HTML report\n" +
                    "• Close - Return to reports hub\n\n" +
                    "Features:\n" +
                    "• Markdown rendering with formatting\n" +
                    "• Collapsible 'Think' sections for reasoning\n" +
                    "• Citations and search results (Perplexity, xAI)\n" +
                    "• Token usage details (developer mode)"
            )

            // AI Chat
            HelpSection(
                title = "AI Chat",
                icon = "\uD83D\uDCAC",
                content = "Have multi-turn conversations with any AI:\n\n" +
                    "Starting a chat:\n" +
                    "1. Tap 'AI Chat' on the home screen\n" +
                    "2. Choose 'New chat' or 'Continue previous'\n" +
                    "3. Select a provider (shows only configured ones)\n" +
                    "4. Select a model (search to filter)\n" +
                    "5. Optionally configure parameters\n" +
                    "6. Start chatting!\n\n" +
                    "Chat features:\n" +
                    "• Streaming responses (real-time word-by-word)\n" +
                    "• System prompt for AI behavior instructions\n" +
                    "• Temperature, max tokens, and other parameters\n" +
                    "• Web search (xAI) and citations (Perplexity)\n" +
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
                icon = "\uD83E\uDDE0",
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
                    "• Pricing per token (input/output)\n" +
                    "• Architecture and modality\n" +
                    "• Provider information\n\n" +
                    "Data from Hugging Face (if API key configured):\n" +
                    "• Author and organization\n" +
                    "• Downloads and likes\n" +
                    "• Tags and license info\n\n" +
                    "AI-generated description:\n" +
                    "• Create an AI Prompt named 'model_info'\n" +
                    "• Assign an agent to generate descriptions\n" +
                    "• Use @MODEL@ and @PROVIDER@ variables\n" +
                    "• Description auto-generates when viewing model info"
            )

            // AI Statistics
            HelpSection(
                title = "AI Statistics",
                icon = "\uD83D\uDCCA",
                content = "Track your AI usage:\n\n" +
                    "Summary shows:\n" +
                    "• Total API calls made\n" +
                    "• Input token count (prompts sent)\n" +
                    "• Output token count (responses received)\n" +
                    "• Total tokens processed\n\n" +
                    "Per-model breakdown:\n" +
                    "• Provider and model name\n" +
                    "• Number of calls\n" +
                    "• Token usage details\n\n" +
                    "• Clear Statistics button to reset all data"
            )

            // AI Costs
            HelpSection(
                title = "AI Costs",
                icon = "\uD83D\uDCB0",
                content = "Track estimated costs for your AI usage:\n\n" +
                    "Pricing sources (in order of priority):\n" +
                    "1. Manual overrides (set in Cost Configuration)\n" +
                    "2. OpenRouter API pricing (weekly cached)\n" +
                    "3. LiteLLM community pricing (built-in)\n" +
                    "4. Fallback estimates\n\n" +
                    "Features:\n" +
                    "• Total estimated cost across all models\n" +
                    "• Input/output cost breakdown\n" +
                    "• Expandable provider groups\n" +
                    "• Per-model cost details\n" +
                    "• Refresh button to update pricing\n" +
                    "• Color-coded pricing sources\n\n" +
                    "Cost Configuration (Settings):\n" +
                    "• Set manual price overrides per model\n" +
                    "• Prices in dollars per million tokens"
            )

            // AI History
            HelpSection(
                title = "AI History",
                icon = "\uD83D\uDCC2",
                content = "Browse and manage generated reports:\n\n" +
                    "• Search by title, prompt, or content\n" +
                    "• Paginated list view\n" +
                    "• Tap a report to see options\n\n" +
                    "Actions per report:\n" +
                    "• View - See formatted responses in-app\n" +
                    "• Share - Export as JSON or HTML\n" +
                    "• Browser - Open interactive HTML report\n" +
                    "• Delete - Remove report (with confirmation)\n\n" +
                    "Reports are stored persistently until deleted."
            )

            // AI Services
            HelpSection(
                title = "Supported AI Services",
                icon = "\uD83E\uDD16",
                content = "${com.ai.data.AiService.entries.size} AI services supported:\n\n" +
                    "• OpenAI - GPT-4o, GPT-5.x, o3, o4\n" +
                    "  Endpoints: Chat Completions, Responses API\n" +
                    "• Anthropic - Claude 4, Claude 3.5 (hardcoded list)\n" +
                    "• Google - Gemini 2.0 Flash and more\n" +
                    "• xAI - Grok with optional web search\n" +
                    "• Groq - Ultra-fast inference\n" +
                    "• DeepSeek - Reasoning with think sections\n" +
                    "  Endpoints: Standard, Beta (FIM)\n" +
                    "• Mistral - European AI models\n" +
                    "  Endpoints: Standard, Codestral (code)\n" +
                    "• Perplexity - Web search with citations\n" +
                    "• Together AI - Open-source models\n" +
                    "• OpenRouter - Multiple providers, unified API\n" +
                    "• SiliconFlow - Qwen, DeepSeek models\n" +
                    "  Endpoints: OpenAI-compatible, Anthropic-compatible\n" +
                    "• Z.AI - GLM models (ZhipuAI)\n" +
                    "  Endpoints: General, Coding\n" +
                    "\n" +
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
                    "• Model - Which model (optional, uses provider default)\n" +
                    "• API Key - Your credentials (optional, uses provider key)\n" +
                    "• Endpoint - API endpoint (optional, uses provider default)\n" +
                    "• Parameters - Advanced settings\n\n" +
                    "Agent actions:\n" +
                    "• Edit - Modify configuration\n" +
                    "• Test - Verify API connectivity\n" +
                    "• Duplicate - Copy with same settings\n" +
                    "• Delete - Remove agent\n\n" +
                    "Quick setup:\n" +
                    "• Use 'Create default agents' to auto-create one agent per configured provider\n" +
                    "• These agents inherit all settings from their provider\n\n" +
                    "Create multiple agents to compare services or configurations."
            )

            // AI Flocks
            HelpSection(
                title = "AI Flocks",
                icon = "\uD83D\uDC1D",
                content = "Group agents for quick selection:\n\n" +
                    "Creating a flock:\n" +
                    "1. Go to AI Setup > AI Flocks\n" +
                    "2. Tap 'Add Flock'\n" +
                    "3. Enter a name\n" +
                    "4. Select agents to include\n" +
                    "5. Save\n\n" +
                    "Using flocks:\n" +
                    "• Select flocks when generating reports\n" +
                    "• Combine with individual agent selection\n" +
                    "• Selections are remembered for next time"
            )

            // AI Prompts
            HelpSection(
                title = "AI Prompts",
                icon = "\uD83D\uDCDD",
                content = "Internal prompts for AI-powered app features:\n\n" +
                    "Used by:\n" +
                    "• Model Info - Create agent named 'model_info'\n" +
                    "• Auto-generates model descriptions\n\n" +
                    "Creating a prompt:\n" +
                    "1. Go to AI Setup > AI Prompts\n" +
                    "2. Tap 'Add Prompt'\n" +
                    "3. Enter name (e.g., 'model_info')\n" +
                    "4. Select an agent to execute the prompt\n" +
                    "5. Write the prompt template\n\n" +
                    "Variables (auto-replaced):\n" +
                    "• @MODEL@ - Model name\n" +
                    "• @PROVIDER@ - Provider name\n" +
                    "• @AGENT@ - Agent name\n" +
                    "• @SWARM@ - Flock name\n" +
                    "• @NOW@ - Current date/time"
            )

            // Provider Configuration
            HelpSection(
                title = "Provider Configuration",
                icon = "\uD83D\uDD27",
                content = "Each AI provider can be configured with:\n\n" +
                    "API Key:\n" +
                    "• Required for API access\n" +
                    "• Test button to verify connectivity\n\n" +
                    "API Endpoints:\n" +
                    "• Multiple endpoints per provider\n" +
                    "• Add custom endpoints for self-hosted servers\n" +
                    "• Set default endpoint for the provider\n" +
                    "• OpenAI: Chat Completions vs Responses API\n" +
                    "• DeepSeek: Standard vs Beta (FIM/prefix)\n" +
                    "• Mistral: Standard vs Codestral (code)\n" +
                    "• SiliconFlow: OpenAI vs Anthropic compatible\n" +
                    "• Z.AI: General vs Coding endpoint\n\n" +
                    "Model List URL:\n" +
                    "• Custom URL for fetching available models\n" +
                    "• Useful for self-hosted or proxy servers\n\n" +
                    "Model Selection:\n" +
                    "• API mode: Fetch models from provider\n" +
                    "• Manual mode: Maintain your own model list"
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
                    "• Seed - Reproducible outputs\n" +
                    "• Stop Sequences - Stop generation at keywords\n\n" +
                    "Provider-specific:\n" +
                    "• OpenAI: JSON response format\n" +
                    "• xAI: Web search toggle\n" +
                    "• Perplexity: Citations, search recency filter\n\n" +
                    "Inheritance:\n" +
                    "• Leave API key empty → uses provider key\n" +
                    "• Leave model empty → uses provider model\n" +
                    "• Leave endpoint empty → uses provider endpoint\n" +
                    "• Leave parameters empty → uses provider defaults"
            )

            // Think Sections
            HelpSection(
                title = "Think Sections",
                icon = "\uD83D\uDCA1",
                content = "View AI reasoning process:\n\n" +
                    "• DeepSeek and other reasoning models show thinking\n" +
                    "• Displayed as collapsible 'Think' buttons\n" +
                    "• Click to expand/collapse\n" +
                    "• Available in-app and in HTML reports\n\n" +
                    "Helps understand how the AI reaches its conclusions."
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

            // Export/Import Configuration
            HelpSection(
                title = "Export/Import Configuration",
                icon = "\uD83D\uDCBE",
                content = "Backup and restore your setup:\n\n" +
                    "Export (AI Setup page):\n" +
                    "• Saves all providers, agents, flocks, prompts\n" +
                    "• Includes API keys, parameters, endpoints\n" +
                    "• Includes model list URLs and admin URLs\n" +
                    "• Includes manual pricing overrides\n" +
                    "• Includes Hugging Face API key\n" +
                    "• JSON format (version 14)\n\n" +
                    "Import:\n" +
                    "• From file picker or clipboard\n" +
                    "• Requires version 11-14\n" +
                    "• All settings restored including endpoints\n\n" +
                    "Refresh model lists: Updates all API-source providers at once."
            )

            // Settings Overview
            HelpSection(
                title = "Settings",
                icon = "\u2699",
                content = "Customize the app:\n\n" +
                    "General:\n" +
                    "• Username for chat display\n" +
                    "• Full screen mode toggle\n" +
                    "• Developer mode toggle\n" +
                    "• API call tracing (dev mode)\n\n" +
                    "External Services:\n" +
                    "• Hugging Face API Key - For model info\n\n" +
                    "Cost Configuration:\n" +
                    "• Set manual price overrides per model\n" +
                    "• Prices in dollars per million tokens\n" +
                    "• Manual overrides have highest priority\n\n" +
                    "AI Setup:\n" +
                    "• Provider configuration (keys, endpoints, models)\n" +
                    "• Agent management (create, edit, duplicate)\n" +
                    "• Flock management (group agents)\n" +
                    "• AI Prompts (internal app prompts)\n" +
                    "• Create default agents (one per provider)\n" +
                    "• Refresh model lists (all providers)\n" +
                    "• Export/Import configuration (v11)"
            )

            // Developer Mode
            HelpSection(
                title = "Developer Mode",
                icon = "\uD83D\uDC1B",
                content = "Enable in Settings:\n\n" +
                    "Features unlocked:\n" +
                    "• API call tracing option\n" +
                    "• Token usage in reports\n" +
                    "• HTTP headers in reports\n" +
                    "• Developer Options on home screen\n\n" +
                    "Developer Options:\n" +
                    "• API Test - Test API calls with custom settings\n" +
                    "• API Traces - View logged API requests/responses\n" +
                    "• Color-coded HTTP status codes\n" +
                    "• API keys are masked for security"
            )

            // External App Integration
            HelpSection(
                title = "External App Integration",
                icon = "\uD83D\uDD17",
                content = "Other apps can launch AI:\n\n" +
                    "Intent action: com.ai.ACTION_NEW_REPORT\n\n" +
                    "• Opens directly to New Report screen\n" +
                    "• Title and prompt can be pre-filled\n" +
                    "• Select agents and generate report\n" +
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
                    "• Keys masked in API traces\n" +
                    "• Direct API calls only to providers\n" +
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
                    "→ Use 'Refresh model lists' in AI Setup\n" +
                    "→ Check if model is available for your API tier\n\n" +
                    "\"No pricing data\"\n" +
                    "→ Add OpenRouter API key or use Cost Configuration\n\n" +
                    "\"Wrong endpoint\"\n" +
                    "→ Check provider settings for correct endpoint\n" +
                    "→ OpenAI: gpt-4o uses Chat, gpt-5.x uses Responses\n\n" +
                    "Slow responses?\n" +
                    "→ Complex prompts can take minutes (7 min timeout)\n\n" +
                    "Streaming not working?\n" +
                    "→ All providers support streaming for chat\n\n" +
                    "Agent using wrong settings?\n" +
                    "→ Empty fields inherit from provider settings\n" +
                    "→ Check provider has correct defaults\n\n" +
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
