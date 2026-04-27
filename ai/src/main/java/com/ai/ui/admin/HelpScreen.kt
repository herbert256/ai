package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.*

@Composable
fun HelpScreen(onBack: () -> Unit, onNavigateHome: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Help", onBackClick = onBack, onAiClick = onNavigateHome)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HelpSection("Welcome", "This app generates AI reports and chats using ${AppService.entries.size} AI services. Configure providers with API keys, create agents, and generate comparative reports.")
            HelpSection("Getting Started", "1. Go to AI Setup > Providers\n2. Enter an API key for at least one provider\n3. Tap Refresh All to test connections and fetch models\n4. Create a new report from the Reports hub")
            HelpSection("AI Hub", "The main screen provides quick access to Reports, Chat, Models, Statistics, Setup, and Housekeeping. Each card navigates to the corresponding feature.")
            HelpSection("AI Reports", "Reports send the same prompt to multiple AI agents in parallel. Results are stored and can be viewed, shared as HTML/JSON, or opened in a browser.")
            HelpSection("Report Selection", "Select agents, flocks, or swarms to include in a report. Each selected item becomes a parallel task. Optionally attach parameter presets and system prompts.")
            HelpSection("Report Results", "Results show as cards with status indicators. Green = success, red = error, gray = stopped. Tap a result to expand it. Cost and token usage are shown when available.")
            HelpSection("AI Chat", "Chat with any provider/model combination. Features include streaming responses, system prompts, parameter presets, and automatic session saving.")
            HelpSection("Chat History", "Chat sessions are automatically saved. Browse, search, and continue previous conversations from the Chat History screen.")
            HelpSection("Dual Chat", "Send the same prompt to two different models side by side. Configure the number of rounds and prompt templates using %subject% and %answer% variables.")
            HelpSection("AI Models", "Search across all models from all active providers. View model info from OpenRouter and HuggingFace. Start a chat or create an agent directly from search results.")
            HelpSection("Model Info", "Shows technical specifications, pricing, and descriptions from OpenRouter and HuggingFace APIs. An AI-generated introduction can be loaded if a model_info prompt is configured.")
            HelpSection("AI Statistics", "View token usage and costs per provider and model. Costs are calculated using a five-tier pricing lookup: API > LiteLLM > Override > OpenRouter > Default.")
            HelpSection("AI Costs", "Configure manual price overrides per provider/model. Prices are in USD per million tokens. Overrides take priority over all automatic pricing sources.")
            HelpSection("AI History", "Browse all generated reports. Search by title, prompt, or response content. View, share, open in browser, or delete individual reports.")
            HelpSection("Supported Services", "The app supports ${AppService.entries.size} AI providers including OpenAI, Anthropic, Google, Mistral, DeepSeek, Groq, Perplexity, Cohere, xAI, and many more. 28 of 31 use the OpenAI-compatible API format.")
            HelpSection("Agents", "Agents are named configurations combining a provider, model, API key, endpoint, system prompt, and parameter preset. Agents inherit settings from their provider when fields are empty.")
            HelpSection("Flocks", "Flocks are groups of agents. Select a flock for a report to include all its agents. Flocks can have their own system prompt and parameter presets.")
            HelpSection("Swarms", "Swarms are groups of provider/model pairs. Unlike flocks (which use agents), swarms directly reference provider+model combinations. Useful for testing many models quickly.")
            HelpSection("Parameters", "Parameter presets define temperature, max tokens, top-p, top-k, penalties, system prompt, and web search settings. Multiple presets can be merged (later non-null values win, booleans are sticky true).")
            HelpSection("Prompts", "Internal prompts are templates assigned to specific agents. Variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@. Used for specialized agent behaviors like model info lookups.")
            HelpSection("System Prompts", "Reusable system prompts that can be attached to agents, flocks, or swarms. Sent as the system message in API calls.")
            HelpSection("Provider Configuration", "Each provider has: API key, model selection (API or manual list), admin URL, model list URL, and optional parameter presets. State indicators: key = configured, error = failed test, sleep = inactive.")
            HelpSection("Housekeeping", "Refresh provider states, model lists, and pricing data. Export/import configurations, API keys, and cost data. Generate default agents for all working providers.")
            HelpSection("Think Sections", "Some models include <think> sections in responses showing their reasoning process. These are rendered in collapsible blocks with distinct formatting.")
            HelpSection("Prompt History", "Report prompts are automatically saved. Browse and reuse previous prompts from the Prompt History screen. Maximum ${com.ai.ui.settings.SettingsPreferences.MAX_PROMPT_HISTORY} entries stored.")
            HelpSection("HTML Reports", "Reports can be exported as HTML files with full formatting, token usage, costs, and response content. Open in browser for rich rendering or share externally.")
            HelpSection("Export/Import", "Export all settings as JSON (version 21). Import accepts versions 11-21 with automatic migration. API keys can be exported/imported separately.")
            HelpSection("Settings", "Configure user name, default email, full screen mode, and popup model selection. Developer mode enables API testing and trace viewing tools.")
            HelpSection("Developer Mode", "Access API Test (send custom requests), Edit Request (modify JSON before sending), and API Traces (view request/response details with JSON tree viewer).")
            HelpSection("External Integration", "Other apps can trigger reports via Intent action com.ai.ACTION_NEW_REPORT. Supports title, prompt, system prompt, agent/flock/swarm selection, and auto-run instructions.")
            HelpSection("Privacy", "All data is stored locally on the device. API keys are sent only to their respective providers. No telemetry or analytics. Reports and chat history stay on device.")
            HelpSection("Troubleshooting", "If a provider shows an error state, check the API key and endpoint URL. Use the API Test tool to debug requests. Clear model list caches if models appear outdated. Check API Traces for detailed request/response data.")
        }
    }
}

@Composable
private fun HelpSection(title: String, content: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            Spacer(modifier = Modifier.height(6.dp))
            Text(content, fontSize = 13.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
        }
    }
}
