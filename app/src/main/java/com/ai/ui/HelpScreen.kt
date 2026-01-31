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
                    "Configure multiple agents with custom parameters and endpoints, organize them into flocks and swarms, " +
                    "compare responses side-by-side, track costs and usage, and chat with any AI model using real-time streaming."
            )

            // Getting Started
            HelpSection(
                title = "Getting Started",
                icon = "\uD83D\uDE80",
                content = "Quick setup:\n\n" +
                    "1. Go to AI Setup > Providers\n" +
                    "2. Select a provider and enter your API key\n" +
                    "3. Test the connection with the Test button\n" +
                    "4. Go to AI Housekeeping\n" +
                    "5. Tap 'Default agents' to auto-create agents\n" +
                    "6. Return to AI Hub and start!\n\n" +
                    "Or set up manually:\n" +
                    "1. Go to AI Setup > Agents\n" +
                    "2. Tap '+ Add Agent' and configure\n" +
                    "3. Go to AI Setup > Flocks\n" +
                    "4. Create a flock with your agents\n\n" +
                    "Tip: 'Default agents' creates one agent per working provider and groups them in a 'default agents' flock.\n\n" +
                    "The home screen shows warnings if setup is incomplete. Cards are grayed out until prerequisites are met."
            )

            // AI Hub
            HelpSection(
                title = "AI Hub (Home)",
                icon = "\uD83C\uDFE0",
                content = "The central hub with all features:\n\n" +
                    "• AI Reports - Generate multi-agent reports\n" +
                    "• AI Chat - Conversations with streaming\n" +
                    "• AI Models - Search and explore models\n" +
                    "• AI Statistics - API calls and token usage\n" +
                    "• AI Costs - Estimated costs per model\n" +
                    "• AI Setup - Providers, agents, flocks, swarms\n" +
                    "• AI Housekeeping - Refresh, export, import, clean up\n" +
                    "• General Settings - Username, display, developer mode\n" +
                    "• Help - This documentation\n" +
                    "• Developer Options - API Test and Traces (dev mode)\n\n" +
                    "Cards adapt based on setup status:\n" +
                    "• Reports/Chat/Models need at least 1 agent\n" +
                    "• Statistics/Costs need usage data\n" +
                    "• Housekeeping needs at least 1 API key"
            )

            // AI Reports
            HelpSection(
                title = "AI Reports",
                icon = "\uD83D\uDCDD",
                content = "Generate reports using multiple AI agents in parallel:\n\n" +
                    "Reports Hub:\n" +
                    "• New Report - Create a new AI report\n" +
                    "• Prompt History - Reuse previous prompts\n" +
                    "• AI History - View past reports\n\n" +
                    "Creating a report:\n" +
                    "1. Enter a title and your prompt\n" +
                    "2. Use @DATE@ to insert current date/time\n" +
                    "3. Tap 'Generate' to select agents\n" +
                    "4. Choose from 4 selection tabs:\n" +
                    "   Flocks | Agents | Swarms | Models\n" +
                    "5. Optionally attach parameter presets\n" +
                    "6. Watch real-time progress\n\n" +
                    "Each selection tab shows pricing per M tokens (input/output) next to each entry.\n\n" +
                    "Reports are stored persistently and can be exported as JSON or HTML."
            )

            // Report Selection
            HelpSection(
                title = "Report Selection Tabs",
                icon = "\uD83D\uDCCB",
                content = "Four ways to select what runs your report:\n\n" +
                    "Flocks tab:\n" +
                    "• Select predefined groups of agents\n" +
                    "• Agents in selected flocks are locked in\n" +
                    "• Search by flock name\n\n" +
                    "Agents tab:\n" +
                    "• Pick individual configured agents\n" +
                    "• Flock agents shown but locked (gray)\n" +
                    "• Shows agent name, provider, model, pricing\n\n" +
                    "Swarms tab:\n" +
                    "• Select groups of provider/model pairs\n" +
                    "• Lightweight - uses provider defaults\n" +
                    "• Search by swarm name\n\n" +
                    "Models tab:\n" +
                    "• Pick individual provider/model combos\n" +
                    "• Swarm models shown but locked (gray)\n" +
                    "• Shows provider, model, pricing\n\n" +
                    "All tabs show per-M-token pricing in red (known) or gray (estimated).\n" +
                    "Use the Parameters button to attach reusable parameter presets.\n" +
                    "Combine selections across tabs freely. Total worker count shown on Generate button."
            )

            // Report Results
            HelpSection(
                title = "Report Results",
                icon = "\uD83D\uDCC4",
                content = "View and share your results:\n\n" +
                    "Progress screen shows per agent:\n" +
                    "• Status icon (pending/running/success/error)\n" +
                    "• Agent name\n" +
                    "• Input/Output token counts\n" +
                    "• Cost in cents\n" +
                    "• Total row with aggregated sums\n" +
                    "• Stop button to cancel in-progress agents\n\n" +
                    "Screen stays on during generation.\n\n" +
                    "Actions when complete:\n" +
                    "• View - Formatted responses in-app\n" +
                    "• Share - Export as JSON or HTML\n" +
                    "• Browser - Interactive HTML report in Chrome\n" +
                    "• Trace - API traces (developer mode only)\n\n" +
                    "HTML reports include:\n" +
                    "• Markdown rendering with formatting\n" +
                    "• Collapsible 'Think' sections for reasoning\n" +
                    "• Citations and search results\n" +
                    "• Cost summary table with duration, tokens, and per-agent costs\n" +
                    "• Content from <user>...</user> tags in prompts"
            )

            // AI Chat
            HelpSection(
                title = "AI Chat",
                icon = "\uD83D\uDCAC",
                content = "Multi-turn conversations with any AI:\n\n" +
                    "Chat Hub options:\n" +
                    "• New chat based on AI Agent\n" +
                    "• New chat - configure on the fly\n" +
                    "• Continue with existing chat\n" +
                    "• Search in existing chats\n\n" +
                    "Starting a new chat:\n" +
                    "1. Select a provider or predefined agent\n" +
                    "2. Select a model (with pricing shown)\n" +
                    "3. Optionally configure parameters\n" +
                    "4. Start chatting!\n\n" +
                    "Chat features:\n" +
                    "• Streaming responses (real-time token-by-token)\n" +
                    "• System prompt for AI behavior\n" +
                    "• Temperature, max tokens, and more\n" +
                    "• Web search (xAI) and citations (Perplexity)\n" +
                    "• Cost tracking per session (in cents)\n" +
                    "• Auto-saved conversations\n" +
                    "• Continue any previous chat\n\n" +
                    "Chat search finds matches across all sessions with message preview and context."
            )

            // Chat History
            HelpSection(
                title = "Chat History",
                icon = "\uD83D\uDCDD",
                content = "Conversations are automatically saved:\n\n" +
                    "• Shows preview of first message\n" +
                    "• Displays provider, model, and timestamp\n" +
                    "• Tap to continue any conversation\n" +
                    "• All messages and parameters restored\n" +
                    "• Paginated list for easy browsing\n" +
                    "• Delete old conversations"
            )

            // AI Models / Model Search
            HelpSection(
                title = "AI Models",
                icon = "\uD83E\uDDE0",
                content = "Search and explore models across all providers:\n\n" +
                    "• Unified search across all configured providers\n" +
                    "• Filter by model name or provider name\n" +
                    "• Shows model count while searching\n\n" +
                    "When you tap a model:\n" +
                    "• Start AI Chat - Begin chatting with this model\n" +
                    "• Create AI Agent - Make an agent with this model\n" +
                    "• Model Info - Detailed model information"
            )

            // Model Info
            HelpSection(
                title = "Model Info",
                icon = "\u2139",
                content = "Detailed information about any model:\n\n" +
                    "OpenRouter data (if API key set):\n" +
                    "• Context length and max tokens\n" +
                    "• Pricing per token (input/output)\n" +
                    "• Architecture, modality, tokenizer\n" +
                    "• Moderation status\n\n" +
                    "Hugging Face data (if API key set):\n" +
                    "• Author and organization\n" +
                    "• Downloads, likes, tags (up to 20)\n" +
                    "• Pipeline tag, library, license\n\n" +
                    "AI-generated description:\n" +
                    "• Create an AI Prompt named 'model_info'\n" +
                    "• Assign an agent to generate descriptions\n" +
                    "• Use @MODEL@ and @PROVIDER@ variables\n" +
                    "• Auto-generates when viewing model info"
            )

            // AI Statistics
            HelpSection(
                title = "AI Statistics",
                icon = "\uD83D\uDCCA",
                content = "Track your AI usage:\n\n" +
                    "Summary:\n" +
                    "• Total API calls made\n" +
                    "• Input tokens (prompts sent)\n" +
                    "• Output tokens (responses received)\n" +
                    "• Total tokens processed\n\n" +
                    "Per-model breakdown:\n" +
                    "• Provider and model name\n" +
                    "• Number of calls\n" +
                    "• Token usage details\n\n" +
                    "Clear Statistics button to reset all data."
            )

            // AI Costs
            HelpSection(
                title = "AI Costs",
                icon = "\uD83D\uDCB0",
                content = "Track estimated costs:\n\n" +
                    "Pricing sources (priority order):\n" +
                    "1. API response (cost returned by provider)\n" +
                    "2. Manual overrides (Cost Configuration)\n" +
                    "3. OpenRouter API pricing (weekly cached)\n" +
                    "4. LiteLLM community pricing (built-in)\n" +
                    "5. Fallback estimates\n" +
                    "6. Default (zero)\n\n" +
                    "Features:\n" +
                    "• Total cost across all models\n" +
                    "• Input/output cost breakdown\n" +
                    "• Expandable provider groups\n" +
                    "• Per-model cost details with pricing source\n" +
                    "• Refresh button to update pricing\n\n" +
                    "Pricing source colors:\n" +
                    "• Blue = OpenRouter\n" +
                    "• Purple = LiteLLM\n" +
                    "• Orange = Fallback\n" +
                    "• Gray = Default/estimated\n\n" +
                    "Pricing shown inline on selection screens as input/output per M tokens " +
                    "(red = known, gray = estimated)."
            )

            // AI History
            HelpSection(
                title = "AI History",
                icon = "\uD83D\uDCC2",
                content = "Browse and manage generated reports:\n\n" +
                    "Search section (expandable):\n" +
                    "• Search by title\n" +
                    "• Search by prompt\n" +
                    "• Search by report content\n" +
                    "• All criteria combined (AND logic)\n\n" +
                    "Paginated list with title and date/time.\n" +
                    "Tap a report to see action buttons:\n\n" +
                    "• View - Formatted responses in-app\n" +
                    "• Share - Export as JSON or HTML\n" +
                    "• Browser - Interactive HTML report\n" +
                    "• Delete - Remove (with confirmation)\n\n" +
                    "Clear History removes all reports."
            )

            // AI Services
            HelpSection(
                title = "Supported AI Services",
                icon = "\uD83E\uDD16",
                content = "${com.ai.data.AiService.entries.size} AI services supported:\n\n" +
                    "Major providers:\n" +
                    "• OpenAI - GPT-4o, GPT-5.x, o3, o4\n" +
                    "  Endpoints: Chat Completions, Responses API\n" +
                    "• Anthropic - Claude 4, Claude 3.5\n" +
                    "• Google - Gemini 2.0 Flash and more\n" +
                    "• xAI - Grok with optional web search\n" +
                    "• Groq - Ultra-fast inference\n" +
                    "• DeepSeek - Reasoning with think sections\n" +
                    "  Endpoints: Standard, Beta (FIM)\n" +
                    "• Mistral - European AI models\n" +
                    "  Endpoints: Standard, Codestral (code)\n" +
                    "• Perplexity - Web search with citations\n" +
                    "• Together AI - Open-source models\n" +
                    "• OpenRouter - Multiple providers, unified API\n\n" +
                    "Additional providers:\n" +
                    "• SiliconFlow, Z.AI, Moonshot/Kimi\n" +
                    "• Cohere, AI21, DashScope, Fireworks\n" +
                    "• Cerebras, SambaNova, Baichuan, StepFun\n" +
                    "• MiniMax, NVIDIA, Replicate, Hugging Face\n" +
                    "• Lambda, Lepton, 01.AI, Doubao\n" +
                    "• Reka, Writer\n\n" +
                    "Each requires an API key from the provider's website."
            )

            // AI Agents
            HelpSection(
                title = "AI Agents",
                icon = "\u2699",
                content = "Configured AI assistants with custom settings:\n\n" +
                    "Each agent has:\n" +
                    "• Name - For identification\n" +
                    "• Provider - Which AI service\n" +
                    "• Model - Which model (Select opens full list with pricing)\n" +
                    "• API Key - Credentials (optional)\n" +
                    "• Endpoint - API endpoint (optional)\n" +
                    "• Parameter presets - Reusable settings (multi-select)\n\n" +
                    "Agent actions:\n" +
                    "• Edit, Test, Duplicate, Delete\n\n" +
                    "Inheritance (empty fields use provider defaults):\n" +
                    "• Empty model -> provider's default model\n" +
                    "• Empty API key -> provider's API key\n" +
                    "• No endpoint -> provider's default endpoint\n\n" +
                    "Agent list shows pricing (input/output per M tokens) and system prompt preview."
            )

            // AI Flocks
            HelpSection(
                title = "AI Flocks",
                icon = "\uD83E\uDD86",
                content = "Named groups of agents for quick selection:\n\n" +
                    "Creating a flock:\n" +
                    "1. Go to AI Setup > Flocks\n" +
                    "2. Tap 'Add Flock'\n" +
                    "3. Enter a name\n" +
                    "4. Select agents (with pricing shown)\n" +
                    "5. Optionally attach parameter presets\n" +
                    "6. Save\n\n" +
                    "Using flocks:\n" +
                    "• Select in the Flocks tab when generating reports\n" +
                    "• Combine with individual agents, swarms, or models\n" +
                    "• Selections are remembered for next time\n\n" +
                    "Flocks use each agent's full configuration (model, endpoint, parameters). " +
                    "Search and sort agents when selecting."
            )

            // AI Swarms
            HelpSection(
                title = "AI Swarms",
                icon = "\uD83D\uDC1D",
                content = "Named groups of provider/model pairs:\n\n" +
                    "Creating a swarm:\n" +
                    "1. Go to AI Setup > Swarms\n" +
                    "2. Tap 'Add Swarm'\n" +
                    "3. Enter a name\n" +
                    "4. Select provider/model combinations (with pricing)\n" +
                    "5. Optionally attach parameter presets\n" +
                    "6. Save\n\n" +
                    "Swarms vs Flocks:\n" +
                    "• Swarms are lightweight - use provider defaults\n" +
                    "• Flocks reference configured agents with custom settings\n" +
                    "• Both can have parameter presets attached\n" +
                    "• Use swarms for quick multi-model comparisons"
            )

            // AI Parameters
            HelpSection(
                title = "AI Parameters",
                icon = "\uD83D\uDD27",
                content = "Reusable parameter presets:\n\n" +
                    "Create named parameter sets that can be attached to agents, flocks, or swarms.\n\n" +
                    "Available parameters:\n" +
                    "• Temperature (0-2) - Creativity level\n" +
                    "• Max Tokens - Response length limit\n" +
                    "• System Prompt - AI behavior instructions\n" +
                    "• Top P / Top K - Sampling settings\n" +
                    "• Frequency/Presence Penalty - Reduce repetition\n" +
                    "• Seed - Reproducible outputs\n" +
                    "• Stop Sequences - Stop at keywords\n\n" +
                    "Provider-specific:\n" +
                    "• OpenAI: JSON response format\n" +
                    "• xAI: Web search toggle\n" +
                    "• Perplexity: Citations, search recency\n\n" +
                    "Multiple presets can be stacked - later values override earlier ones. " +
                    "Only non-null values are applied during merging."
            )

            // AI Prompts
            HelpSection(
                title = "AI Prompts",
                icon = "\uD83D\uDCDD",
                content = "Internal prompts for app features:\n\n" +
                    "Used by:\n" +
                    "• Model Info - Create prompt named 'model_info'\n" +
                    "• Auto-generates model descriptions\n\n" +
                    "Creating a prompt:\n" +
                    "1. Go to AI Setup > Prompts\n" +
                    "2. Tap 'Add Prompt'\n" +
                    "3. Enter name (e.g., 'model_info')\n" +
                    "4. Select an agent to execute it\n" +
                    "5. Write the prompt template\n\n" +
                    "Variables (auto-replaced):\n" +
                    "• @MODEL@ - Model name\n" +
                    "• @PROVIDER@ - Provider name\n" +
                    "• @AGENT@ - Agent name\n" +
                    "• @SWARM@ - Flock/swarm name\n" +
                    "• @NOW@ - Current date/time\n" +
                    "• @DATE@ - Current date"
            )

            // Provider Configuration
            HelpSection(
                title = "Provider Configuration",
                icon = "\uD83D\uDD27",
                content = "Each AI provider can be configured with:\n\n" +
                    "API Key:\n" +
                    "• Required for API access\n" +
                    "• Test button to verify connectivity\n" +
                    "• Password visibility toggle\n\n" +
                    "Model Selection:\n" +
                    "• Type model name directly\n" +
                    "• Or tap 'Select' for full-screen model list\n" +
                    "• Model list shows input and output pricing columns\n" +
                    "• Current model highlighted in the list\n\n" +
                    "Model Source:\n" +
                    "• API mode: Fetch models from provider\n" +
                    "• Manual mode: Maintain your own model list\n\n" +
                    "API Endpoints:\n" +
                    "• Multiple endpoints per provider\n" +
                    "• Add custom endpoints for self-hosted servers\n" +
                    "• Set default endpoint per provider\n" +
                    "• Examples:\n" +
                    "  OpenAI: Chat Completions vs Responses API\n" +
                    "  DeepSeek: Standard vs Beta (FIM/prefix)\n" +
                    "  Mistral: Standard vs Codestral (code)\n" +
                    "  SiliconFlow: OpenAI vs Anthropic compatible\n" +
                    "  Z.AI: General vs Coding endpoint\n\n" +
                    "Provider State:\n" +
                    "• OK (green) - API key tested successfully\n" +
                    "• Error (red) - API key failed testing\n" +
                    "• Inactive - Provider disabled by user\n" +
                    "• Not used (gray) - No API key configured\n\n" +
                    "Model List URL:\n" +
                    "• Custom URL for fetching available models\n" +
                    "• Useful for self-hosted or proxy servers"
            )

            // AI Housekeeping
            HelpSection(
                title = "AI Housekeeping",
                icon = "\uD83E\uDDF9",
                content = "Maintenance tools for managing your AI setup:\n\n" +
                    "Refresh:\n" +
                    "• All - Runs everything below in sequence\n" +
                    "• Model lists - Fetch latest models from all providers\n" +
                    "• OpenRouter data - Update pricing and specs\n" +
                    "• Provider state - Test all API key connections\n" +
                    "• Default agents - Create one agent per working provider\n\n" +
                    "'Default agents' tests each provider, creates/updates an agent per working provider, " +
                    "and groups them in a 'default agents' flock.\n\n" +
                    "Export:\n" +
                    "• AI configuration - Full JSON backup (v17)\n" +
                    "• API keys - Keys only as JSON file\n" +
                    "• Model costs - Pricing data as CSV\n\n" +
                    "Import:\n" +
                    "• AI configuration - Restore from JSON (supports v11-v17)\n" +
                    "• API keys - Import keys from JSON file\n" +
                    "• Model costs - Import pricing from CSV\n\n" +
                    "Clean up:\n" +
                    "• Clean up - Full reset: deletes all data, then refreshes and generates default agents\n" +
                    "• Chats - Delete chat sessions older than N days\n" +
                    "• Reports - Delete reports older than N days\n" +
                    "• Statistics - Enter 0 to clear all (no timestamps)\n" +
                    "• API Trace - Delete traces older than N days\n" +
                    "• Prompts - Delete prompt history older than N days\n\n" +
                    "Each cleanup asks 'days to keep' with a default of 30."
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
                    "• Search by title or prompt text\n" +
                    "• Paginated list, most recent first\n" +
                    "• Clean up in Housekeeping"
            )

            // HTML Reports
            HelpSection(
                title = "HTML Reports",
                icon = "\uD83C\uDF10",
                content = "Interactive reports for browser viewing:\n\n" +
                    "Content:\n" +
                    "• Agent buttons to toggle between responses\n" +
                    "• Markdown rendered as formatted HTML\n" +
                    "• Collapsible think sections\n" +
                    "• Citations and search results\n\n" +
                    "Cost summary table:\n" +
                    "• Provider and model per agent\n" +
                    "• API request duration in seconds\n" +
                    "• Input/output token counts\n" +
                    "• Input/output costs in cents\n" +
                    "• Total cost per agent\n" +
                    "• Totals row at bottom\n\n" +
                    "User content:\n" +
                    "• Wrap text in <user>...</user> tags in your prompt\n" +
                    "• This content appears in the HTML report header\n" +
                    "• It is not sent to the AI agents"
            )

            // Export/Import Configuration
            HelpSection(
                title = "Export/Import Configuration",
                icon = "\uD83D\uDCBE",
                content = "Backup and restore your setup:\n\n" +
                    "Primary location: AI Housekeeping screen.\n" +
                    "Also available: AI Setup and AI Providers screens.\n\n" +
                    "Export includes:\n" +
                    "• All providers with API keys\n" +
                    "• All agents with parameters\n" +
                    "• All flocks and swarms\n" +
                    "• AI parameter presets\n" +
                    "• AI prompts\n" +
                    "• Manual pricing overrides\n" +
                    "• Provider endpoints\n" +
                    "• External API keys (HuggingFace, OpenRouter)\n\n" +
                    "Current format: JSON v17.\n" +
                    "Import supports versions 11 through 17.\n\n" +
                    "Separate exports available for API keys only (JSON) and model costs (CSV)."
            )

            // Settings Overview
            HelpSection(
                title = "Settings",
                icon = "\u2699",
                content = "Customize the app:\n\n" +
                    "General Settings:\n" +
                    "• Username for chat display\n" +
                    "• Full screen mode toggle\n" +
                    "• Developer mode toggle\n" +
                    "• API call tracing toggle (dev mode)\n" +
                    "• Hugging Face API Key - For model info\n\n" +
                    "AI Setup:\n" +
                    "• Providers - API keys, models, endpoints\n" +
                    "• Agents - Named AI configurations\n" +
                    "• Flocks - Groups of agents\n" +
                    "• Swarms - Groups of provider/model pairs\n" +
                    "• Parameters - Reusable parameter presets\n" +
                    "• Prompts - Internal app prompts\n" +
                    "• Costs - Manual price overrides\n\n" +
                    "AI Housekeeping:\n" +
                    "• Refresh models, pricing, provider states\n" +
                    "• Generate default agents\n" +
                    "• Export/Import configuration\n" +
                    "• Clean up old data"
            )

            // Developer Mode
            HelpSection(
                title = "Developer Mode",
                icon = "\uD83D\uDC1B",
                content = "Enable in General Settings:\n\n" +
                    "Features unlocked:\n" +
                    "• API call tracing option\n" +
                    "• Token usage in reports\n" +
                    "• HTTP headers in reports\n" +
                    "• Developer Options on home screen\n\n" +
                    "Developer Options:\n" +
                    "• API Test - Custom API request builder\n" +
                    "  Select provider, model, parameters\n" +
                    "  Send test messages, view raw responses\n" +
                    "• API Traces - Logged requests/responses\n" +
                    "  Color-coded HTTP status codes\n" +
                    "  Collapsible JSON tree viewer\n" +
                    "  API keys masked for security\n" +
                    "  Paginated trace list"
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
                    "• All reports and chats stored locally\n" +
                    "• No analytics or telemetry\n" +
                    "• API keys in private app storage\n" +
                    "• Keys masked in API traces\n" +
                    "• Direct API calls only to providers\n" +
                    "• HTTPS encryption in transit\n" +
                    "• No third-party data sharing"
            )

            // Troubleshooting
            HelpSection(
                title = "Troubleshooting",
                icon = "\uD83D\uDEE0",
                content = "Common issues:\n\n" +
                    "\"No API key configured\"\n" +
                    "-> Add API key in AI Setup > Providers\n\n" +
                    "\"Network error\"\n" +
                    "-> Check internet, retries once automatically\n\n" +
                    "\"Model not found\"\n" +
                    "-> Refresh model lists in Housekeeping\n" +
                    "-> Check model availability for your API tier\n\n" +
                    "\"No pricing data\"\n" +
                    "-> Add OpenRouter API key in General Settings\n" +
                    "-> Or set manual prices in AI Setup > Costs\n\n" +
                    "\"Wrong endpoint\"\n" +
                    "-> Check provider settings for correct endpoint\n" +
                    "-> OpenAI: gpt-4o uses Chat, gpt-5.x uses Responses\n\n" +
                    "Slow responses?\n" +
                    "-> Complex prompts can take minutes (7 min timeout)\n" +
                    "-> Check the duration column in HTML cost table\n\n" +
                    "Pricing shows gray?\n" +
                    "-> Gray = estimated fallback pricing\n" +
                    "-> Red = known pricing from OpenRouter/LiteLLM/manual\n\n" +
                    "Agent using wrong settings?\n" +
                    "-> Empty fields inherit from provider\n" +
                    "-> Check provider has correct defaults\n" +
                    "-> Check parameter preset order (later overrides earlier)\n\n" +
                    "Provider shows as inactive?\n" +
                    "-> Provider was disabled. Re-enable in provider settings.\n\n" +
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
