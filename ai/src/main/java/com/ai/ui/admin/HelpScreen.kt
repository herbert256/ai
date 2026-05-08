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

private data class HelpCard(val title: String, val body: String)
private data class HelpContent(val title: String, val cards: List<HelpCard>)

@Composable
fun HelpScreen(topicId: String? = null, onBack: () -> Unit, onNavigateHome: () -> Unit) {
    BackHandler { onBack() }
    val topic = topicId?.takeIf { it.isNotBlank() }?.let { HELP_TOPICS[it] }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = topic?.title ?: "Help", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (topic != null) {
                topic.cards.forEach { HelpSection(it.title, it.body) }
            } else {
                CompactOverview()
            }
        }
    }
}

@Composable
private fun CompactOverview() {
    HelpSection(
        "Welcome",
        "This app runs AI reports, chats, and dual chats against ${AppService.entries.size} cloud providers plus on-device models. Configure providers with API keys, then build reports, chats, or knowledge bases from the Hub."
    )
    HelpSection(
        "Per-screen help",
        "Every screen has its own help page. Tap ❓ in the top bar of the screen you're on for guidance specific to that screen. This page is the general overview only."
    )
    HelpSection(
        "Getting started",
        "1. Settings → AI Setup → Providers — paste an API key.\n" +
            "2. Refresh All — verify keys + fetch model lists.\n" +
            "3. From the Hub, pick Reports / Chat / Knowledge / Models / Setup / Housekeeping."
    )
    HelpSection(
        "Privacy",
        "All data stays on this device. API keys are sent only to their provider. No telemetry."
    )
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

private val HELP_TOPICS: Map<String, HelpContent> = mapOf(
    // ===== Hub =====
    "hub" to HelpContent("AI Hub", listOf(
        HelpCard("Overview", "The Hub is the home screen. Every other area of the app is one tap away from here, and 🏠 in any title bar brings you back."),
        HelpCard("Cards", "AI Reports, AI Chat, AI Knowledge, AI Models, AI Usage, AI Setup, and AI Housekeeping each open the matching feature. Settings + Help live at the bottom."),
        HelpCard("AI API Traces", "Shown when at least one captured trace exists and tracing is enabled. Tap to open the most recent trace; long lists go to the trace browser."),
        HelpCard("First time?", "Open Settings → AI Setup → Providers and paste an API key, then Refresh All to fetch model lists."),
        HelpCard("Title bar", "❓ opens this help. 🏠 returns to this Hub from anywhere in the app.")
    )),

    // ===== Reports =====
    "reports_hub" to HelpContent("AI Reports — hub", listOf(
        HelpCard("Overview", "Reports send the same prompt to multiple AI agents in parallel. Results are stored and can be viewed, shared as HTML/JSON, or opened in a browser."),
        HelpCard("New report", "Tap New Report to enter a prompt and pick agents. Tap a recent prompt to reuse it."),
        HelpCard("History", "All saved reports — searchable by title, prompt, or response."),
        HelpCard("Search", "Full-text scan across saved reports. Local Search hits stored content; Semantic Search uses embeddings."),
        HelpCard("Manage", "Bulk delete / export / migrate reports.")
    )),
    "report_new" to HelpContent("Report — selection phase", listOf(
        HelpCard("Overview", "Pick which models will run the prompt. Each selected agent / flock / swarm becomes one parallel API call."),
        HelpCard("Add models", "+ Flock, + Agent, + Swarm pull from your saved configs. + Model picks any provider+model directly. + All Models adds every active model from a provider."),
        HelpCard("From report", "Copy the model list from a previous report — handy for re-running the same setup with a new prompt."),
        HelpCard("Parameters", "Optional preset bundles (temperature, max tokens, system prompt, …). Multiple presets merge — later non-null wins."),
        HelpCard("Knowledge", "Attach knowledge bases to inject context chunks into every agent's prompt."),
        HelpCard("Generate", "Run as a single report or as separate reports per agent (one report each).")
    )),
    "report_result_generation" to HelpContent("Report — results", listOf(
        HelpCard("Overview", "Each row is one agent — model + status + cost + tokens. Live updates while running."),
        HelpCard("Action row", "View / Edit / Regenerate / Export / Copy / Pin / Translate / Meta / Cross. Tap one for that flow."),
        HelpCard("Per-row 🐞", "Opens the captured API trace for that agent's call."),
        HelpCard("Title bar", "ℹ️ → model picker for any agent's Model Info; 🗑 → delete this report; 🐞 → all traces for this report; 🔄 grayed (multi-call)."),
        HelpCard("Pitfalls", "Stuck ⏳ rows after force-quit auto-recover on next open; if not, tap Regenerate."),
        HelpCard("Drill-in", "Tap a row to open the single-agent result with full content + per-result actions.")
    )),
    "report_single_result" to HelpContent("Single agent result", listOf(
        HelpCard("Overview", "Detail view for one agent's response — conclusion / motivation extraction, citations, search results, related questions."),
        HelpCard("Title bar", "🔄 re-fires this single API call (with confirmation); ℹ️ → Model Info; 🗑 → drop this row from the report; 🐞 → open the captured trace."),
        HelpCard("Translation info", "Shown when this report is a translated copy. Side-by-side original vs. translation viewer."),
        HelpCard("Continue in chat", "Open a chat seeded with the prompt + this response. Pick: same provider/model, switch agent, or configure on the fly."),
        HelpCard("Pitfalls", "Removing the last successful agent from a report leaves it empty — re-run from the parent report's Regenerate.")
    )),
    "report_continue_in_chat" to HelpContent("Continue in chat", listOf(
        HelpCard("Overview", "Hands the agent's response off to a fresh chat session as the starting context."),
        HelpCard("Current history and model", "Reuse the same provider/model and keep this report's prompt + response as the seed turn."),
        HelpCard("Pick another agent", "Stash the response and pick a different agent — its system prompt and parameters take over."),
        HelpCard("Configure on the fly", "Pick provider → model → parameters in one flow before opening the chat."),
        HelpCard("When it's disabled", "Empty / errored responses can't seed a chat — there's nothing useful to send.")
    )),
    "secondary_list" to HelpContent("Secondary results — list", listOf(
        HelpCard("Overview", "Lists every Rerank / Meta / Moderation / Translate run made on this report. Tap a row to open its detail."),
        HelpCard("Filtering", "Same screen serves all four kinds — the title and rows reflect the active kind. Filtered Translation list opens its own per-run view."),
        HelpCard("Title bar", "🐞 → trace list filtered to this report's secondaries of this kind; 🗑 grayed (delete is per-row)."),
        HelpCard("Per-row delete", "Each row has its own confirm dialog — wipes that one secondary, leaves siblings alone."),
        HelpCard("Related", "Run a new secondary from the Report Result screen's Action Row → Meta / Cross / Translate.")
    )),
    "secondary_detail" to HelpContent("Secondary result — detail", listOf(
        HelpCard("Overview", "Full content of one Rerank / Meta / Moderation row. Renders structured tables for Rerank + Moderation, free-text for Meta."),
        HelpCard("Title bar", "🔄 re-fires this exact secondary call; ℹ️ → Model Info for the model that produced it; 🗑 deletes this secondary; 🐞 opens its trace."),
        HelpCard("Translation info", "Shown when this is a translated copy of an existing secondary."),
        HelpCard("Pitfalls", "Re-running replaces the existing content — there's no version history per secondary.")
    )),
    "secondary_cross" to HelpContent("Cross", listOf(
        HelpCard("Overview", "Cross runs every selected answerer model against every source row's content (N answerers × S sources)."),
        HelpCard("Levels", "L1 lists every (answerer, source) pair. Tap a row → L2 (one answerer, all sources). Tap a source → L3 (one specific cell)."),
        HelpCard("Per-row 🐞", "Each cell links to its own captured trace."),
        HelpCard("Restart", "Re-runs cells flagged as errored or stuck. Leaves successful cells alone — quick recovery without redoing the whole grid."),
        HelpCard("Pitfalls", "Cross multiplies API calls quickly. Watch the cost summary before starting on large grids.")
    )),
    "secondary_scope" to HelpContent("Secondary scope", listOf(
        HelpCard("Overview", "Picks which rows from the report feed into the next Meta / Rerank / Moderation / Translate run."),
        HelpCard("Scope", "All reports, only this report, or per-agent. Some kinds (chat-type Meta) prompt the user; structured kinds skip this screen."),
        HelpCard("Language scope", "For Translate: all languages stored on this report, only one, or a fresh pick.")
    )),
    "report_meta" to HelpContent("Meta", listOf(
        HelpCard("Overview", "Run a Meta prompt across selected agent responses — Summarize, Compare, Critique, Synthesize, etc."),
        HelpCard("Pick a prompt", "Internal Prompts (category = meta) drive the available options. Add or edit them in Settings → Prompts → Internal."),
        HelpCard("Pick a model", "Any active provider/model. Cost and tokens vary widely — check Statistics first if running on long reports."),
        HelpCard("Per-row trash", "Each Meta result row has its own delete; the title-bar 🗑 is grayed because this is a list view.")
    )),
    "report_edit_prompt" to HelpContent("Edit prompt", listOf(
        HelpCard("Overview", "Modify the report's prompt. Saving creates a new version — the next Regenerate uses it."),
        HelpCard("Title", "Free-text title shown in History. Defaults to the first line of the prompt."),
        HelpCard("Pitfalls", "Editing alone doesn't re-run agents — tap Regenerate from the Action Row to pick up the new prompt.")
    )),
    "report_parameters" to HelpContent("Edit parameters", listOf(
        HelpCard("Overview", "Re-pick the parameter presets bundled with the report. Useful when you want different temperature / system prompt for the next Regenerate."),
        HelpCard("Multiple presets", "Listed in the order they apply. Later non-null values override earlier; booleans are sticky-true."),
        HelpCard("Pitfalls", "Pre-existing rows aren't re-rendered — only the next Regenerate applies the new presets.")
    )),
    "report_export" to HelpContent("Export report", listOf(
        HelpCard("Overview", "Export the current report as HTML, JSON, or Markdown — single agent, all agents, or every secondary too."),
        HelpCard("Format", "HTML keeps full formatting, links, citations. JSON is the raw report data. Markdown plain-text-ish."),
        HelpCard("Detail", "Per-agent only / include token usage / include traces. Each level adds size."),
        HelpCard("Action", "Open in browser, share via system chooser, or save to disk.")
    )),
    "report_manage" to HelpContent("Manage reports", listOf(
        HelpCard("Overview", "Bulk operations across saved reports — delete, export, migrate."),
        HelpCard("Trim by age", "Drops everything older than N days — handy after a long benchmark run."),
        HelpCard("Per-row open", "Tap a row to open the report itself.")
    )),
    "report_pick_flock" to HelpContent("Pick a flock", listOf(
        HelpCard("Overview", "Every saved flock with its agent count. Tap to add all the flock's agents to the report."),
        HelpCard("Search", "Filter by name."),
        HelpCard("Empty?", "Define flocks in Settings → Workers → Flocks first.")
    )),
    "report_pick_agent" to HelpContent("Pick an agent", listOf(
        HelpCard("Overview", "Every saved agent with its provider/model. Tap to add to the report."),
        HelpCard("Search", "Filter by name."),
        HelpCard("Empty?", "Define agents in Settings → Workers → Agents — or use + Model on the report screen for an ad-hoc pick.")
    )),
    "report_pick_swarm" to HelpContent("Pick a swarm", listOf(
        HelpCard("Overview", "Every saved swarm with its model count. Tap to add all the swarm's provider/model pairs."),
        HelpCard("Swarm vs flock", "Swarms reference provider+model directly; flocks reference agents (which carry their own system prompt + parameters)."),
        HelpCard("Empty?", "Define swarms in Settings → Workers → Swarms.")
    )),

    // ===== Translation =====
    "translation_run" to HelpContent("Translation run", listOf(
        HelpCard("Overview", "Lists every translation call inside one run (each row = one source × language pair)."),
        HelpCard("Title bar", "🐞 → trace list filtered to this run's translation calls; ℹ️ → Model Info for the run's translator; 🗑 deletes the entire run."),
        HelpCard("Restart failed", "Re-runs only the rows flagged with ❌. Skips successful and pending rows."),
        HelpCard("Start missing", "Adds any rows that should exist for the run's scope but don't. Useful after a partial cancel."),
        HelpCard("Per-row tap", "Opens the call detail with the source + translation side by side.")
    )),
    "translation_call" to HelpContent("Translation call", listOf(
        HelpCard("Overview", "Detail for one translation call — original on top, translation underneath."),
        HelpCard("Source", "Provider/model that produced the original (when applicable). Tap the model name to open Model Info."),
        HelpCard("Translation", "The translated content + the translator's model."),
        HelpCard("Title bar", "🐞 opens the captured trace for this call; ℹ️ → Model Info for the translator. 🔄 + 🗑 not wired here.")
    )),
    "translation_compare" to HelpContent("Translation compare", listOf(
        HelpCard("Overview", "Generic side-by-side viewer for any 'original ↔ translation' pair."),
        HelpCard("Layout", "Half-screen cap on the original pane; translation gets the rest. Both panes scroll independently.")
    )),
    "translation_language" to HelpContent("Pick languages", listOf(
        HelpCard("Overview", "Pick one or more target languages for the next translation run."),
        HelpCard("Multi-select", "Each ticked language becomes a separate translation call per source."),
        HelpCard("Pitfalls", "Cost scales linearly with language count.")
    )),

    // ===== Chat =====
    "chat_hub" to HelpContent("AI Chat — hub", listOf(
        HelpCard("Overview", "Open a chat with any active provider/model, or pick a preconfigured agent."),
        HelpCard("Choose by", "Provider → Model, Agent, or jump into Dual Chat for two models side by side."),
        HelpCard("Start with photo", "Camera-attached image becomes the first user turn. Vision-capable models recommended."),
        HelpCard("History", "Past chat sessions are saved per (provider, model) pair. Manage / clear from this hub."),
        HelpCard("Search / Continue", "Find an old session by content; reopen it with full message history.")
    )),
    "chat_session" to HelpContent("Chat session", listOf(
        HelpCard("Overview", "Live chat. Streamed responses, system prompt, parameter preset, attachments, knowledge injection — all per-session."),
        HelpCard("Send", "Text + optional image. Vision-capable models accept images directly."),
        HelpCard("Web search / reasoning", "Per-turn toggles when the model supports them. Auto-detected via LiteLLM / models.dev."),
        HelpCard("Attach knowledge", "Tick KBs to inject relevant chunks into every turn. Per-turn cost depends on chunk size."),
        HelpCard("Per-message 🐞", "Each assistant response carries the trace icon for its API call."),
        HelpCard("Moderation", "Optional pre-send moderation gate. Flagged inputs require explicit Proceed."),
        HelpCard("Title bar", "ℹ️ → Model Info for the chat's model. Trace + Reload + Delete are per-message, not per-screen.")
    )),
    "chat_parameters" to HelpContent("Chat parameters", listOf(
        HelpCard("Overview", "Pre-flight tweaks for a new chat session — temperature, max tokens, system prompt, web search, reasoning effort."),
        HelpCard("System prompt", "Prepended as the system message. Use it to set persona, format, or constraints."),
        HelpCard("Web search", "Provider-side web tool — only available on supported models."),
        HelpCard("Reasoning effort", "Low / medium / high for o-series / GPT-5 / thinking models. Off by default."),
        HelpCard("Pitfalls", "Edits don't apply to existing sessions — start a new chat for the new settings to take effect.")
    )),
    "chat_history" to HelpContent("Chat history", listOf(
        HelpCard("Overview", "Every saved chat session — most recent first. Tap a row to reopen."),
        HelpCard("Search", "Filter by title or content."),
        HelpCard("Continue picker", "Resume an existing session, branch from one message, or fork into a fresh session."),
        HelpCard("Manage", "Bulk delete chats older than N days from the Manage screen.")
    )),
    "chat_continue" to HelpContent("Continue chat", listOf(
        HelpCard("Overview", "Reopens an existing chat session with full message history. New turns continue the conversation."),
        HelpCard("Pitfalls", "Switching the underlying model is not supported mid-session — start a new chat or use Continue in chat from a Report instead.")
    )),
    "chat_manage" to HelpContent("Manage chats", listOf(
        HelpCard("Overview", "Bulk operations across saved chat sessions."),
        HelpCard("Trim by age", "Drops sessions older than N days."),
        HelpCard("Pitfalls", "Deleting a session removes its full message history — there's no soft-delete.")
    )),
    "dual_chat_setup" to HelpContent("Dual chat — setup", listOf(
        HelpCard("Overview", "Configures two models that will trade messages on the same prompt for N rounds."),
        HelpCard("Models", "Pick provider+model for each side. Different prompts and parameters can be set per side."),
        HelpCard("Templates", "%subject% and %answer% variables fill in the prompt automatically per round."),
        HelpCard("Rounds", "Caps the conversation length. Cost is roughly linear in rounds × tokens.")
    )),
    "dual_chat_session" to HelpContent("Dual chat — session", listOf(
        HelpCard("Overview", "Live side-by-side chat. Each round runs both models in parallel; messages stream to their own column."),
        HelpCard("Cost row", "Per-side and total cost in cents at the top — refreshed every turn."),
        HelpCard("Per-message 🐞", "Each turn carries its trace icon."),
        HelpCard("Title bar", "ℹ️ opens a 2-row picker to jump to either model's Model Info."),
        HelpCard("Chat N more", "Extends the run beyond the original round count without restarting.")
    )),

    // ===== History / search =====
    "history" to HelpContent("History", listOf(
        HelpCard("Overview", "All generated reports, newest first. Tap a row to reopen the full report."),
        HelpCard("Search", "Filter by title, prompt, or response. Each field narrows independently."),
        HelpCard("Per-row delete", "Each report row has its own delete confirm — wipes that one report from disk."),
        HelpCard("Title bar", "🗑 → bulk-clear all history (with confirm); 🐞 → all captured traces."),
        HelpCard("Pitfalls", "Deleting a report also removes its secondaries (Translate / Meta / Rerank / Moderate) and trace files for that report.")
    )),
    "prompt_history" to HelpContent("Prompt history", listOf(
        HelpCard("Overview", "The last 100 report prompts. Tap a row to reuse the prompt + title in a new report."),
        HelpCard("Pitfalls", "Prompts are deduplicated by (title, prompt) — re-running the exact same pair just bumps the timestamp.")
    )),

    // ===== Knowledge =====
    "knowledge_list" to HelpContent("Knowledge bases", listOf(
        HelpCard("Overview", "Saved RAG knowledge bases. Each KB has its own embedder, chunk store, and source list."),
        HelpCard("New KB", "+ New starts the embedder picker — pick a provider+model with embedding capability."),
        HelpCard("Per-row tap", "Opens the KB detail (sources, chunks, re-index, delete)."),
        HelpCard("Pitfalls", "Switching embedders is not supported per-KB — re-index by deleting + recreating with the new embedder.")
    )),
    "knowledge_detail" to HelpContent("KB detail", listOf(
        HelpCard("Overview", "One knowledge base — its embedder, sources, and chunk count."),
        HelpCard("+ File / + Web page", "Add a source. Files: text / PDF / Word / spreadsheets / images. Web pages: any URL — fetched + parsed."),
        HelpCard("Per-source actions", "Re-index pulls the source again and re-embeds. Delete drops just that source's chunks."),
        HelpCard("Title bar", "🗑 → delete the entire KB (with confirm). Per-source delete stays on each row."),
        HelpCard("Pitfalls", "Re-indexing a large source is slow on-device — keep the screen open until the status reads 'Indexed'.")
    )),

    // ===== Models =====
    "models_search" to HelpContent("Model search", listOf(
        HelpCard("Overview", "Searches every fetched model from every active provider. Type-as-you-go filter."),
        HelpCard("Capability badges", "👁 vision, 🌐 web search, 🧠 reasoning. Layered detection across LiteLLM / models.dev / OpenRouter / heuristics."),
        HelpCard("Pricing", "Per-million-tokens estimates. Falls back to a default tier when no source has data."),
        HelpCard("Per-row tap", "Opens Model Info for that (provider, model)."),
        HelpCard("Pitfalls", "If a model's capabilities look wrong, refresh its provider in Settings → AI Setup → Refresh.")
    )),
    "model_info" to HelpContent("Model info", listOf(
        HelpCard("Overview", "Layered model details — OpenRouter + HuggingFace + LiteLLM + models.dev + Helicone + llm-prices + Artificial Analysis + manual overrides."),
        HelpCard("Provider name", "Tap the blue provider line to jump to that provider's edit screen."),
        HelpCard("Last usage", "Top 10 places this exact (provider, model) pair was used recently — chats, reports, secondaries — plus the cumulative AI Usage counter when calls were made outside persisted sessions. Hidden when the model has zero usage."),
        HelpCard("Actions", "Start AI Chat, Create AI Agent for this model, jump into the catalog raw JSON viewers."),
        HelpCard("Title bar", "🐞 → trace list filtered to this model. ℹ️ + 🗑 not wired here."),
        HelpCard("AI introduction", "When an internal prompt of category model_info exists, the screen runs it once and caches the result.")
    )),
    "model_picker" to HelpContent("Model picker", listOf(
        HelpCard("Overview", "Picks one model from the list. Used by report selection, manual overrides, etc."),
        HelpCard("Filter", "Type to narrow by provider or model name.")
    )),
    "model_raw" to HelpContent("Raw model JSON", listOf(
        HelpCard("Overview", "Pretty-printed JSON for one of the catalog sources (HuggingFace / OpenRouter / LiteLLM / models.dev / Helicone / llm-prices / Artificial Analysis)."),
        HelpCard("Use it for", "Sanity-checking capability flags, context-window numbers, multi-modal pricing, etc."),
        HelpCard("Pitfalls", "Catalogs disagree about the same model — Model Info layers them in priority order; the raw view is per-source.")
    )),

    // ===== Settings =====
    "settings_main" to HelpContent("Settings", listOf(
        HelpCard("Overview", "User name, default email, API tracing toggle, model-name layout, < Back visibility."),
        HelpCard("AI tracing", "Master switch for capturing API requests + responses to disk. Off → no new traces, no 🐞 icons."),
        HelpCard("Model name layout", "Picks the rendering style for combined provider+model labels everywhere in the app."),
        HelpCard("Show < Back", "Off hides the visible Back button — gestures / system back still work."),
        HelpCard("Related", "AI Setup, External Services, and Housekeeping live one tap away — tap their sub-screens for those settings.")
    )),
    "settings_setup" to HelpContent("AI Setup", listOf(
        HelpCard("Overview", "Top-level setup hub — Providers, Models, Workers, Prompts, Local models, External services, Refresh, Import/Export."),
        HelpCard("Providers", "API keys + endpoint URLs + state per provider. Activate / deactivate from here."),
        HelpCard("Workers", "Reusable Agents, Flocks, Swarms — the building blocks for Reports."),
        HelpCard("Prompts", "System prompts + Internal prompts (Meta, Cross, model-info etc.)."),
        HelpCard("Local", "On-device LLM and embedder (LiteRT)."),
        HelpCard("Refresh", "Bulk re-test all providers + re-fetch their model lists.")
    )),
    "agents" to HelpContent("Agents", listOf(
        HelpCard("Overview", "Named configurations: provider + model + API key + endpoint + system prompt + parameter preset."),
        HelpCard("Inheritance", "Empty agent fields fall through to the provider's defaults."),
        HelpCard("Use", "Add agents to flocks, run them in reports, or open them as a chat target."),
        HelpCard("Pitfalls", "Renaming an agent doesn't update reports / flocks / swarms that already reference it by id.")
    )),
    "agent_edit" to HelpContent("Edit agent", listOf(
        HelpCard("Overview", "Editor for one agent. All fields except provider+model are optional."),
        HelpCard("API key", "Override the provider key when this agent should use a different one (e.g., per-team billing)."),
        HelpCard("Endpoint", "Override the provider URL (e.g., point at a private OpenAI-compatible server)."),
        HelpCard("Test", "Sends a tiny ping with this configuration. The result + raw error live in API Traces.")
    )),
    "flocks" to HelpContent("Flocks", listOf(
        HelpCard("Overview", "Named groups of agents. Selecting a flock for a report is shorthand for 'add every member'."),
        HelpCard("Flock-level params", "System prompt + parameter preset that apply on top of each agent's own settings."),
        HelpCard("Pitfalls", "Adding a deleted-elsewhere agent id leaves a broken reference — re-add the agent first.")
    )),
    "flock_edit" to HelpContent("Edit flock", listOf(
        HelpCard("Overview", "Pick which agents belong to this flock + the flock-level system prompt / parameters."),
        HelpCard("Reorder", "Drag handles to set the order. Reports run agents in parallel, but exports honor this order.")
    )),
    "swarms" to HelpContent("Swarms", listOf(
        HelpCard("Overview", "Named groups of (provider, model) pairs. Unlike flocks (which use agents), swarms reference models directly."),
        HelpCard("Use", "Quick way to test the same prompt against many models without creating an agent for each."),
        HelpCard("Pitfalls", "Swarms don't carry system prompt / parameters — apply them at report-creation time.")
    )),
    "swarm_edit" to HelpContent("Edit swarm", listOf(
        HelpCard("Overview", "Pick the (provider, model) pairs for this swarm."),
        HelpCard("+ All Models", "Bulk-adds every active model from a provider — useful for benchmarking.")
    )),
    "parameters" to HelpContent("Parameters", listOf(
        HelpCard("Overview", "Reusable parameter presets — temperature, max tokens, top-p, top-k, penalties, system prompt, web search, reasoning effort."),
        HelpCard("Merging", "Reports / agents / flocks can stack multiple presets. Later non-null wins; booleans are sticky-true."),
        HelpCard("Pitfalls", "Provider-specific fields are silently dropped when the model doesn't support them — check the trace if behavior surprises you.")
    )),
    "parameters_edit" to HelpContent("Edit parameters", listOf(
        HelpCard("Overview", "Editor for one preset. Leave fields blank to inherit at runtime."),
        HelpCard("Test", "Run a quick prompt against a chosen model with this preset to verify behavior.")
    )),
    "system_prompts" to HelpContent("System prompts", listOf(
        HelpCard("Overview", "Reusable system messages. Attach to agents, flocks, or swarms."),
        HelpCard("Variables", "@MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@ resolve at send time."),
        HelpCard("Pitfalls", "A system prompt assigned at multiple levels stacks — check the trace if you see double prompts.")
    )),
    "system_prompt_edit" to HelpContent("Edit system prompt", listOf(
        HelpCard("Overview", "Editor for one system prompt. Multi-line, no formatting."),
        HelpCard("Variables", "@MODEL@ / @PROVIDER@ / @AGENT@ / @SWARM@ / @NOW@ — substituted before sending.")
    )),
    "internal_prompts_hub" to HelpContent("Internal prompts — hub", listOf(
        HelpCard("Overview", "Internal prompts split into Meta (post-report summarize / compare / etc.), Cross (drill-in answer-vs-source), Model info (per-model description), and other categories."),
        HelpCard("Meta", "Drives Action Row → Meta. Each Meta prompt is one option in that picker."),
        HelpCard("Cross", "Drives Action Row → Cross. Cross prompts pair an answerer prompt with a source-renderer."),
        HelpCard("Model info", "Auto-runs once per Model Info screen visit (cached).")
    )),
    "internal_prompts" to HelpContent("Internal prompts", listOf(
        HelpCard("Overview", "List of internal prompts in one bucket (Meta / Cross / model_info / …). Add / edit / reorder."),
        HelpCard("Type", "chat (sent as a chat completion), rerank (structured ranking), moderation (structured flag), translate, etc. — controls how the response is parsed."),
        HelpCard("Pitfalls", "Changing a prompt's type after it has results may invalidate parsing — old rows render as raw text.")
    )),
    "internal_prompt_edit" to HelpContent("Edit internal prompt", listOf(
        HelpCard("Overview", "Editor for one prompt. Title, body, optional category override, optional agent pin."),
        HelpCard("Variables", "Same set as system prompts — see the System Prompts help for details.")
    )),
    "providers" to HelpContent("Providers", listOf(
        HelpCard("Overview", "Every supported provider with its state. Active / Inactive toggle controls whether reports + chats can use it."),
        HelpCard("State icons", "🔑 = key set, ⚠️ = last test failed, 💤 = inactive."),
        HelpCard("Active only filter", "Hides inactive providers — re-enable from the toggle in the row's edit screen.")
    )),
    "provider_edit" to HelpContent("Provider configuration", listOf(
        HelpCard("Overview", "API key, model selection (API or manual list), admin URL, model list URL, optional parameter preset for this provider's calls."),
        HelpCard("Test", "Pings the provider with a tiny request — surfaces auth / endpoint problems early."),
        HelpCard("Refresh models", "Re-fetches the model list from the provider's catalog API."),
        HelpCard("Endpoints", "Optional per-model-type overrides — e.g., separate URLs for chat vs embeddings."),
        HelpCard("Pitfalls", "Some providers (Google) authenticate via ?key= query param, not Authorization header — automatic, but trace will look unusual.")
    )),
    "provider_add" to HelpContent("Add provider", listOf(
        HelpCard("Overview", "Add a custom OpenAI-compatible provider not in the built-in list."),
        HelpCard("API format", "OPENAI_COMPATIBLE works for most. Use ANTHROPIC / GOOGLE only for those exact APIs."),
        HelpCard("Pitfalls", "If your provider's responses use non-standard fields, write a small Internal Prompt to coerce the format you need.")
    )),
    "models" to HelpContent("Models — list", listOf(
        HelpCard("Overview", "All models for one provider. Filter by capability or pricing tier."),
        HelpCard("Per-row edit", "Tap a row to set per-model overrides: pricing, capabilities, types."),
        HelpCard("Refresh", "Re-fetches the model list from the provider's catalog API.")
    )),
    "model_edit" to HelpContent("Edit model", listOf(
        HelpCard("Overview", "Per-model overrides — vision flag, web-search flag, reasoning flag, model type, manual price."),
        HelpCard("Manual override", "Wins over LiteLLM / models.dev / etc. when set. Useful for new releases the catalogs haven't picked up.")
    )),
    "model_types" to HelpContent("Model types", listOf(
        HelpCard("Overview", "Maps model-id heuristics to API path types (CHAT / RESPONSES / EMBEDDING / IMAGE / etc.)."),
        HelpCard("Use", "Override when the auto-detection picks the wrong path — common with custom OpenAI-compatible providers.")
    )),
    "manual_model_types" to HelpContent("Manual model types", listOf(
        HelpCard("Overview", "Per-(provider, model) explicit type assignment — wins over the heuristic."),
        HelpCard("Pitfalls", "Wrong type → API call fails with a confusing error. Check the trace's URL path if dispatch looks wrong.")
    )),
    "external_services" to HelpContent("External services", listOf(
        HelpCard("Overview", "API keys for catalog services that aren't AI providers themselves — HuggingFace, OpenRouter, Artificial Analysis."),
        HelpCard("Use", "OpenRouter is the single most informative model catalog. Plug in a free API key for richer Model Info pages.")
    )),
    "refresh" to HelpContent("Refresh", listOf(
        HelpCard("Overview", "Re-tests every active provider and re-fetches their model lists. Per-provider progress + status."),
        HelpCard("Bulk action", "Tap Refresh All. Failures don't stop the others — check the row's status icon afterward."),
        HelpCard("Catalogs", "Refresh also re-pulls LiteLLM, models.dev, Artificial Analysis, etc. (when their API keys are set)."),
        HelpCard("Pitfalls", "Big providers (OpenRouter) can take a few seconds. Don't background the screen until status reads done.")
    )),
    "import_export" to HelpContent("Export / Import", listOf(
        HelpCard("Overview", "Backup or restore your config: providers, agents, flocks, swarms, parameters, prompts, KB manifests."),
        HelpCard("Format", "JSON (current export version 23). Import accepts versions 11..23 with automatic migration."),
        HelpCard("API keys", "Optional separate export — keeps secrets out of the main backup when you want to share."),
        HelpCard("Pitfalls", "Import replaces matching items. Duplicate names within the import file cause the last one to win.")
    )),
    "local_runtime" to HelpContent("Local runtime", listOf(
        HelpCard("Overview", "On-device LLM (LocalLlm) + embedder (LocalEmbedder). Runs without network or API keys."),
        HelpCard("LiteRT models", "Smaller LLMs and embedders bundled as LiteRT (.tflite-style) packages."),
        HelpCard("LocalLlm", "Full LLMs (HuggingFace gguf-style)."),
        HelpCard("Pitfalls", "Local models occupy a lot of storage (gigabytes). Don't include them in backups — they're excluded automatically.")
    )),
    "local_litert_models" to HelpContent("LiteRT models", listOf(
        HelpCard("Overview", "Browse / install / remove LiteRT models. Tap a row for details + size."),
        HelpCard("Pitfalls", "Free space matters — installs > 1 GB fail silently on near-full devices.")
    )),
    "local_llms" to HelpContent("Local LLMs", listOf(
        HelpCard("Overview", "Larger HuggingFace LLMs available on-device. Install / remove + size info."),
        HelpCard("Pitfalls", "Throughput depends heavily on device CPU/GPU. Test first on a small prompt before relying on it for reports.")
    )),

    // ===== Setup wizard steps =====
    "setup_providers" to HelpContent("Setup → Providers", listOf(
        HelpCard("Overview", "Walks through enabling at least one provider. Pick a provider, paste its API key, test the connection."),
        HelpCard("Test", "Sends a tiny ping. Failures surface here so you don't run a full report blind.")
    )),
    "setup_models" to HelpContent("Setup → Models", listOf(
        HelpCard("Overview", "Refresh model lists for the providers you just enabled."),
        HelpCard("Pitfalls", "Without this step, model pickers will look empty — refresh once before moving on.")
    )),
    "setup_workers" to HelpContent("Setup → Workers", listOf(
        HelpCard("Overview", "Generate default agents for every working provider, or open Agents / Flocks / Swarms editors."),
        HelpCard("Default agents", "One agent per active provider, named after its display name. A handy starting set.")
    )),
    "setup_prompts" to HelpContent("Setup → Prompts", listOf(
        HelpCard("Overview", "System prompts + internal prompts (Meta, Cross, model-info, …)."),
        HelpCard("Pitfalls", "The default Meta + Cross prompts are minimal — extend them once you know what comparisons you actually want.")
    )),
    "setup_local_models" to HelpContent("Setup → Local models", listOf(
        HelpCard("Overview", "Optional: install a LiteRT model or local LLM for fully on-device generation."),
        HelpCard("Pitfalls", "Skip this step entirely if storage is tight — cloud providers cover all the same flows.")
    )),

    // ===== Admin =====
    "housekeeping" to HelpContent("Housekeeping", listOf(
        HelpCard("Overview", "Maintenance hub — Backup, Export/Import, Refresh, Trim by age, Usage statistics, Manual cost overrides, Internal prompts, Reset."),
        HelpCard("Backup & Restore", "Single-zip backup of every config + report + chat. Restore validates the zip before overwriting."),
        HelpCard("Trim by age", "Drops reports / chats / traces older than N days in a single shot."),
        HelpCard("Reset", "Each sub-section has its own confirm — used for clearing config, runtime data, or specific stores. Read carefully before tapping."),
        HelpCard("Pitfalls", "Reset's bulk-clear is irreversible. Take a Backup first.")
    )),
    "statistics" to HelpContent("AI Usage", listOf(
        HelpCard("Overview", "Token usage and costs per provider+model+kind. Aggregated across reports, chats, secondaries, and one-shot tests."),
        HelpCard("Cost calculation", "Layered pricing: provider self-report → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter cross-provider → Helicone → default."),
        HelpCard("Per-model row", "Tap a row to open Model Info for that (provider, model) pair."),
        HelpCard("Title bar", "🗑 → clear all stats (with confirm). Counters reset to zero.")
    )),
    "cost_config" to HelpContent("Cost config", listOf(
        HelpCard("Overview", "Lists per-(provider, model) manual cost overrides."),
        HelpCard("Add", "+ override prompts for a provider, model, and per-million-tokens prices."),
        HelpCard("Pitfalls", "Manual overrides win over every catalog source. Set them carefully — typos give wrong cost totals across the app.")
    )),
    "cost_override" to HelpContent("Add cost override", listOf(
        HelpCard("Overview", "Editor for one cost override. Pick provider + model, then set prompt / completion prices in USD per million tokens."),
        HelpCard("Pitfalls", "Empty / zero prices effectively reset the override — use the catalog default by removing the override instead.")
    )),
    "trace_list" to HelpContent("API Traces", listOf(
        HelpCard("Overview", "Captured request + response pairs for every API call. Per-trace status / duration / model / hostname."),
        HelpCard("Filters", "Category / Provider / Hostname / Model + 'Errors only' toggle. Each filter narrows the active list."),
        HelpCard("Per-row tap", "Opens the trace detail with the full request + response JSON."),
        HelpCard("Title bar", "🗑 → clear every captured trace (with confirm). Per-row delete isn't shown — manage in bulk.")
    )),
    "trace_detail" to HelpContent("Trace detail", listOf(
        HelpCard("Overview", "Full request + response for one captured call. Tabs: All / Request headers / Response headers / Request data / Response data."),
        HelpCard("Title bar", "🔄 re-fires the captured call (with confirm); ℹ️ → Model Info; 🗑 not wired here; 🐞 not wired (we are the trace)."),
        HelpCard("Provider / Agent buttons", "Provider opens the provider edit screen; Agent appears when a saved agent matches this (provider, model)."),
        HelpCard("Translation result", "Side-by-side viewer for traces tagged 'Translation'."),
        HelpCard("Copy / Share", "Both perform a redaction pass first — sensitive headers + JSON keys + URL params are blanked.")
    )),
    "trace_pick_model" to HelpContent("Trace — pick model", listOf(
        HelpCard("Overview", "Filter the trace list to one model. Tap a row, the list reloads."),
        HelpCard("(All)", "Top entry resets the filter.")
    )),

    // ===== Developer =====
    "developer_test" to HelpContent("API test", listOf(
        HelpCard("Overview", "Send arbitrary requests to any provider+model. Useful for debugging custom endpoints."),
        HelpCard("Pitfalls", "Counts toward AI Usage. Not for production runs — use Reports / Chat instead.")
    )),
    "developer_edit" to HelpContent("API request edit", listOf(
        HelpCard("Overview", "Edit the captured JSON of a previous request before re-firing. Used from Trace detail's Edit Request button."),
        HelpCard("Pitfalls", "Editing the wrong fields can produce unparseable responses — the response viewer falls back to raw text in that case.")
    )),

    // ===== Search =====
    "search_local" to HelpContent("Local search", listOf(
        HelpCard("Overview", "Substring scan across saved reports + chats stored on this device. No embeddings, no AI."),
        HelpCard("Use it when", "You remember a phrase or model name — fastest path to the source.")
    )),
    "search_semantic" to HelpContent("Semantic search", listOf(
        HelpCard("Overview", "Embedding-based search across saved content. Pick an embedder + a query."),
        HelpCard("Pitfalls", "Cost-per-query depends on the embedder. Local embedder is free.")
    )),
    "search_quick" to HelpContent("Quick local search", listOf(
        HelpCard("Overview", "Lightweight substring search — typing a query filters the result list as you go."),
        HelpCard("Use it when", "You want immediate feedback for short queries.")
    )),
    "search_local_semantic" to HelpContent("Local semantic search", listOf(
        HelpCard("Overview", "Embedding-based search powered by the on-device embedder. No API calls, no network."),
        HelpCard("Pitfalls", "Local embeddings are smaller / less accurate than cloud embedders. Good enough for most personal-content queries.")
    )),

    // ===== Misc =====
    "provider_admin" to HelpContent("Provider admin", listOf(
        HelpCard("Overview", "Per-provider operations not covered by the standard provider edit screen — e.g., open the provider's admin URL in a browser."),
        HelpCard("Pitfalls", "Some providers' admin URLs need authentication — sign-in there before tapping report links from the trace screen.")
    )),
    "report_export_screen" to HelpContent("Report export", listOf(
        HelpCard("Overview", "See: Export report.")
    )),
    "content_view" to HelpContent("Content view", listOf(
        HelpCard("Overview", "Generic full-screen viewer for one piece of report content (response body / prompt / costs panel)."),
        HelpCard("Think sections", "Models that emit <think> blocks render those collapsibly so the main content stays readable.")
    )),
    "prompt_view" to HelpContent("Prompt view", listOf(
        HelpCard("Overview", "Read-only viewer for the report's prompt as it was actually sent — variables substituted, system prompts merged."),
        HelpCard("Use it for", "Verifying what the model actually saw when results look surprising.")
    ))
)
