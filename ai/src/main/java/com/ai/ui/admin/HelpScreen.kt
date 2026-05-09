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
        "This app runs AI reports, chats, and dual chats against ${'$'}{AppService.entries.size} cloud providers plus on-device models. Configure providers with API keys, then build reports, chats, or knowledge bases from the Hub."
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
    "hub" to HelpContent(
        title = "AI Hub",
        cards = listOf(
            HelpCard("Overview", "Home base for the app. Every other area lives one tap away from here, and tapping the logo at the top opens the most recent report when at least one exists."),
            HelpCard("AI Reports", "Send one prompt to many models in parallel. Disabled until at least one Agent has been defined under AI Setup → Workers."),
            HelpCard("AI Chat", "Single-model and dual-model chat sessions. Disabled until at least one Agent has been defined."),
            HelpCard("AI Knowledge", "Build RAG knowledge bases from files / web pages. Always enabled — KB management doesn't need a configured provider."),
            HelpCard("AI Models", "Search every fetched model from every active provider. Disabled until at least one Agent exists."),
            HelpCard("AI Usage", "Token + cost roll-up across reports / chats / secondaries. Disabled when nothing has been run yet (loadUsageStats() is empty)."),
            HelpCard("AI API Traces", "Hidden entirely when tracing is off in Settings. When tracing is on but no traces exist yet, the row is shown but disabled."),
            HelpCard("AI Setup / AI Housekeeping / Settings / Help", "Setup edits providers, workers, prompts, local models. Housekeeping is the maintenance hub. Settings is the small per-user toggles. Help opens this page."),
            HelpCard("Tips", "The logo image itself is clickable when there's at least one saved report — it jumps you straight to the latest. Card heights and logo sizing adapt to the screen so all 9 (or 10) cards fit without scrolling."),
            HelpCard("Title bar", "❓ opens topic-specific help. 🏠 returns here from anywhere in the app.")
        )
    ),
    "reports_hub" to HelpContent(
        title = "AI Reports",
        cards = listOf(
            HelpCard("Overview", "Reports send the same prompt to multiple models in parallel and collect every response. Each result is saved to disk and can be reopened, exported, translated, summarised, or fed forward into a chat."),
            HelpCard("In-flight pill", "When at least one report has unfinished agents (PENDING / RUNNING and no completedAt), an orange ⏳ pill appears at the top — tap it to resume the most recent in-flight run without going through History."),
            HelpCard("Start card", "Three entries: New AI Report (blank), Start with a previous prompt (last 100, deduplicated), Start with photo (camera capture becomes the seed image for a vision-capable run)."),
            HelpCard("View previous reports", "Opens the History list. Disabled when nothing has been saved yet."),
            HelpCard("Pinned and Recent", "Every pinned report is listed under 📌. The three most recent unpinned reports show under 🕘. Tap a row to open the report."),
            HelpCard("Search card", "Four search modes ordered by escalating cost: 🔍 Quick local (substring), 📂 Extended local (tokenised), 🌐 Remote semantic (cloud embeddings), 📱 Local semantic (on-device embedder)."),
            HelpCard("Manage", "Bulk operations — trim by age, export-all backup zip. Disabled until at least one report exists."),
            HelpCard("Shared files banner", "When the Android share-target routed files into a fresh KB, a green banner appears on New Report offering Attach as KB / Skip — handled automatically once dismissed.")
        )
    ),
    "report_new" to HelpContent(
        title = "New AI Report",
        cards = listOf(
            HelpCard("Overview", "Two-stage: type a title + prompt here, then on Next pick which agents / flocks / swarms / models receive the prompt. The title and prompt are saved to LAST_AI_REPORT_TITLE / _PROMPT and to the last-100 prompt history."),
            HelpCard("Title and prompt", "Both are required for Next to enable. Title is single-line; prompt is multi-line with a 10-line minimum height. Clear wipes both fields plus any attached image."),
            HelpCard("Image attachment", "📎 picks an image from device storage and attaches it as base64 — passed through to every agent's prompt at dispatch. Only vision-capable models will actually read it; the rest receive the text alone. Image-attached reports can be MB-sized on disk."),
            HelpCard("Web search chip", "🌐 tags every dispatched call with the web-search tool flag. Providers and models that don't support web search drop the flag silently."),
            HelpCard("Thinking chip", "🧠 None / Low / Medium / High. Applied to every agent at dispatch; non-thinking models drop the field automatically."),
            HelpCard("Validate prompt chip", "🛡 picks a moderation model and runs the prompt through it before any agent fires. If the model flags the prompt, you get a Proceed-anyway / Cancel dialog with a 🐞 link to the moderation trace; tap when on to clear the model."),
            HelpCard("Shared KB banner", "When files were routed in via the share-target, a green banner offers a one-tap KB build from those files (using the local default embedder when installed, otherwise the first remote embedding model). Indexing runs on Dispatchers.IO with progress messages; the new KB id auto-attaches to the report."),
            HelpCard("Next", "Saves title + prompt to last-prompt prefs and prompt history, then routes to the model-selection screen. While moderation is running the button shows a spinner.")
        )
    ),
    "report_result_generation" to HelpContent(
        title = "Report — selection and results",
        cards = listOf(
            HelpCard("Overview", "Single screen with two phases. Selection: empty list with +Agent / +Flock / +Swarm / +Model / +Report buttons + Params + Generate. Results: the same screen flips into per-agent rows + Action row once Generate fires."),
            HelpCard("Add buttons (selection)", "+Agent picks one saved agent, +Flock adds every member of a flock, +Swarm adds every (provider, model) pair in a swarm, +Model is the single-select all-providers picker, +Report copies the model list from a previous report."),
            HelpCard("Knowledge attach (selection)", "When you have at least one saved KB, a 📚 row shows the current attachment count and opens a multi-select. Attached KB ids ride with the report and inject relevant chunks into every dispatched call."),
            HelpCard("Params (selection)", "Opens Advanced Parameters — temperature, max tokens, system prompt, etc. The button reads Params ✓ when an override is active. Clear all wipes the override."),
            HelpCard("Generate / Update model list", "Generate fires the dispatch. When entered via Edit / Models on a finished report, the bottom button switches to Update model list — stages the new list and pops back without re-running; you re-fire from Action row → Regenerate."),
            HelpCard("Action row (results)", "While running: STOP / Background. Once complete: View, Edit, Regenerate, Export, Copy, Pin/Unpin, Translate, Meta (disabled when no Meta prompts exist), Fan out (disabled when no Fan-out prompts exist)."),
            HelpCard("View popup", "Reports / Prompt / Costs plus one row per Meta-prompt name with at least one persisted secondary. Edit popup is Prompt / Title / Models / Parameters."),
            HelpCard("Pending-changes banner", "Orange banner appears when the user edited prompt / models / parameters since the last run — Regenerate is required for the new values to take effect."),
            HelpCard("Per-row 🐞", "Each agent row carries the trace icon when its API call left a recording. Tapping opens that single trace file."),
            HelpCard("Title bar", "Selection phase: only the back arrow is wired. Results phase: ℹ️ opens a model picker that jumps to any agent's Model Info; 🗑 deletes the entire report after confirm; 🐞 opens the trace list filtered to this reportId; 🔄 is grayed because regeneration is multi-call (use the Action row)."),
            HelpCard("Stuck rows", "On reopen, any row left in PENDING / RUNNING by a force-quit is recovered: a one-shot sweep marks blank-content / null-error / null-duration secondaries as errored, and a 150 ms tick refreshes the inline meta list. If a row still spins, tap Regenerate.")
        )
    ),
    "report_single_result" to HelpContent(
        title = "Single agent result",
        cards = listOf(
            HelpCard("Overview", "Detail view for one agent's response, reached by tapping a row on the result screen. Renders <conclusion> and <motivation> blocks separately when present, then the rest of the body, with collapsible <think> sections."),
            HelpCard("Header", "Provider name in the title bar; provider — model line in blue is tappable to open Model Info. Errors render as a red Error block with the underlying message; blank bodies show 'No analysis available'."),
            HelpCard("Title bar — 🔄", "Reload icon opens a confirmation dialog (target = provider / model). Confirming calls onRegenerateAgent for this single (reportId, agentId) and pops back to the result screen."),
            HelpCard("Title bar — ℹ️", "Always wired here. Jumps to Model Info for this agent's (provider, model) pair."),
            HelpCard("Title bar — 🗑", "Always wired. Opens 'Remove from report?' confirm. Confirming drops just this row, recomputes totals, and pops back."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on AND ApiTracer.getTraceFiles() finds a record where reportId == this report and model == this agent's model — opens the most recent matching trace."),
            HelpCard("Translation info", "Shown only when this report has a sourceReportId and the matching agent's responseBody is loaded — opens TranslationCompareScreen with original on top, translation on bottom."),
            HelpCard("Continue in chat", "Disabled when the response is blank or errored. Opens the Continue picker (current history+model / pick agent / configure on the fly)."),
            HelpCard("Pitfalls", "Removing the last successful agent from a report leaves it empty — reopen the parent report and re-run from the Action row.")
        )
    ),
    "report_continue_in_chat" to HelpContent(
        title = "Continue in chat",
        cards = listOf(
            HelpCard("Overview", "Three-row picker that hands this agent's response off to a fresh chat session as the seed turn. Reached from the 💬 button on the single-agent result."),
            HelpCard("📜 with current history and model", "Reuses the same provider/model and the agent's resolved system prompt + parameters from current settings. The chat starts with the report prompt + this response already in the transcript."),
            HelpCard("🤖 with this response only and select an agent", "Stashes the agent's response as the next chat's input-box starter and routes to the agent picker. The picked agent's system prompt and parameters then drive the session."),
            HelpCard("🛠️ with this response only and configure on the fly", "Stashes the response and walks you through provider → model → parameters before opening the chat — handy when none of your saved agents fit."),
            HelpCard("Tips", "All three rows are always enabled here; the upstream button on the single-result screen is the one that disables on empty / errored responses."),
            HelpCard("Title bar", "Only the back arrow is wired."),
            HelpCard("Related", "Single agent result → 💬 Continue in chat opens this. Picking a row navigates to a Chat session.")
        )
    ),
    "secondary_list" to HelpContent(
        title = "Secondary results — list",
        cards = listOf(
            HelpCard("Overview", "Lists every persisted secondary of one kind (Rerank / Meta / Moderation; Translate has its own per-run screen). Same screen serves all kinds — title is the user-given Meta-prompt name (or the legacy kind label for older rows)."),
            HelpCard("Polling", "While at least one batch is in flight (isBatching), the list re-reads disk every 500 ms so newly-stamped placeholders flip from ⏳ to ✅/❌ without leaving the screen."),
            HelpCard("Language picker", "For chat-type META, when the report has TRANSLATE rows a row of language pills appears: Original plus one per distinct targetLanguage. Selecting a non-Original language overlays translated bodies onto the matching original rows."),
            HelpCard("Fan out drill-in", "Fan-out prompts paint 'Fan out / Fan out - model / Fan out - pair' as the user steps in. L1 lists every (answerer, source) pair, L2 lists sources for one answerer with a Responder/Initiator role toggle, L3 splits a single source/fan-out response side-by-side. System back steps out one level at a time."),
            HelpCard("Meta picker view", "Chat-type META renders a FlowRow of buttons (one per result, labelled by provider · model) plus the selected result inline — mirror of the Reports viewer."),
            HelpCard("Per-row 🗑", "Each row has its own confirm dialog. Title-bar 🗑 is grayed because deletion is per-row here."),
            HelpCard("Per-row tap", "Opens SecondaryResultDetailScreen. Drilling into the same row after popping back is preserved via rememberSaveable openId."),
            HelpCard("Title bar", "Title reflects the active filter (Meta-prompt name or 'Fan out / Fan out - model / Fan out - pair'). Trace / Info / Reload icons aren't wired at this list level — they live on the per-row detail screen.")
        )
    ),
    "secondary_detail" to HelpContent(
        title = "Secondary result — detail",
        cards = listOf(
            HelpCard("Overview", "Full content of one Rerank / Meta / Moderation row. Errors render as a red Error block; blank content shows '(no content)'."),
            HelpCard("Rerank rendering", "Tries to parse the structured JSON ([{id, rank, score, reason}, ...]) and render a sorted RerankTable. Falls back to raw markdown via ContentWithThinkSections when the model deviated from the schema."),
            HelpCard("Moderation rendering", "Parses [{id, flagged, categories, scores}, ...] into a ModerationTable with 🚩 / ✓ flags, fired categories, and the top 3 scores. Falls back to raw text on bad JSON."),
            HelpCard("Meta rendering", "Always renders via ContentWithThinkSections so <think> blocks collapse and the rest is plain Markdown-ish text."),
            HelpCard("Title bar — 🔄", "Not wired here — re-run a secondary by deleting and re-firing from the report's Action row."),
            HelpCard("Title bar — ℹ️", "Wired when the providerId resolves. Jumps to Model Info for this row's (provider, model) pair."),
            HelpCard("Title bar — 🗑", "Wired. Opens 'Delete this <kindLabel>?' confirm; confirming calls onDelete and pops back."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and a matching trace exists (reportId + this row's model, max-by-timestamp)."),
            HelpCard("Translation info", "Shown only for META rows that have a translateSourceTargetId resolving to a non-blank source — opens TranslationCompareScreen.")
        )
    ),
    "secondary_fan_out" to HelpContent(
        title = "Fan out",
        cards = listOf(
            HelpCard("Overview", "Fan out runs every selected answerer × every source row's content. Each cell is one fan-out response call; the title bar reads 'Fan out / Fan out - model / Fan out - pair' as you drill in."),
            HelpCard("Fan out (L1)", "List of every (answerer model, status) — derived from latestByPair across all results. Status icons: ✅ done, ❌ errored, ⏳ running, queued. Tap an answerer to drop to L2."),
            HelpCard("Fan out - model (L2)", "Sources for the chosen answerer (or, if Initiator role is selected, every (answerer, source) where this model is the source). Tap a source row → L3."),
            HelpCard("Fan out - pair (L3)", "Single cell — source content on top, fan-out response underneath. Per-row 🐞 links to its own captured trace."),
            HelpCard("Resume stale", "On open, any fan-out pair with no content / no error / not in runningFanOutPairs is re-enqueued via onResumeStaleFanOut — survives app kill mid-batch."),
            HelpCard("Restart failed", "Re-runs only ❌ cells, leaving ✅ alone. Skips the placeholder grid rebuild — quick recovery without re-spending tokens on succeeded cells."),
            HelpCard("Combine reports", "When at least one fan-in prompt exists, the screen exposes 'Run combine reports' — fires a meta call against the fan-out matrix's results."),
            HelpCard("Per-model delete", "Drops every cell where this answerer participated. Fan-out list refresh tick bumps so L1 reflects the gap on pop-back."),
            HelpCard("Pitfalls", "Cell count is N×(N-1) for an N-agent run; cost grows fast. Watch the Action row's cost summary before pressing Restart on large grids.")
        )
    ),
    "secondary_scope" to HelpContent(
        title = "Secondary scope",
        cards = listOf(
            HelpCard("Overview", "Inserted between a Meta-prompt button and the model picker. Picks which rows feed into the next run + (when relevant) which target languages."),
            HelpCard("All model reports", "Default. Uses every successful agent on the report (count shown in the sublabel)."),
            HelpCard("Only top ranked reports", "Available for meta / fan-out prompts when the report has at least one rerank row. Pick the rerank source from a dropdown and an N (1..total)."),
            HelpCard("Manual select models", "Tick exactly which agent rows to include. Defaults to every successful agent ticked, so it's a starting point you can prune."),
            HelpCard("Languages section", "Only shown for chat-type prompts when the report has translation rows. All languages = original + every translated; Select languages = pick a subset alongside the original."),
            HelpCard("Continue", "Disabled until the chosen scope yields at least one input — Top-Ranked needs a rerank picked + count > 0, Manual needs at least one tick."),
            HelpCard("Pitfalls", "Rerank / moderation runs always operate on the full agent set — those kinds skip this screen entirely."),
            HelpCard("Title bar", "Only the back arrow is wired.")
        )
    ),
    "report_meta" to HelpContent(
        title = "Meta",
        cards = listOf(
            HelpCard("Overview", "Unified Meta screen. Top: every persisted Meta-prompt result (TRANSLATE excluded — those live in the cost table only), newest first. Bottom: an Add card with one button per saved Meta prompt."),
            HelpCard("Polling", "While isRunning is true, refreshTick bumps every 500 ms — placeholders that runSecondary writes from its IO coroutine surface as ⏳ rows here without bouncing in/out of the screen."),
            HelpCard("Per-row icons", "❌ for errored, animated rotating ⏳ for in-flight (blank content), ✅ for completed."),
            HelpCard("Per-row content", "Kind label in orange, provider · model in white, timestamp underneath. Cost (input + output cents, monospace) appears when totalCost > 0."),
            HelpCard("Per-row 🗑", "Each row has its own confirm. Picks the noun from the row's metaPromptName (or legacy kind label)."),
            HelpCard("Add card", "FlowRow of orange buttons sorted by name, one per metaPrompts entry. Empty case shows a hint pointing at AI Setup → Prompt management → Report Meta Prompts."),
            HelpCard("Tap a row", "Opens SecondaryResultDetailScreen for that result — full content + ℹ️ Model Info + 🐞 trace + 🗑."),
            HelpCard("Title bar", "Only the back arrow is wired here. Per-row delete is the model — title-bar 🗑 would be ambiguous on a list of mixed kinds.")
        )
    ),
    "report_edit_prompt" to HelpContent(
        title = "Edit prompt / title",
        cards = listOf(
            HelpCard("Overview", "Modify the report's prompt (or just its title). Saving stamps hasPendingPromptChange so the result screen surfaces a yellow 'Changes pending: prompt' banner — the existing rows aren't re-rendered until you tap Regenerate."),
            HelpCard("Prompt field", "Multi-line, fills the screen. Update prompt is disabled when the body trims to blank."),
            HelpCard("Title field (Edit title variant)", "Single-line, no pending-changes flag — title is metadata only and doesn't affect any outbound API call."),
            HelpCard("Saver scoping", "rememberSaveable is keyed on initialPrompt / initialTitle so re-opening the overlay with a fresh seed value doesn't restore a stale draft from the SaveableStateRegistry."),
            HelpCard("Title bar", "Only the back arrow is wired here. The screen is reached from Action row → Edit → Prompt or Title."),
            HelpCard("Pitfalls", "Editing the prompt alone doesn't re-run agents — the existing responses stay on screen until you Regenerate. Edit title is in-place: no banner, no regenerate prompt."),
            HelpCard("Related", "Action row → Edit → Models routes to the selection phase in 'Update model list' mode for stripping/adding agents.")
        )
    ),
    "report_parameters" to HelpContent(
        title = "Advanced Parameters",
        cards = listOf(
            HelpCard("Overview", "Per-report parameter override that wins over each agent's own settings for this run only. Apply stamps hasPendingParametersChange so the result screen shows the pending banner."),
            HelpCard("Numeric fields", "Temperature (0.0–2.0), max tokens, top P (0.0–1.0), top K, frequency / presence penalty (-2.0–2.0), seed. Empty fields mean 'inherit' — only non-blank values become part of the override."),
            HelpCard("System prompt", "Multi-line (3–5 lines visible). Replaces the agent / flock / swarm system prompt for this run when non-blank."),
            HelpCard("Web search / Citations / Recency", "xAI-style and Perplexity-style toggles. Recency takes 'day', 'week', 'month', 'year' — anything else is dropped silently."),
            HelpCard("Apply", "Builds an AgentParameters from non-blank values. If everything is blank/default, calls onApply(null) — i.e. clears the override."),
            HelpCard("Clear all", "Wipes every field and calls onApply(null). Useful when starting over."),
            HelpCard("Pitfalls", "Provider-specific fields (e.g. Anthropic ignores frequency/presence penalty) are silently dropped server-side — check the trace if behaviour surprises you."),
            HelpCard("Title bar", "Only the back arrow is wired.")
        )
    ),
    "report_export" to HelpContent(
        title = "Export report",
        cards = listOf(
            HelpCard("Overview", "Pick a format and (when relevant) a detail level, then either share to another app, view in browser, or build the master Export-all zip."),
            HelpCard("Format chips", "HTML, PDF, MS Word, OpenDocument, JSON, Zipped HTML — wrap to a second row on narrow phones via FlowRow. JSON and Zipped HTML ignore the detail picker; everything else honors it."),
            HelpCard("Detail — Short", "Prompt, per-model results (with citations and related questions), Meta sections (one per Meta prompt) plus Moderations. No index, no costs, no traces."),
            HelpCard("Detail — Complete", "Index, prompt, every Meta section, Reranks / Moderations / Translations, the cost table, and every captured API trace with redacted bodies."),
            HelpCard("Android share", "Builds the file and hands it to the system share sheet. Closes the Export screen so back from the chooser doesn't loop here."),
            HelpCard("View in browser", "Builds the file and opens it as a separate Android intent. Stays on this screen so you can come back and try a different format without rebuilding the picker state."),
            HelpCard("Export all (zip)", "Bundles all 8 documents (Short + Complete × HTML / PDF / DOCX / ODT) plus the JSON traces zip into one master zip and shares it. Pops the screen on success."),
            HelpCard("Progress dialog", "While building, a non-dismissable dialog shows a linear progress bar driven by (done, total) updates from the export. Failures show a Toast with the exception class + message; the dialog clears."),
            HelpCard("Title bar", "Only the back arrow is wired.")
        )
    ),
    "report_manage" to HelpContent(
        title = "Manage reports",
        cards = listOf(
            HelpCard("Overview", "Hub-level housekeeping for saved reports — two cards: Delete old reports, and Export all (backup)."),
            HelpCard("Delete old reports", "Numeric field (digits, max 4) for 'Older than (days)'. Pinned reports are skipped. Confirm dialog shows the candidate count before any file is touched."),
            HelpCard("Export all (backup)", "Zips every report JSON plus every secondary results file into a single archive (ai_reports_backup_<ts>.zip) and opens the system share sheet. Status text reads 'Bundled N reports' on success."),
            HelpCard("Working state", "While the zip / delete is in flight, both buttons are disabled and the export label switches to 'Working…'."),
            HelpCard("Status line", "Final operation result lives as a small grey line at the bottom — 'Deleted N reports.', 'Bundled N reports.', or 'Nothing to export.'."),
            HelpCard("Pitfalls", "Delete is irreversible — once the cutoff fires, those reports' secondaries and trace files go too. Take an Export all first if you might want them back."),
            HelpCard("Title bar", "Only the back arrow is wired."),
            HelpCard("Related", "Use Housekeeping → Backup & Restore for the full-app backup; this screen is reports-only.")
        )
    ),
    "report_view_picker" to HelpContent(
        title = "View — picker",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker reached from the Report Result action row's View button. Each row is a separate view of the current report."),
            HelpCard("Reports", "Opens the per-agent reports viewer. Detail line shows N of M agents succeeded so you can spot a partially-failed run before drilling in."),
            HelpCard("Prompt", "Opens the report's full prompt as scrollable text. Detail line previews the first non-blank line (≤80 chars)."),
            HelpCard("Costs", "Tokens + cost breakdown across all agents and secondaries. Detail line shows the secondary spend so far in USD when there is any."),
            HelpCard("Per-Meta-prompt rows", "One row per Meta-prompt name with at least one persisted secondary on this report. Detail = run count; secondary line = the kind label (Rerank / Summarize / Compare / Moderate)."),
            HelpCard("Title bar", "Static 'View' title; only the back arrow is wired here. Help points at this card.")
        )
    ),
    "report_edit_picker" to HelpContent(
        title = "Edit — picker",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker reached from the Report Result action row's Edit button. Each row routes to a separate edit screen for one slice of the report."),
            HelpCard("Prompt", "Opens the multi-line prompt editor. Detail line previews the first non-blank line (≤80 chars). Saving stamps a 'Changes pending: prompt' banner on the result screen until you Regenerate."),
            HelpCard("Title", "Single-line title editor. No pending-changes flag — title is metadata only."),
            HelpCard("Models", "Routes back to the selection phase with the report's existing model list staged for in-place editing. The detail line says how many models are currently on the report."),
            HelpCard("Parameters", "Opens the per-report parameter override (temperature, max tokens, top P, stop sequences, etc). Detail line is a generic field hint."),
            HelpCard("Title bar", "Static 'Edit' title; only the back arrow is wired here. Help points at this card.")
        )
    ),
    "report_fan_out_confirm" to HelpContent(
        title = "Fan out — confirm run",
        cards = listOf(
            HelpCard("Overview", "Confirmation screen shown after the Fan out scope picker, before the runner kicks off. Lists exactly how many calls a Run will fire and which models are involved."),
            HelpCard("Counts grid", "answerers × responses-per-report = total calls. Falls back to a flat 'N calls' line when scope is uneven enough that the grid math doesn't divide cleanly."),
            HelpCard("Scope", "All reports / Top-N ranked / Manual selection. Reflects the choice made on the previous screen — back to change it."),
            HelpCard("Answerer / Source lists", "Two cards listing the model names on each side of the fan out. A model appears in both when it's both an answerer and a source."),
            HelpCard("Fan-out prompt", "Preview of the prompt body (≤12 lines) that will be sent for every pair, with @RESPONSE@ filled in at run time."),
            HelpCard("Run / Cancel", "Run is disabled while the count loads or when there are zero pairs. Cancel pops back to the previous screen without firing.")
        )
    ),
    "developer_select_model" to HelpContent(
        title = "API Test — Select Model",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker over the active provider's model list. Tap a row to drop the chosen model into the API Test request."),
            HelpCard("Search field", "Filters by model id (case-insensitive). The ✕ trailing icon clears the field. Counter line shows '<filtered> of <total> models'."),
            HelpCard("Loading state", "If the model list hasn't been fetched yet, a spinner appears in the body. Tap Fetch from the API Test page first when the list reads empty."),
            HelpCard("Pricing column", "Per-row prompt / completion price (×10⁶ tokens). Real pricing renders in green; rows that fell through to DEFAULT_PRICING render dim with 'no pricing'."),
            HelpCard("Title bar", "Static 'Select Model' title; the green sub-header below shows the active provider's name. Only the back arrow is wired here.")
        )
    ),
    "developer_select_endpoint" to HelpContent(
        title = "API Test — Select Endpoint",
        cards = listOf(
            HelpCard("Overview", "Full-screen picker over the active provider's endpoints. The first row is the provider's default base URL; saved custom endpoints follow."),
            HelpCard("Default row", "Drops the provider.baseUrl into the API Test request. Always present even when there are no custom endpoints saved."),
            HelpCard("Custom rows", "One row per Endpoint defined under AI Setup → Providers → Endpoints for this provider. Label + URL on two lines (URL is monospace)."),
            HelpCard("Title bar", "Static 'Select Endpoint' title; the green sub-header shows the active provider's name. Only the back arrow is wired here.")
        )
    ),
    "refresh_result" to HelpContent(
        title = "Refresh — result",
        cards = listOf(
            HelpCard("Overview", "Result screen shown after a Refresh sub-action finishes (catalog refresh, provider state, model refresh, default-agent generation). Replaces the popup result dialogs the screen used to show."),
            HelpCard("Description block", "Short explanation of what the refresh did and why. Failure states explain what to check (API key, connectivity, etc)."),
            HelpCard("Result rows", "One row per measured value — Status / counts / cache age. Green = loaded, red = failed, grey = neutral metric."),
            HelpCard("Sample model entries", "Catalog refreshes (OpenRouter / LiteLLM) include up to 8 sample model keys from the cache so you can confirm real data landed."),
            HelpCard("OK button", "Returns to the Refresh screen. Multi-row screens (Provider state, Default agents) update live while the underlying refresh runs.")
        )
    ),
    "report_pick_flock" to HelpContent(
        title = "Pick a flock",
        cards = listOf(
            HelpCard("Overview", "Modal dialog that lists every saved flock with its agent count and a synthetic per-million-tokens cost band. Tap a row to add every member to the report."),
            HelpCard("Search field", "Filters by name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("Member count", "Reflects what expandFlockToModels actually feeds the report — agents whose provider isn't Active are skipped, so the count matches the worker count after Generate."),
            HelpCard("Pricing column", "Sums per-million prompt / completion across all members. Red when at least one member has real pricing data; grey-on-grey badge when every member fell through to DEFAULT."),
            HelpCard("Empty state", "Opens an empty list; define flocks in AI Setup → Workers → Flocks first."),
            HelpCard("Back button", "Bottom-right TextButton dismisses without a selection."),
            HelpCard("Pitfalls", "Flocks reference agents by id; deleting the underlying agent leaves a broken member. Edit the flock first."),
            HelpCard("Related", "+Agent for one agent at a time, +Swarm for direct provider/model groups (no system prompt / parameters).")
        )
    ),
    "report_pick_agent" to HelpContent(
        title = "Pick an agent / previous report",
        cards = listOf(
            HelpCard("Overview", "Two flows share this help topic: the agent dialog reached by +Agent, and the full-screen 'Pick previous report' picker reached by +Report on the selection phase."),
            HelpCard("Agent dialog", "Lists every saved agent with name + provider · model + per-million-token pricing. Search filters by name or provider name. Tap a row to add it to the report."),
            HelpCard("Pricing badge", "Red when the model has real pricing data; grey-on-grey when the row fell through to DEFAULT_PRICING. Updates as PricingCache loads tier blobs in the background."),
            HelpCard("Pick previous report", "Single-select picker over saved reports — newest first by Report.timestamp. Tap to copy that report's model list into the current selection."),
            HelpCard("Search (previous report)", "Filters by title or prompt. Search results count line shows '<filtered> of <total> reports'."),
            HelpCard("Empty state", "When there are no agents / reports yet, the body is empty (agent dialog) or shows 'No previous reports yet.' (report picker)."),
            HelpCard("Pitfalls", "Reports list is loaded off the UI thread because getAllReports re-parses every report JSON, including image-attached ones."),
            HelpCard("Title bar / dismiss", "Agent dialog dismisses via a Back TextButton at the bottom-right; the previous-report picker has a normal back arrow.")
        )
    ),
    "report_pick_swarm" to HelpContent(
        title = "Pick a swarm / model",
        cards = listOf(
            HelpCard("Overview", "Two flows share this help topic: the swarm dialog reached by +Swarm, and the full-screen single-select model picker reached by +Model and the moderation / rerank pickers."),
            HelpCard("Swarm dialog", "Lists every swarm with member count + summed per-million pricing. Tap to add every (provider, model) pair to the report."),
            HelpCard("Model picker — list", "Joins every active provider's catalog plus, when a model-type filter is set, on-device LiteRT models exposed under the synthetic Local provider."),
            HelpCard("Provider filter", "Dropdown above the list — All Providers or one specific provider (count shown next to each name). LOCAL appears here only when the type filter has matching local models."),
            HelpCard("Type filter", "When opened with a modelTypeFilter (RERANK / MODERATION / EMBEDDING / etc.), a checkbox '<Type> models only' is shown ON by default — untick to widen to the full catalog."),
            HelpCard("Already-added rows", "Rows passed in via alreadyAdded render at 0.4 alpha, are not clickable, and append ' · already added' next to capability badges."),
            HelpCard("Pricing column", "Per-token (×10⁶) prompt / completion, red for real data, grey badge for DEFAULT. Vision / Web / Reasoning badges sit before the price."),
            HelpCard("Tap to confirm", "Single-select: tapping a row immediately fires onConfirm with the (provider, model) pair and the caller closes the picker. No multi-select, no batch confirm.")
        )
    ),
    "translation_run" to HelpContent(
        title = "Translation run",
        cards = listOf(
            HelpCard("Overview", "Lists every TRANSLATE call inside one run, grouped by translationRunId (or, for legacy rows, by 'lang:<targetLanguage>'). Sorted by timestamp ascending."),
            HelpCard("Header", "Provider / model line up top — every call in the run shares one model, so it's shown once. Tap the line to open Model Info. Below: '<count> calls' and the summed cost in cents (when > 0)."),
            HelpCard("Per-row content", "Status emoji (✅ done, ⏳ blank-content, ❌ errored), source-type label (report / prompt / fan-out / fan-in / meta-prompt-name), then the actual source rebuilt from the original (so the user's Model name layout setting wins over the frozen agentName)."),
            HelpCard("Per-row tap", "Opens TranslationCallDetailScreen with original on top, translation underneath."),
            HelpCard("Restart failed translations", "Bottom-card button — disabled when erroredCount == 0. Re-runs every ❌ row in this run; ✅ rows are left alone."),
            HelpCard("Start missing translations", "Adds rows for every expected source × language pair that isn't already covered by this run. Useful after a partial cancel."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and ApiTracer.getTraceFiles() finds at least one entry tagged category=Translation for this report. Opens the trace list filtered to those calls."),
            HelpCard("Title bar — ℹ️", "Wired when the providerId resolves. Jumps to Model Info for the run's translator model."),
            HelpCard("Title bar — 🗑", "Wired. Confirm dialog shows the count and language; confirming deletes every TRANSLATE row in this run and pops back.")
        )
    ),
    "translation_call" to HelpContent(
        title = "Translation call",
        cards = listOf(
            HelpCard("Overview", "Per-call detail. Title reads 'Translate · <language>'. Source pane on top (capped at half the screen), translation pane fills the rest. Both panes scroll independently."),
            HelpCard("Source label", "Provider / model when the source is an AGENT row or META row; 'Prompt' for PROMPT-source translations; the Meta prompt name as fallback for older META rows."),
            HelpCard("Source resolution", "Driven by translateSourceKind + translateSourceTargetId. PROMPT pulls report.prompt; AGENT pulls the matching agent's responseBody; META pulls the SecondaryResult's content via SecondaryResultStorage.get."),
            HelpCard("Cost line", "Below the title bar when totalCost > 0 — formatted as cents with monospace font."),
            HelpCard("Error rendering", "When the row has errorMessage, a red Error block + the message replaces both panes."),
            HelpCard("Title bar — 🐞", "Wired when tracing is on and a trace exists for (this report id, this row's translation model, closest timestamp)."),
            HelpCard("Title bar — ℹ️", "Wired when the translation provider resolves and result.model is non-blank — jumps to Model Info."),
            HelpCard("Title bar — 🔄 / 🗑", "Not wired here — re-fire / delete from the parent Translation run screen."),
            HelpCard("Tips", "The source label and the translation label are both clickable — they each invoke modelInfoClickable, opening Model Info for whichever model the panel represents.")
        )
    ),
    "translation_compare" to HelpContent(
        title = "Translation compare",
        cards = listOf(
            HelpCard("Overview", "Generic side-by-side viewer for any 'original ↔ translation' pair. Reached from the Translation info button on a translated single-agent result, or on a translated Meta secondary."),
            HelpCard("Layout", "Both panes get equal weight (1f each) — original on top in blue, translation on bottom in green, separated by a 2dp divider."),
            HelpCard("Independent scroll", "Each pane has its own verticalScroll so a long original next to a short translation (or vice versa) doesn't lock you into a shared scroll position."),
            HelpCard("Think sections", "Both panes render via ContentWithThinkSections — <think> blocks collapse so the user-readable content stays prominent."),
            HelpCard("Empty content", "A pane with blank content shows '(no content)' in tertiary text."),
            HelpCard("Title", "Caller-supplied — typically reads 'Translation info — <provider> / <model>' or includes the Meta-prompt name."),
            HelpCard("Title bar", "Only the back arrow is wired."),
            HelpCard("Related", "TranslationCallDetailScreen has the same split layout but caps the original at half-screen — that one is per-call; this one is for whole-document overlays.")
        )
    ),
    "translation_language" to HelpContent(
        title = "Pick target language",
        cards = listOf(
            HelpCard("Overview", "Single-select picker over a curated list of 50+ languages — most-requested by speaker count for the head, alphabetical for the tail."),
            HelpCard("Search", "Filters by English name OR native name (case-insensitive). The ✕ trailing icon clears the field."),
            HelpCard("Per-row content", "English name in white on top, native name in tertiary grey underneath when it differs (e.g. 'Mandarin Chinese' / '中文 (普通话)'). A '>' chevron sits at the right."),
            HelpCard("Tap to confirm", "Single-select — tapping a row fires onConfirm and the caller closes the picker."),
            HelpCard("Pitfalls", "Translate runs against many languages multiply call cost linearly with language count — pick deliberately."),
            HelpCard("Curation", "Not exhaustive. The translation prompt itself can be edited in AI Setup → Prompts → Internal to use a more specific dialect."),
            HelpCard("Tips", "Search for native script directly works — typing '中文' jumps to Mandarin without remembering the English name."),
            HelpCard("Title bar", "Only the back arrow is wired.")
        )
    ),
    "content_view" to HelpContent(
        title = "View report content",
        cards = listOf(
            HelpCard("Overview", "Full-screen viewer for a saved report's content. Three modes share this screen: per-agent body picker (default), prompt-only ('Prompt' title), or the cost table ('Cost summary' title)."),
            HelpCard("Loading state", "Reports are loaded on Dispatchers.IO via produceState — a Loading sentinel keeps the empty-state text from flashing while the JSON parse runs."),
            HelpCard("Language picker row", "When the report has TRANSLATE rows, a FlowRow of buttons (Original + one per distinct targetLanguage) sits below the title bar. Selecting a non-Original key overlays translated content onto the matching agent / prompt rows."),
            HelpCard("Agent buttons", "Per-agent FlowRow built from successful (SUCCESS-status) agents sorted alphabetically. The active row is highlighted purple. Each button rebuilds the label from agent.provider + agent.model so the Model name layout setting wins."),
            HelpCard("Active model header", "Provider — model in blue under the buttons, with a 🐞 next to it when tracing is on and a matching trace exists for (reportId, agent.model, max-by-timestamp)."),
            HelpCard("Body rendering", "ContentWithThinkSections handles <think> collapsibles, citations, related-questions blocks, and search results — so models that emit any of those render structured rather than raw."),
            HelpCard("Cost summary mode", "Shows ReportCostTable when at least one agent or secondary has tokenUsage. Empty state shows '(no usage recorded)'. Costs aggregate translation calls too — language picker is hidden in this mode."),
            HelpCard("Title bar", "Only the back arrow is wired. The screen is reached from the result page's View → Reports / Prompt / Costs popup.")
        )
    ),
    "cost_view" to HelpContent(
        title = "Cost summary",
        cards = listOf(
            HelpCard("Overview", "Read-only cost table for the report — every API call counted against this report (agents + secondaries + translations) gets a row. Reached from the result page's View → Costs button."),
            HelpCard("Per-row breakdown", "Each row shows model, kind ('report' / 'rerank' / 'meta' / 'moderate' / 'translate'), input/output tokens, and a USD subtotal computed against the layered pricing lookup at view-time."),
            HelpCard("Group totals", "Tables aggregate by provider and by model so you can see which provider absorbed most of the spend; both groupings render below the per-row list."),
            HelpCard("Translation costs", "Translation calls are billed against the same model that ran them — they appear as 'translate' kind rows. The language picker is hidden in cost mode since costs aggregate every call."),
            HelpCard("Empty state", "When neither the agents nor any secondary carries a tokenUsage record, the body reads '(no usage recorded)'. This usually means the run was cancelled before the first response landed."),
            HelpCard("Title bar", "Title reads 'Cost summary'. Only the back arrow is wired here."),
            HelpCard("Pitfalls", "Costs use CURRENT pricing — if the provider changed prices since the run, the displayed cost is the today-rate, not the as-billed rate."),
            HelpCard("Related", "View → Reports for per-agent bodies; View → Prompt for the prompt itself; AI Usage (Settings → Statistics) for cumulative spend across all reports.")
        )
    ),
    "knowledge_new" to HelpContent(
        title = "New knowledge base",
        cards = listOf(
            HelpCard("Overview", "Form to create a new knowledge base. The KB binds an embedder model + a chunk strategy at creation time; both are immutable for the KB's lifetime once chunks land."),
            HelpCard("Name field", "Free-form display name. Used as the KB title in pickers and in the chat-attach dialog. Required — Create is disabled until non-blank."),
            HelpCard("Embedder picker", "Pick one provider/model for embeddings. Local embedder (LiteRT MediaPipe) is also offered when a TextEmbedder model is installed. The chosen embedder's output dimension becomes a hard invariant for every chunk in this KB."),
            HelpCard("Chunk strategy", "How source documents get split before embedding. Defaults pick token / character thresholds tuned to the chosen model's input limit; advanced fields let you override."),
            HelpCard("Create", "Creates the manifest under <filesDir>/knowledge/<id>/manifest.json. No sources yet — drill into the new KB and add documents from there."),
            HelpCard("Title bar", "Only the back arrow is wired."),
            HelpCard("Pitfalls", "Picking an embedder you don't actively use here means the cosine retrieval at chat-attach time has to load the embedder runtime — which can be slow and memory-heavy. Prefer your default embedder unless you have a reason."),
            HelpCard("Related", "Once created, the KB Detail screen handles ingest (Add file / paste text / share-target import) and indexing.")
        )
    ),
    "chat_search" to HelpContent(
        title = "Search chats",
        cards = listOf(
            HelpCard("Overview", "Full-text search over saved chat sessions. Reached from the Chat History list's search icon."),
            HelpCard("Search field", "Filters by anything: session title, message content, model, provider name, system prompt. The match is substring + case-insensitive."),
            HelpCard("Result rows", "One row per matching session with a content excerpt around the first match. Tap to resume the session at the matched message."),
            HelpCard("Empty state", "Empty query shows the most recent N sessions; non-matching query shows 'No chats matching <query>'."),
            HelpCard("Title bar", "Only the back arrow is wired here. Help points at this card. The list view's help topic is 'Chat history'."),
            HelpCard("Pitfalls", "Search reads + parses every chat session JSON on every keystroke — a debounce keeps it acceptable on slow storage but heavy histories may still feel jittery. Prefer the list's date / pinned filters when you can."),
            HelpCard("Related", "Chat History (the list) for date-ordered browsing; Manage chats for bulk delete / export.")
        )
    ),
    "models_per_provider" to HelpContent(
        title = "Provider — Models",
        cards = listOf(
            HelpCard("Overview", "Per-provider model list for one provider. Reached from AI Setup → Providers → <provider> → Models. The all-providers Models hub is a different screen."),
            HelpCard("Source picker", "API / Manual chips at the top. API mode pulls models from the provider's catalog endpoint; Manual mode lets you paste / curate a fixed list (one model id per line)."),
            HelpCard("API mode list", "Shows what the last Fetch returned — the same list every model picker uses. Models known to be stale (LiteLLM has fresher metadata) get a tiny badge."),
            HelpCard("Manual mode editor", "Add lines from the multi-line input + Add button; tap a row to drop it back into the editor for tweaking."),
            HelpCard("Auto-save", "Edits land via the Settings save lambda as you go — no separate Save button. The screen drops local mirror state when switching modes so half-typed values don't stick."),
            HelpCard("Title bar", "Static 'Models' title; the green sub-header below names the active provider. Only the back arrow is wired here.")
        )
    ),
    "prompt_view" to HelpContent(
        title = "Prompt view",
        cards = listOf(
            HelpCard("Overview", "Read-only viewer for the report's prompt as it was actually saved. Reached from the result page's View → Prompt button."),
            HelpCard("Translated prompt", "When the report has a TRANSLATE row whose translateSourceKind is PROMPT, the language picker at the top lets you flip between the original and the translated body."),
            HelpCard("Empty state", "When report.prompt is blank, the screen shows '(no prompt recorded)' in tertiary grey."),
            HelpCard("Layout", "Single column with verticalScroll — long prompts scroll naturally."),
            HelpCard("Use it for", "Verifying what the model actually saw when results look surprising — variables and any user-tag block from <user>...</user> append are visible."),
            HelpCard("Title bar", "Title reads 'Prompt'. Only the back arrow is wired here."),
            HelpCard("Pitfalls", "The screen renders the saved prompt — if you Edit prompt and don't Regenerate, the new prompt shows here but agents weren't re-run with it. The result page's pending-changes banner reminds you."),
            HelpCard("Related", "View → Reports for the per-agent body picker; View → Costs for the cost table.")
        )
    ),
    "history" to HelpContent(
        title = "History",
        cards = listOf(
            HelpCard("Overview", "All saved reports, newest-first. Re-fetches on every ON_RESUME so coming back from a delete / regenerate shows the updated list."),
            HelpCard("Search card", "Toggle expands to three independent fields: Title, Prompt, Response. Each narrows the list further (logical AND). 'Search (active)' label appears on the toggle when any field is non-blank."),
            HelpCard("Pagination", "Auto-sized to the screen — pageSize derived from maxHeight and a 56dp row height. < Prev / Next > controls when totalPages > 1."),
            HelpCard("Per-row content", "Title (truncated) on the left, MM/dd HH:mm date on the right. Per-row 🐞 (when tracing is on AND ApiTracer has any entries for this reportId) opens the trace list filtered to that report."),
            HelpCard("Per-row delete", "Each row has a ✕ that opens a confirm dialog. Confirming deletes the report on Dispatchers.IO and re-loads the list."),
            HelpCard("Title bar — 🗑", "Wired when allReports is non-empty. Confirm dialog shows the count; confirming calls ReportStorage.deleteAllReports and clears the local list."),
            HelpCard("Title bar — others", "ℹ️ / 🔄 / 🐞 not wired at the list level (those are per-row)."),
            HelpCard("Pitfalls", "Deleting a report cascades — its secondaries (Translate / Meta / Rerank / Moderate) and any trace files for that reportId also go.")
        )
    ),
    "prompt_history" to HelpContent(
        title = "Prompt History",
        cards = listOf(
            HelpCard("Overview", "The last 100 unique (title, prompt) pairs you sent to a report, newest-first. Tap a row to open New Report seeded with that title and prompt."),
            HelpCard("Search field", "Single field that filters by title OR prompt (case-insensitive)."),
            HelpCard("Pagination", "Auto-sized — pageSize derived from screen height and a 56dp row. < Prev / Next > visible when more than one page."),
            HelpCard("Per-row content", "Title in white on the left, MM/dd HH:mm timestamp on the right."),
            HelpCard("Clear History", "Bottom red button — wipes the persisted prompt history and resets the list. Disabled when the list is already empty."),
            HelpCard("Deduplication", "Re-running the exact same (title, prompt) pair just bumps the timestamp; the list never grows past 100 entries."),
            HelpCard("Pitfalls", "Prompt history is independent from Report History — clearing it leaves your saved reports untouched and vice versa."),
            HelpCard("Title bar", "Only the back arrow is wired.")
        )
    )
,
    "chat_hub" to HelpContent(
        title = "AI Chat",
        cards = listOf(
            HelpCard("Overview", "Landing screen for everything chat-shaped. Top section starts a new conversation; below it, pinned and recent sessions plus tools to continue, search, or manage existing chats."),
            HelpCard("Unfinished pill", "When at least one chat ended on a user turn with no assistant reply (you navigated away mid-stream), an envelope pill appears at the very top with a Resume link to the most recent such session."),
            HelpCard("Start card", "Four entry points stacked in one card: New Chat with Agent (greyed when no agent has a key + active provider), New Chat – Configure On The Fly (pick provider/model/parameters at start), Dual AI Chat (two models trade turns), and Start with photo (camera capture, image rides into the first user turn)."),
            HelpCard("Continue Existing Chat", "Opens the full chat-history list. Disabled and dimmed when no sessions exist yet. Pinned + Recent below give you a faster jump for the top sessions."),
            HelpCard("Chat with a local LLM", "Only shown when at least one .task model is installed in filesDir/local_llms/. Tapping with one model installed jumps straight to the session; with two or more, a dropdown appears so you pick which to load."),
            HelpCard("Pinned and Recent cards", "Pinned holds every session you marked with the pin chip; Recent shows the next three by updated time. Each row is the first user-message preview; tap to resume."),
            HelpCard("Search Chats", "Free-text search across every saved message — opens the dedicated search screen."),
            HelpCard("Manage", "Bulk-prune by age and zip-export of every chat-history JSON. Disabled when no chats exist."),
            HelpCard("Title-bar icons", "Only the always-on Help (?) and Home icons. No reload, info, delete, or trace from this hub — those live on the per-session screens."),
            HelpCard("Tips", "The hub re-reads chat history on every resume tick, so deleting / pinning a session elsewhere shows up when you come back. The camera capture clears any previous photo error before launching.")
        )
    ),
    "chat_session" to HelpContent(
        title = "Chat",
        cards = listOf(
            HelpCard("Overview", "Live single-model conversation. Messages stream chunk-by-chunk; the input box clears the moment Send fires; the assistant bubble bottom-anchors so short conversations sit just above the input row instead of pinned at the top."),
            HelpCard("Header row", "Provider / model label is clickable and opens Model Info. To its right: a Knowledge chip (only when at least one KB exists) and a Pin chip. The pin state is written to the session record immediately, so the hub reflects it without waiting for the next message save."),
            HelpCard("Knowledge chip", "Multi-select dialog over saved KBs. Once one KB is checked, every other KB whose embedder differs becomes greyed out — the retriever embeds the query with the first attached KB's embedder and would silently drop the rest. Clearing the selection re-enables every row."),
            HelpCard("Web search chip", "Per-turn 🌐 toggle. When on, an OR with the Parameters preset's searchEnabled drives the request, and the LiteLLM tool-use overhead (~3-4k extra system tokens for Claude with web_search) is folded into the cost estimate."),
            HelpCard("Reasoning chip", "Only shown when LiteLLM, models.dev, or the model id family (o1/o3/o4/gpt-5, anything with thinking/reasoning in the name) marks the model as supporting it. Levels come from the provider's self-reported capabilities when available, otherwise the legacy low/medium/high set."),
            HelpCard("Validate input chip", "Tap once to pick a moderation model; while set, every Send first runs the input through callModerationApi. A clean classification proceeds silently. A flagged result pops a Proceed-anyway / Cancel dialog with the fired categories. API errors fail-open: the message is still sent and the orange Moderation: error line is shown."),
            HelpCard("Attach + send", "📎 opens the SAF picker for an image; the thumbnail + mime type appears above the input row with a Remove button. A red warning fires when the model isn't flagged vision-capable. Send is disabled while streaming, while moderation runs, and when the input is empty without an image."),
            HelpCard("Trace icons", "Title bar's ℹ︎ jumps to Model Info. Each finished assistant bubble carries a 🐞 that opens the matching trace (closest timestamp, same model, no reportId). The flagged-input dialog also shows a 🐞 when a moderation trace was captured. All trace icons are suppressed when API tracing is off in Settings."),
            HelpCard("Cost meter", "Live total in cents shown next to the Back button after the first turn — running sum of input tokens × promptPrice + output tokens × completionPrice for this session."),
            HelpCard("Pitfalls", "Cancellation on back-press deliberately doesn't append a [Stream interrupted] line — the partial chunks aren't an error from your perspective. Real exceptions during streaming do append the partial response with the error message. System-prompt changes from Parameters now apply mid-session — the previous flow only seeded on an empty messages list.")
        )
    ),
    "chat_parameters" to HelpContent(
        title = "Chat Parameters",
        cards = listOf(
            HelpCard("Overview", "Pre-flight setup screen for a configure-on-the-fly chat. Pick optional presets, optionally override individual fields, then Start Chat hands the resolved ChatParameters to the session screen."),
            HelpCard("Provider/model line", "Read-only label under the title bar. Clickable — taps open Model Info for the picked (provider, model)."),
            HelpCard("System Prompt button", "Opens a single-select dialog over Settings.systemPrompts. Picking one fills the System prompt text field; typing in the field clears the selection so the manual text wins."),
            HelpCard("Parameters button", "Multi-select over Settings.parameters presets. Selected presets are merged via Settings.mergeParameters into a single ChatParameters; manual fields below override per-field."),
            HelpCard("Per-field overrides", "Temperature, Max tokens, Top P, Top K, Frequency penalty, Presence penalty — each takes a free-form number. Empty falls back to the merged preset value, or null if no preset is set. Invalid input also falls back."),
            HelpCard("Citations + recency", "Return citations defaults on; Search recency takes day / week / month / year. Web search itself moved to a per-turn 🌐 chip on the session screen — the preset's searchEnabled is OR'd with the chip at send time."),
            HelpCard("Start Chat", "Builds the ChatParameters with the resolved system prompt, then navigates to the session. Note: 🌐 doesn't exist here on purpose — flip it from inside the chat."),
            HelpCard("Tips", "Both selector buttons turn purple-tinted when set, so you can see at a glance whether a preset is active. Selecting a system prompt also dumps its text into the editable field, so you can preview/edit it before starting."),
            HelpCard("Pitfalls", "Edits to Settings.parameters elsewhere don't migrate into an already-running session — they're resolved once at Start Chat. Restart the chat to pull new preset values.")
        )
    ),
    "chat_history" to HelpContent(
        title = "Chat History",
        cards = listOf(
            HelpCard("Overview", "Paged list of every saved chat session. Each row shows first user-message preview, provider · model, and last-updated date. Tap a row to resume that session."),
            HelpCard("Pagination", "Page size auto-fits the screen height (rows of ~80dp). Previous / Next buttons sit above and below the list with the current page indicator and total chat count between them."),
            HelpCard("Trace icon per row", "🐞 appears between the row and the > caret when at least one chat-turn trace was tagged with this sessionId. Tap it to open the full trace list filtered to this session."),
            HelpCard("Empty state", "When ChatHistoryManager has no sessions, the screen renders \"No chat history yet\" centered — the Continue Existing Chat card on the hub is also disabled in this state."),
            HelpCard("Resume behaviour", "Tapping a row navigates to AI_CHAT_CONTINUE/{sessionId}, which reopens the same ChatSessionScreen with the stored messages, parameters, attached KBs, pinned flag, and persisted web-search / reasoning toggle."),
            HelpCard("Title-bar icons", "Help and Home only. Bulk delete is on the dedicated Manage screen reachable from the hub, not from here."),
            HelpCard("Tips", "The list re-fetches whenever ChatHistoryManager.historyVersion ticks (after a save / delete / pin elsewhere). Page index is rememberSaveable so it survives a config change."),
            HelpCard("Pitfalls", "The trace probe runs once per row — if you record a new trace for an open session, the icon doesn't appear until you leave and return."),
            HelpCard("Related", "Use Search Chats from the hub to find a specific message; use Manage chats for bulk delete by age or full export.")
        )
    ),
    "chat_continue" to HelpContent(
        title = "Chat",
        cards = listOf(
            HelpCard("Overview", "Same screen as Chat session, but seeded with the stored messages, parameters, knowledge attachments, pinned flag, and persisted web-search / reasoning effort from the saved session record."),
            HelpCard("State you keep", "ChatHistoryManager.loadSession brings back: every message (system prompt repinned in place if Parameters changed), the original ChatParameters, the per-session knowledgeBaseIds, and the pinned flag."),
            HelpCard("Toggles you can flip", "Web search and reasoning effort chips are read from the persisted ChatParameters and are saved back on every turn — the next save uses your current chip state, not the original preset."),
            HelpCard("System prompt update", "If the underlying Settings system-prompt template changed since you last opened the session, the system message is rewritten in-place on the next turn so the new prompt takes effect."),
            HelpCard("Session id", "AI_CHAT_CONTINUE/{sessionId} carries the id; the screen treats it as the current session id so saves overwrite the same record. New messages append to the existing JSON."),
            HelpCard("Title-bar icons", "Same as a fresh chat — ℹ︎ jumps to Model Info, Help and Home are always present. The Back button shows the running cost-in-cents for this session next to it."),
            HelpCard("Tips", "Trace icons on existing assistant bubbles probe the on-disk trace store by closest timestamp + same model + no reportId; old assistant turns from before tracing was on still have no icon."),
            HelpCard("Pitfalls", "Switching the underlying model is not supported mid-session — start a new chat instead. Editing a Parameters preset elsewhere doesn't migrate into the resumed session because the persisted ChatParameters record was built at Start Chat time."),
            HelpCard("Related", "Use the Pin chip to keep a frequently-resumed session at the top of the hub. Use Continue in chat from a Report to start a fresh chat that inherits a multi-agent run.")
        )
    ),
    "chat_manage" to HelpContent(
        title = "Manage chats",
        cards = listOf(
            HelpCard("Overview", "Two housekeeping actions for chat history: bulk-delete sessions older than N days, and zip-export every chat-history JSON for backup or sharing."),
            HelpCard("Delete old chats card", "Number-only field for the cutoff (max 4 digits). Pinned chats are skipped; the helper line under the field reminds you. Defaults to 30 days; the Delete button is enabled only when the value parses to a positive integer."),
            HelpCard("Confirm dialog", "Loads matching sessions off the UI thread and shows the actual count: \"Delete N chats?\". Title displays \"Loading…\" briefly while the scan runs (long histories can take a moment)."),
            HelpCard("Export all card", "Zips every file in filesDir/chat-history/ into a timestamped archive (ai_chats_backup_YYMMDD_HHMMSS.zip) under cacheDir, then opens the system share sheet via FileProvider. Status line below shows the chat count once bundled."),
            HelpCard("Status line", "After any operation, a single status string at the bottom: \"Zipping chats…\" / \"Bundled N chats.\" / \"Deleted N chats.\" / \"Nothing to export.\""),
            HelpCard("Title-bar icons", "Help and Home only — no per-screen reload, info, delete, or trace icons here."),
            HelpCard("Tips", "The Working… button label tells you when an export is in flight; the Delete button is disabled at the same time, so you can't kick off a parallel scan."),
            HelpCard("Pitfalls", "Pinned chats are excluded from the bulk delete — to remove a pinned chat you have to unpin it from inside the session first. Deletes are immediate and cannot be undone."),
            HelpCard("Related", "Whole-app backup including chats lives under AI Setup → Backup & restore. Use Search Chats from the hub if you only want to find an old message rather than prune.")
        )
    ),
    "dual_chat_setup" to HelpContent(
        title = "Dual AI Chat",
        cards = listOf(
            HelpCard("Overview", "Configures two models that take turns chatting about a subject. State is persisted to a dedicated SharedPreferences (dual_chat_prefs) so reopening the screen restores your last configuration."),
            HelpCard("Model 1 card (blue)", "Tap the model button to drill into the active-providers picker, then the model picker. System Prompt and Parameters preset buttons sit below — both turn purple-tinted when set."),
            HelpCard("Swap button", "Center row, ⬅ Swap ➡. Swaps Model 1 and Model 2 wholesale (provider, model, parameters preset, system prompt) — useful when you realize you wanted them in the other order."),
            HelpCard("Model 2 card (green)", "Same controls as Model 1 in a different colour so the two sides stay visually distinct."),
            HelpCard("Subject + Rounds", "Subject is the topic both models will talk about. Rounds caps the conversation; the default is 10. Cost grows roughly linearly with rounds × per-turn tokens."),
            HelpCard("Prompt templates", "1st prompt seeds round one and supports %subject%. 2nd prompt fires from Model 2 in round one and supports %answer% (Model 1's reply). From round three onward, the previous response is forwarded directly with no template wrapping."),
            HelpCard("Go button", "Saves the prefs blob, resolves both Parameters preset chains, snapshots both system prompts to text, and starts the session. Disabled until both models, both names, the subject, and a positive Rounds value are set."),
            HelpCard("Title-bar icons", "Help and Home only — provider / model selection happens via full-screen overlays, not via title-bar icons."),
            HelpCard("Tips", "DisposableEffect saves your prefs on screen exit, so back-navigating mid-edit doesn't lose work. Model and provider selection share the same overlay screens used elsewhere — rich pricing columns and badges included.")
        )
    ),
    "dual_chat_session" to HelpContent(
        title = "Dual Chat",
        cards = listOf(
            HelpCard("Overview", "Both models alternate turns automatically until the round budget is hit. Bubbles align left for Model 1 (blue) and right for Model 2 (green). The screen drives a single coroutine job — leaving the screen cancels it via DisposableEffect."),
            HelpCard("Cost row", "Three-column running tally just under the title bar: Model 1, Model 2, Total cost in cents. Recomputed via derivedStateOf from per-side input/output token sums × per-side pricing."),
            HelpCard("Progress line", "Below the cost row: \"Interaction X / N — Subject: …\". X bumps after both models replied for that round."),
            HelpCard("Thinking pill", "While a model is in flight, a small \"Model N is thinking…\" pill renders aligned to that side. Replaced by the actual reply once received."),
            HelpCard("Stop button", "Fires while running — cancels the chat job. The job's withTracerTags finally restores the previous tracer tag pair on its way out, so no manual cleanup is needed."),
            HelpCard("Continue more", "After Stop or after the round budget is reached, an Extra chats field + \"Chat N more\" button appears. The new total is currentInteraction + N; the loop resumes from where it stopped."),
            HelpCard("Title-bar icons", "ℹ︎ pops a two-row picker (\"Provider — model\" for each side); tapping a row jumps to that model's Model Info. Help and Home are always-on."),
            HelpCard("Per-bubble 🐞", "Each bubble's ladybug opens the trace tagged with this session id and the bubble's model, with the closest timestamp — same model speaking again gets a different trace. Suppressed entirely when API tracing is off in Settings."),
            HelpCard("Tips", "Provider / model labels in each bubble are click-targets for Model Info too. The session id is prefixed with dualchat_ + start time so traces from this run are easy to find."),
            HelpCard("Pitfalls", "If either provider has no API key configured the call will error and the loop stops. Errors render in red below the message list and the run flips to the stopped state.")
        )
    ),
    "knowledge_list" to HelpContent(
        title = "AI Knowledge",
        cards = listOf(
            HelpCard("Overview", "Lists every saved knowledge base. KBs are RAG corpora that can be attached to a Report or Chat session to inject relevant excerpts before each call. The header line is the one-paragraph reminder of what KBs are for."),
            HelpCard("Shared-content banner", "When you arrive here from the share-target chooser with files / URLs queued, a green sticky banner counts the pending items and explains: pick an existing KB to ingest there, or create a new one. \"Discard share\" abandons the queue."),
            HelpCard("+ New knowledge base", "Green button opens the wizard — name + embedder picker. Embedder choices fold local TextEmbedder .tflite files in filesDir/local_models/ together with every (provider, model) marked EMBEDDING from active providers."),
            HelpCard("KB cards", "Each card shows the KB name, embedder line (\"Local · model\" or \"Provider · model\"), and \"N sources · M chunks\". Tap to drill into the detail screen."),
            HelpCard("Empty state", "When KnowledgeStore lists nothing: \"No knowledge bases yet.\" Tap + New knowledge base to create one."),
            HelpCard("Title-bar icons", "Help and Home only on the list and on the new-KB wizard. Per-KB delete is on the detail screen."),
            HelpCard("Tips", "List re-keys on each ON_RESUME tick — re-indexing or deleting a KB elsewhere shows up when you return. The wizard's empty embedder list points you at Housekeeping → Local LiteRT models or AI Setup → Manual model types overrides."),
            HelpCard("Pitfalls", "Embedder is fixed for the lifetime of the KB — there is no migration path. If you change your mind, create a new KB and re-ingest."),
            HelpCard("Related", "Multi-select KB attach lives on the chat session header (📚) and on the New Report screen. The retriever uses the first attached KB's embedder; mismatched embedders are skipped silently.")
        )
    ),
    "knowledge_detail" to HelpContent(
        title = "Knowledge base",
        cards = listOf(
            HelpCard("Overview", "Per-KB workspace. Add file or URL sources, see the chunk count, re-index a single source, or delete the whole KB. Indexing runs in the screen's coroutine scope so the status line updates live as the import happens."),
            HelpCard("Header", "Title is the KB name. Below: embedder line (provider · model, monospace) and \"N sources · M chunks\". The trash icon in the title bar deletes the entire KB after a confirm dialog."),
            HelpCard("+ File", "Opens the SAF picker scoped to PDF, plain/markdown text, DOCX, ODT, XLSX, ODS, CSV/TSV, and JPG/PNG. File type is detected by extension first, MIME second, defaulting to TEXT."),
            HelpCard("+ Web page", "URL input field below the file row. Trim + non-blank gates the button. KnowledgeService.indexUrl runs in IO, posts progress to status, and disables the buttons while it works."),
            HelpCard("Status line", "Reflects the current step: \"Reading X…\" / \"Fetching X…\" / \"Indexed name (N chunks)\" / \"Failed: …\" — and per-batch progress messages from the embedder for chunked sources."),
            HelpCard("Sources list", "Each row shows source name, type, chunk count, and any error (red). Per-row Re-index re-runs the same extractor; per-row Delete drops the source via KnowledgeStore.deleteSource and refreshes the list."),
            HelpCard("Auto-ingest from share", "When pendingUris arrives from the share-target queue, the screen auto-imports each item once the KB has loaded — http(s) URLs go through indexUrl, content:// URIs go through pickTypeForUri + indexFile. The queue is cleared via onConsumePending so a back-and-forward doesn't re-import."),
            HelpCard("Title-bar icons", "🗑 deletes the whole KB (confirm dialog: \"Removes the manifest, every source, and every chunk. Cannot be undone.\"). Help and Home are always present."),
            HelpCard("Tips", "URL input clears itself on a successful indexUrl. Buttons disable while a working flag is set so you can't queue parallel imports."),
            HelpCard("Pitfalls", "Re-index hits the same upstream — for a flaky URL it may fail again. The error message is preserved on the source row in red until you re-index successfully or delete it.")
        )
    ),
    "models_search" to HelpContent(
        title = "Models",
        cards = listOf(
            HelpCard("Overview", "Aggregated browser across every active provider's model list. Used for catalog inspection and as a one-shot picker for Swarm edits and the configure-on-the-fly chat entry."),
            HelpCard("Type + Provider dropdowns", "Type filters by ModelType (chat, embedding, reranker, moderation, etc.). Provider filters to one of the active services with a (count) suffix per row. \"All …\" highlighted in blue when nothing is selected."),
            HelpCard("Min context dropdown", "Tiers: Any, ≥ 8k, 32k, 128k, 200k, 1M. Lookup goes provider /models first, then models.dev. Models with no context length anywhere are dropped from the list (they can't be qualified)."),
            HelpCard("Capability chips", "👁 Vision, 🌐 Web search, 💲 Has pricing (real source, not the 25/75 default), 🎁 Free only (real source + 0 input + 0 output), ⚠ Default 25/75 (no real pricing — useful for finding entries that need a curated source), ⚡ Conflicting pricing (2+ catalog tiers disagree by more than 1%)."),
            HelpCard("Search box", "Plain substring across provider name + model name. Case-insensitive. Combines with every other filter."),
            HelpCard("Result rows", "Model name with badges (vision, web, reasoning), provider name in blue, and the per-million-token pricing in red (real source) or grey (default tier)."),
            HelpCard("Tap behaviour", "Default mode: navigate to Model Info for that pair. Pick mode (when caller passed onPickModel): the click invokes the callback and dismisses — title becomes \"Select Model\" and Model Info is bypassed."),
            HelpCard("Title-bar icons", "Help and Home only. Loading spinner inline shows when at least one provider is mid-fetch (loadingModelsFor non-empty)."),
            HelpCard("Tips", "Filters are rememberSaveable, so flipping into a model and back doesn't reset them. Use ⚠ Default 25/75 + a known-large provider as a quick way to find catalog gaps to file."),
            HelpCard("Pitfalls", "Models live on Settings.models per-provider — a provider that hasn't been refreshed yet shows only its default model. Refresh per-provider from the per-provider settings screen.")
        )
    ),
    "model_info" to HelpContent(
        title = "Model Info",
        cards = listOf(
            HelpCard("Overview", "Per-model dossier: name, recent usage, capability summary, layered cost breakdown, raw catalog data from seven sources, and an opt-in \"introduce yourself\" call against the model itself."),
            HelpCard("Header card", "Model name + provider; trailing 🐞 (when traces exist for this model) opens the trace list filtered to it. Provider name itself is clickable and jumps to the per-provider edit screen."),
            HelpCard("Last usage card", "Last 10 hits across chat sessions, reports, and per-report secondaries (translate / meta / rerank / moderate). Each row links back to its source. A cumulative AI Usage row appears at the bottom when one-shot test calls or model refreshes bumped the counter without persisting a session."),
            HelpCard("Actions", "Start AI Chat (opens a fresh chat with this provider/model selected) and Create AI Agent (opens AgentEditScreen pre-filled with provider/model/api key + a default name)."),
            HelpCard("Sources buttons", "Two rows of catalog buttons — HuggingFace, OpenRouter, LiteLLM, models.dev, then Helicone, llm-prices, Artificial Analysis. Green when the source has data for this model, red when it doesn't. Tapping opens the pretty-printed JSON. \"Show all\" concatenates every source into one dump."),
            HelpCard("Costs card", "$/M token rows for each tier that has data, in the same precedence order as PricingCache.getPricing. \"Per 1k searches\" sub-rows surface per-query rerank pricing when present. \"Add manual cost override\" jumps into the override CRUD pre-filled."),
            HelpCard("Capabilities card", "Vision, Web search, Thinking, optional PDF input, optional deprecation banner with a replacement model when the provider self-reports one, plus default temperature / stop sequences. Each row shows the resolution source: Pinned, Provider /models, LiteLLM, models.dev, or Auto-detected from name (last-resort)."),
            HelpCard("API Traces card", "Total trace count for this model — clickable when non-zero, opens the model-filtered trace list."),
            HelpCard("AI Usage card", "Cumulative call count + input/output tokens + cost. Empty placeholder when the counter is still zero."),
            HelpCard("AI Introduction", "Opt-in: \"Ask the model to introduce itself\" button uses the internal Model info prompt template + this (provider, model) and runs it through repository.analyzePlayerWithAgent. Result is cached in PromptCache so a previously-completed answer reappears immediately on revisit."),
            HelpCard("Tips", "OpenRouter id matching normalises '.' and '-' to handle anthropic/claude-opus-4.6 vs claude-opus-4-6 mismatches. HuggingFace lookups try every dash/dot variant of the candidate path and cache the result (including misses) for a week.")
        )
    ),
    "model_picker" to HelpContent(
        title = "Select Provider / Model / Agent",
        cards = listOf(
            HelpCard("Overview", "Three full-screen pickers share this help topic: Select Provider, Select Model, and Select Agent. Same layout — search box, count line, scrollable list — different content."),
            HelpCard("Select Provider", "Lists every AppService (or only active services when activeOnly is set). Each row shows the display name plus a small state emoji: 🔑 ok, ❌ error, 💤 inactive, ⭕ untested. Tap to confirm; back exits without choosing."),
            HelpCard("Select Model", "After picking a provider. Pricing columns on the right (In $/M, Out $/M) read from settings overrides first, then PricingCache. Vision / web / reasoning badges sit between the model name and the price columns."),
            HelpCard("Initial refresh", "For API-mode providers with onRefresh wired, the screen kicks a fetch on entry, waits up to 15 s for it to complete, then reveals the list. Stalled fetches unveil whatever was previously cached so you're never stuck staring at a spinner."),
            HelpCard("Default option", "When showDefaultOption is on (per-provider settings reuse), the list starts with a \"Default (use provider setting)\" row that selects the empty-string sentinel."),
            HelpCard("Open Models button", "Visible when onNavigateToProviderModels was wired (typically inside provider edit). Jumps into the rich Models browser with this provider preselected."),
            HelpCard("Select Agent", "Reads aiSettings.agents directly. Search matches name, provider name, or effective model. Each row shows agent name + provider/model line + per-million-token pricing."),
            HelpCard("Fetch error row", "When the provider's last fetch failed, a red error line appears under the search box with a 🐞 link to the captured trace (when API tracing is on)."),
            HelpCard("Title-bar icons", "Help and Home only on every variant. Refresh is automatic; manual refresh is on the per-provider settings screen."),
            HelpCard("Tips", "Local LLM models come from filesDir/local_llms/ via LocalLlm.availableLlms — the synthetic LOCAL provider's model list isn't stored in ProviderConfig.models."),
            HelpCard("Pitfalls", "Inactive providers are hidden when activeOnly is set (the chat / dual-chat flows). To pick from an inactive provider you have to activate it first in AI Setup.")
        )
    ),
    "model_raw" to HelpContent(
        title = "Raw catalog data",
        cards = listOf(
            HelpCard("Overview", "Pretty-printed JSON view of one of the seven catalog sources for a single model. Reached from the Sources buttons on Model Info."),
            HelpCard("Title", "\"Source · model-name\" — e.g. \"OpenRouter · claude-opus-4.6\". \"All sources · model\" when reached via Show all."),
            HelpCard("Layout", "Single full-screen card containing scrollable JSON. Both vertical and horizontal scrolling — long lines aren't wrapped or truncated. Monospace font keeps the structure readable."),
            HelpCard("JSON colouring", "Keys blue, strings green, numbers orange, true/false purple, null grey, punctuation white. Falls back to plain white for non-JSON inputs (e.g. a \"(no data)\" placeholder)."),
            HelpCard("Show all", "When opened from the Show all button, the body concatenates every source's raw JSON with === Source === headers between sections. Saves you tapping seven buttons in turn while comparing."),
            HelpCard("Title-bar icons", "Help and Home only. Back returns to Model Info; the source button there stays green/red so you can tell at a glance which sources had hits."),
            HelpCard("Tips", "The JSON is pre-pretty-printed via createAppGson(prettyPrint = true). Field-name colouring relies on a string being followed by ':' after whitespace, which means escape sequences inside strings won't accidentally re-tokenise."),
            HelpCard("Related", "Capability and pricing rows on Model Info already distill the most useful fields out of these dumps — only dive into the raw view when something looks off or you want to file a catalog issue.")
        )
    ),
    "search_local" to HelpContent(
        title = "Extended local search",
        cards = listOf(
            HelpCard("Overview", "On-device keyword search across saved reports. The query is split on whitespace; each token is matched case-insensitively against title + prompt + every successful agent's response body. Score = total token occurrences."),
            HelpCard("Query field", "Multi-line, up to 3 lines. Whitespace-tokenised — multi-word queries become AND-of-substrings with score summed. No regex."),
            HelpCard("Search button", "Purple. Disabled until the query is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "Single line under the button: \"Searching…\", \"No matches.\", or \"N results\"."),
            HelpCard("Result rows", "Title (white, bold), date (yyyy-MM-dd HH:mm), and the integer score on the right in blue. Tap to open the report. Top 25 only — sorted by score desc, then timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only. No trace icon — every byte stays on-device, there's nothing to record."),
            HelpCard("Tips", "Search runs entirely on the device — no API calls, no key required. Useful even when offline."),
            HelpCard("Pitfalls", "Tokens are matched as substrings, so very short tokens (\"ai\", \"the\") will inflate scores via incidental matches. Use longer or more distinctive terms when you need precision."),
            HelpCard("Related", "Use Quick local search for a single-substring filter (no scoring), or Semantic search / Local semantic search for embedding-based similarity instead of keyword matching.")
        )
    ),
    "search_semantic" to HelpContent(
        title = "Semantic search",
        cards = listOf(
            HelpCard("Overview", "Embedding-based similarity search across saved reports. The user picks an embedding-typed model from any active OpenAI-compatible provider; query and reports are embedded, scored by cosine, top 10 returned."),
            HelpCard("Empty state", "When no provider has an embedding-typed model, an inline panel points you at AI Setup → Manual model types overrides or fetching a provider whose list contains text-embedding-3-small."),
            HelpCard("Model picker", "Dropdown lists every (active OpenAI-compatible service, model marked EMBEDDING) pair. Label uses the project's \"Model name layout\" setting via modelLabel."),
            HelpCard("Query field", "Up to 3 lines, multi-line. Submitted whole — not tokenised. The text becomes a single embedding vector compared against report vectors."),
            HelpCard("Search behaviour", "Embeds the query first; then walks every report, building a representative text from title + prompt + first 2k characters of the first non-blank agent response. Cached vectors (keyed on doc id, provider, model, content hash) are reused; new ones are batched in groups of 50."),
            HelpCard("Status line", "Live progress: \"Indexing reports… i / N\" while scanning, then \"Embedding batch X / Y (Z reports)\" while sending. Final state is the result count or \"No matches.\""),
            HelpCard("Result rows", "Title, timestamp, and the cosine score (3 decimals) in blue. Top 10, sorted descending; rows with score ≤ 0 are dropped."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Edit a report and a fresh content hash means the next run re-embeds it automatically — caching is correct across edits, not just identity. The 50-per-batch limit fits all observed providers."),
            HelpCard("Pitfalls", "Costs scale with report count on first run for a new model; subsequent runs hit the cache. Switching embedding model invalidates the cache for that model only — vectors from the old model are still on disk and reused if you switch back."),
            HelpCard("Related", "Local semantic search uses an on-device .tflite encoder for the same workflow with no network calls and no per-call cost.")
        )
    ),
    "search_quick" to HelpContent(
        title = "Quick local search",
        cards = listOf(
            HelpCard("Overview", "The cheapest of the search variants — single substring match (case-insensitive) against report prompt and every successful agent response. No tokenisation, no scoring; a report is either a hit or it isn't. Results sorted by recency."),
            HelpCard("Word field", "Single-line input labelled Word. Used as one substring — short whitespace phrases work as a single literal."),
            HelpCard("Search button", "Purple. Disabled until the field is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "\"Searching…\", \"No matches.\", or \"N results\". No score is shown — every hit is binary."),
            HelpCard("Result rows", "Title and timestamp only. Tap to open the report. Sorted by timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Faster than Extended local search for narrow queries — no per-token scoring loop, no top-N truncation. Returns every hit."),
            HelpCard("Pitfalls", "Title is not searched (only prompt + responses). For a title query, use Extended local search instead."),
            HelpCard("Related", "Extended local search adds whitespace tokenisation and an occurrence score; semantic searches do similarity matching beyond literal substring.")
        )
    ),
    "search_local_semantic" to HelpContent(
        title = "Local semantic search",
        cards = listOf(
            HelpCard("Overview", "Semantic search using an on-device LiteRT (MediaPipe Tasks Text Embedder) model. Same workflow as the cloud Semantic search but every embedding stays on the phone."),
            HelpCard("Empty state", "When no .tflite is installed in filesDir/local_models/, the inline panel points you at AI Housekeeping → Local LiteRT models — that screen handles download / import / remove."),
            HelpCard("Model picker", "Dropdown over availableModels — whatever .tflite files live in filesDir/local_models/. Pre-selected to the first one on entry."),
            HelpCard("Query + Search", "Multi-line query, purple Search button, same disabled gating as the other search screens. The button label flips to \"Searching…\" during a run."),
            HelpCard("Status line + 🐞", "Live status: \"Indexing reports… i / N\" then \"Embedding batch X / Y (Z reports)\". After a run completes, a 🐞 next to the status line opens the most recent \"Local semantic search\" trace (synthetic, hostname \"local\")."),
            HelpCard("Caching", "Embeddings persist via EmbeddingsStore keyed on report id + provider \"LOCAL\" + model name + content hash. Re-runs hit the cache; edited reports re-embed automatically."),
            HelpCard("Result rows", "Title, timestamp, cosine score (3 decimals) in blue. Top 10, scores > 0, sorted desc."),
            HelpCard("Title-bar icons", "Help and Home only — the 🐞 lives next to the status line, not in the title bar."),
            HelpCard("Tips", "ApiTracer writes a synthetic trace entry tagged \"Local semantic search\" so the Trace screen lights up the same way it does for HTTP-backed embedders. The MediaPipe TextEmbedder embeds one string per call internally — \"batch of 50\" is just to reduce the trace count."),
            HelpCard("Pitfalls", "Switching to a different .tflite invalidates the cache for that model — first run after a switch re-embeds every report."),
            HelpCard("Related", "Cloud Semantic search does the same job using a remote provider's embedding endpoint. Quick local / Extended local searches do non-semantic substring matching.")
        )
    ),
    "share_target" to HelpContent(
        title = "Send to AI",
        cards = listOf(
            HelpCard("Overview", "Lightweight chooser shown when another app shares content into this app via ACTION_SEND. Lives between the receiving Activity and the standard nav graph; a card tap clears the share state and routes the payload."),
            HelpCard("Preview card", "Top of the screen: shared subject (when present and non-blank), first 300 characters of shared text (with ellipsis when truncated), \"N attachments\" line for any URIs, and the raw mime type. Lets you double-check the payload before picking a destination."),
            HelpCard("New Report card", "📝. Routes to the New Report flow — text becomes the prompt, the first image attaches for vision, non-image files queue for one-tap auto-attach as a knowledge base. Greyed when there's neither text nor URIs."),
            HelpCard("New Chat card", "💬. Opens a fresh chat session with the shared text staged as the first user turn. Greyed when no text was shared."),
            HelpCard("Add to Knowledge card", "📚. Routes to the Knowledge list with the URIs / URL pre-staged in the share-target queue; the queue is consumed by either picking an existing KB or creating a new one. Greyed when there's no URI and the shared text isn't a URL."),
            HelpCard("Cancel", "Back / system back fires onCancel which discards the share without routing. The chooser doesn't add itself to the regular back stack."),
            HelpCard("Title-bar icons", "Help and Home only. Help points to this entry; Home aborts the chooser."),
            HelpCard("Tips", "Cards stay tappable even when the payload is \"weak\" for that route — only-image-shared can still go to Report (vision attach), only-text-shared can still go to Knowledge (paste-in URL or note). The receiving callbacks do the heavier validation."),
            HelpCard("Pitfalls", "Multiple shared images: only the first attaches to a chat or report; the rest are dropped on those routes. Use Add to Knowledge if you want to ingest several files at once.")
        )
    )
,
    "settings_main" to HelpContent(
        title = "Settings",
        cards = listOf(
            HelpCard("Overview", "General app preferences plus the entry point to AI Setup. Edits autosave with a 400 ms debounce, so you don't need a Save button — just type and back out."),
            HelpCard("User name", "Plain text. Surfaces wherever the app addresses you and defaults the From: header on email-style exports."),
            HelpCard("Default email address", "Pre-fills the To: field on report email exports. Leave blank to be prompted each time."),
            HelpCard("API tracing", "Master switch. Off → no new trace files are written, the Hub's AI API Traces card and every 🐞 ladybug icon across result screens disappears. On → every API request and response is captured to disk."),
            HelpCard("Model name layout", "Two radio options. Model name only is the dense default. Provider and model name joins the provider's display name and the model id with \" · \" — useful when you run the same model on multiple providers."),
            HelpCard("Show < Back", "When off the visible back chevron disappears from every TitleBar and the title left-aligns. System / gesture back keeps working — TitleBar's BackHandler is registered independently."),
            HelpCard("Title bar", "Plain back button only. No 🔄 / ℹ️ / 🗑 / 🐞 icons here."),
            HelpCard("Tips", "Settings has no Save button on purpose — every keystroke restarts the 400 ms debounce timer. If you tap Back fast, the latest values still flush to disk."),
            HelpCard("Related", "Tap the AI Setup row (when present elsewhere) for the rest of the configuration; Housekeeping → Reset application reverts everything here to factory defaults.")
        )
    ),
    "settings_setup" to HelpContent(
        title = "AI Setup",
        cards = listOf(
            HelpCard("Overview", "Top-level hub for AI configuration. Each card opens a sub-hub or list — counts on the right show how many entries you have so you can tell at a glance what is configured."),
            HelpCard("Providers", "Per-provider API keys, state, default model, and the catalog editor. Count = number of registered providers (39 ship by default plus any you have added)."),
            HelpCard("Models", "Sub-hub: per-provider Models, Model Types (default API paths), and Manual model types overrides. Count = total models across active providers only."),
            HelpCard("Workers", "Sub-hub for Agents, Flocks, Swarms. Disabled until at least one provider has an API key. Count = active agents + flocks + swarms."),
            HelpCard("Prompt management", "Sub-hub: System Prompts plus the four Internal Prompts category buckets (Meta / Fan-out / Fan-in / Other). Count = system prompts + internal prompts."),
            HelpCard("Parameters", "Direct CRUD for parameter presets (temperature, max tokens, system prompt, web-search flags, reasoning effort)."),
            HelpCard("Costs", "Opens the manual cost-override list. Count = number of manual override entries currently saved."),
            HelpCard("External Services", "HuggingFace, OpenRouter, Artificial Analysis API keys. Count = number of those keys that are non-blank."),
            HelpCard("Local Models", "Sub-hub for on-device .task LLMs and .tflite LiteRT embedders. Count = installed total across both runtimes."),
            HelpCard("Title bar", "Plain back button. Refresh / Trash / Bug / Info icons live on the deeper screens.")
        )
    ),
    "agents" to HelpContent(
        title = "Agents",
        cards = listOf(
            HelpCard("Overview", "CRUD list of named agent configurations. Each agent ties a provider, model, optional API key override, optional endpoint, parameters preset(s), and system prompt into one reusable unit."),
            HelpCard("Visible entries", "Only agents whose provider is currently active (state == \"ok\") show up — agents bound to inactive or keyless providers are hidden, not deleted."),
            HelpCard("Item rows", "Show name in white, then \"Provider · effective model\" beneath. Tap a row to edit; tap the row's trash to delete (after a confirmation dialog scoped to entity type \"Agent\")."),
            HelpCard("Add Agent", "Top button opens the Edit screen with empty fields and the first active provider preselected as the default."),
            HelpCard("Title bar", "Standard CrudListScreen bar — back button only. No 🔄 / ℹ️ / 🗑 / 🐞."),
            HelpCard("Tips", "Sort is alphabetical by name (case-insensitive)."),
            HelpCard("Pitfalls", "If your provider list is fresh and nothing is active, this list will appear empty even though you have agent rows saved — set up a provider key first."),
            HelpCard("Related", "Flocks bundle agents into groups; Swarms group provider/model pairs (no agent indirection); Refresh → Default agents seeds one agent per active provider automatically.")
        )
    ),
    "agent_edit" to HelpContent(
        title = "Agent edit",
        cards = listOf(
            HelpCard("Overview", "Form for one agent. All fields autosave on Create / Save — there is no per-field autosave here. Cancel = system back."),
            HelpCard("Agent name", "Required, must be unique among other agents (case-insensitive). The Save / Create button stays disabled until the name validates."),
            HelpCard("Provider / Model", "Open full-screen pickers via the two outlined buttons. Switching provider clears the model field; \"(default)\" means \"use the provider's default model\" at run time."),
            HelpCard("API Key", "Optional. Overrides the provider's saved key for this agent only. Leave blank to use the provider key. Editing it clears any earlier test result."),
            HelpCard("Endpoint", "Dropdown surfaces only when the provider has more than the Default endpoint or when LiteLLM lists extra paths for the picked model. Picking a LiteLLM-derived path materialises a real Endpoint entry on the provider via onAddEndpoint."),
            HelpCard("System Prompt / Parameters", "Two side-by-side buttons opening selector dialogs. Selected presets show in purple-tinted state. A red ⚠ banner appears beneath when LiteLLM reports the chosen model does not accept system messages."),
            HelpCard("Test Agent", "Visible only when an API key (override or provider) exists. Runs a real API call; on success shows green \"Success\", on failure red error text. The 🐞 next to the result deep-links to the captured trace."),
            HelpCard("Title bar", "helpTopic=agent_edit. Back-only. The 🐞 sits inline on the Test row, not in the bar."),
            HelpCard("Pitfalls", "If you flip provider after picking a model, the model resets — by design, since model ids rarely match across providers."),
            HelpCard("Related", "Tap Provider on a Trace Detail screen to jump back to the matching agent; the Edit Provider screen auto-creates a default agent when you change its default model.")
        )
    ),
    "flocks" to HelpContent(
        title = "Flocks",
        cards = listOf(
            HelpCard("Overview", "CRUD list of Flocks — named groups of Agents, plus optional shared parameters and system prompt. The Report builder uses a flock to fan one prompt out across several agents in a single tap."),
            HelpCard("Item rows", "Show name in white plus a count and comma-separated list of contained agents (active providers only). Tap a row to edit, trash to delete with confirmation."),
            HelpCard("Add Flock", "Opens the editor blank. The Save / Create button stays disabled until the flock has both a unique name and at least one selected agent."),
            HelpCard("Default agents flock", "Refresh → Default agents creates / maintains a flock named exactly DEFAULT_AGENTS_FLOCK_NAME — do not rename it manually unless you know what you are doing."),
            HelpCard("Title bar", "Plain back button. No 🔄 / ℹ️ / 🗑 / 🐞."),
            HelpCard("Tips", "Flocks are sorted alphabetically; the subtitle truncates long member lists, so the Edit screen is the canonical view."),
            HelpCard("Pitfalls", "Deleting a flock does not delete the agents it referenced — agents are owned at a higher level."),
            HelpCard("Related", "Refresh → Default agents auto-builds a default flock; Reports → New report → + Flock pulls from this list.")
        )
    ),
    "flock_edit" to HelpContent(
        title = "Flock edit",
        cards = listOf(
            HelpCard("Overview", "Edit one flock. Top row holds the name field and Create / Save button (disabled until the name is valid AND at least one agent is checked)."),
            HelpCard("System Prompt / Parameters", "Optional shared overrides applied to every agent in the flock at run time. Both buttons turn purple when populated. Selected via the same dialogs used in Agents."),
            HelpCard("Search agents", "Filter input matches agent name OR the agent's provider display name. Selected agents pin to the top of the list (compareByDescending) so a long roster stays manageable."),
            HelpCard("Selection counter", "\"N selected of M\" shows above the list, where M counts only agents whose provider is currently active."),
            HelpCard("Agent rows", "Each row shows name plus the effective model label (provider · model in the chosen layout). Tap the whole row OR the checkbox to toggle membership."),
            HelpCard("Title bar", "helpTopic=flock_edit. Back-only."),
            HelpCard("Tips", "Empty selection is the only blocker for Save besides name validation — a flock with one agent is legal but pointless."),
            HelpCard("Pitfalls", "An agent bound to an inactive provider is hidden here and effectively disabled — fix the provider's state to use it again.")
        )
    ),
    "swarms" to HelpContent(
        title = "Swarms",
        cards = listOf(
            HelpCard("Overview", "CRUD list of Swarms — groups of (provider, model) pairs WITHOUT going through Agents. Use a swarm when you want to fire the same prompt at, say, GPT-5 / Claude / Gemini in parallel without creating individual agent rows."),
            HelpCard("Item rows", "Show name plus \"N members: provider/model, provider/model, …\" filtered to active providers. Tap to edit; trash to delete with confirmation."),
            HelpCard("Add Swarm", "Opens the editor blank. Create / Save stays disabled until the name is valid AND at least one member is selected."),
            HelpCard("Title bar", "Plain back button."),
            HelpCard("Tips", "Members of inactive providers stay in the swarm but won't run — fix the provider state and they re-light without re-editing."),
            HelpCard("Difference from Flocks", "Flocks reference agents (and inherit each agent's API key / endpoint / prompts). Swarms hold raw provider+model tuples and use the provider's saved key / endpoint, plus optional swarm-level system prompt and parameters."),
            HelpCard("Pitfalls", "There is no per-member API key override — for that, build agents and a flock instead."),
            HelpCard("Related", "Reports → New report → + Swarm pulls from this list.")
        )
    ),
    "swarm_edit" to HelpContent(
        title = "Swarm edit",
        cards = listOf(
            HelpCard("Overview", "Edit one swarm. Top row holds the name + Save / Create button. Save activates once the name is valid and at least one member is added."),
            HelpCard("System Prompt / Parameters", "Optional shared bundle applied to every member at run time. Buttons go purple when populated."),
            HelpCard("Member counter", "\"N members\" sits at the left of the action row; the blue \"+ Add model\" button on the right opens the full ModelSearchScreen overlay (search across all known provider+model pairs)."),
            HelpCard("Member cards", "One card per (provider, model) tuple. Provider name in blue, model id in white, plus capability badges (👁 vision / 🌐 web / 🧠 reasoning). The trailing red ✕ removes the member."),
            HelpCard("Adding from the picker", "If you pick a pair already in the swarm, the duplicate is silently dropped — a member is keyed on (provider.id, model)."),
            HelpCard("Title bar", "helpTopic=swarm_edit. Back-only."),
            HelpCard("Pitfalls", "The picker shows ALL known models, not just active ones — picking a model whose provider has no key won't fail until you actually run the swarm."),
            HelpCard("Related", "ModelSearchScreen (the picker) is also reachable from Search → Model search.")
        )
    ),
    "parameters" to HelpContent(
        title = "Parameters",
        cards = listOf(
            HelpCard("Overview", "CRUD list of parameter presets. A preset bundles temperature / max tokens / top-p / top-k / penalties / seed / system prompt / web-search and reasoning flags into one named row that you can attach to agents, flocks, swarms or one-off report runs."),
            HelpCard("Subtitle", "\"N parameters configured\" — counts how many fields in the preset are non-null, plus a +1 for each web-search flag that's on."),
            HelpCard("Add Parameter Preset", "Top button opens the edit screen blank."),
            HelpCard("Title bar", "Plain back button."),
            HelpCard("Tips", "When multiple presets are attached to a worker (agents accept a list), later non-null values win — a sensible \"merge\" semantics."),
            HelpCard("Pitfalls", "Setting maxTokens here is a soft suggestion for OpenAI but mandatory for Anthropic; leaving it blank in an Anthropic-bound preset falls back to 4096."),
            HelpCard("Related", "Workers → Agents / Flocks / Swarms reference these by id; Provider edit also accepts a list of params ids that apply to every call to that provider.")
        )
    ),
    "parameters_edit" to HelpContent(
        title = "Parameters edit",
        cards = listOf(
            HelpCard("Overview", "Form for one preset. Save / Create activates once the name is valid; every other field is optional and blank means \"don't send this parameter\"."),
            HelpCard("Parameters block", "Temperature (0.0–2.0), Max tokens, Top P (0.0–1.0), Top K, Frequency / Presence penalty (-2.0–2.0), Seed. All free-text — ill-typed values become null on save."),
            HelpCard("System Prompt", "Multi-line text (3–6 visible lines). Sent as the system role to providers that accept it; folded into the user message for those that don't (Anthropic with thinking-only models, Mistral cohorts)."),
            HelpCard("Options", "Response format JSON, Enable web search (search:true flag — Perplexity-style), Web search tool (Anthropic / Gemini / OpenAI Responses-style tool block), Return citations."),
            HelpCard("Search Recency", "Filter chips: None / day / week / month / year. Honored only by providers that report supportsSearchRecency=true."),
            HelpCard("Reasoning Effort", "Filter chips: None / low / medium / high. Sent only to reasoning-capable models (gpt-5/o-series, Gemini thinking, Claude with extended thinking) — ignored by everything else."),
            HelpCard("Title bar", "helpTopic=parameters_edit. Back-only."),
            HelpCard("Tips", "Numeric fields tolerate empty input — a blank stays null in the saved preset.")
        )
    ),
    "system_prompts" to HelpContent(
        title = "System Prompts",
        cards = listOf(
            HelpCard("Overview", "CRUD list of reusable system-prompt blocks. A row stores a name and a single multi-line text body — assignable to Agents, Flocks, Swarms, Providers, and one-off Report runs."),
            HelpCard("Item rows", "Show the name in white, the first 80 characters of the prompt in dim text below. Tap to edit, trash to delete with confirmation."),
            HelpCard("Add System Prompt", "Top button opens an empty editor."),
            HelpCard("Title bar", "Plain back button."),
            HelpCard("Tips", "Names are unique among system prompts (case-insensitive). The body is required for save."),
            HelpCard("Pitfalls", "If a worker also has a Parameters preset that carries a non-blank systemPrompt, that wins over the bare System Prompt — the preset's value is the late-merge winner."),
            HelpCard("Related", "Internal Prompts (Meta / Fan-out / Fan-in / Other) live in a different bucket and use placeholders like @QUESTION@ / @RESULTS@ — not interchangeable with these.")
        )
    ),
    "system_prompt_edit" to HelpContent(
        title = "System prompt edit",
        cards = listOf(
            HelpCard("Overview", "Form for one system prompt. Two fields: Name (single line, required, unique) and System prompt text (6–15 visible lines)."),
            HelpCard("Save", "Disabled while the name is invalid OR the prompt body is blank — the body is required, unlike Parameters' optional systemPrompt field."),
            HelpCard("Live counter", "\"N characters\" beneath the body, updated on every keystroke."),
            HelpCard("Title bar", "helpTopic=system_prompt_edit. Back-only."),
            HelpCard("Tips", "Markdown is fine — most chat APIs forward it raw and many models treat it as styled hints."),
            HelpCard("Pitfalls", "There is no token-count check; oversized system prompts will count against context length at run time without a warning here."),
            HelpCard("Related", "Use placeholders like @QUESTION@ here at your peril — substitution only happens for Internal Prompts, not System Prompts.")
        )
    ),
    "internal_prompts_hub" to HelpContent(
        title = "Internal prompts (hub)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under Prompt Management with one card per Internal Prompt category. Each card opens the same list screen pinned to that category — counts show how many entries are in each bucket."),
            HelpCard("Meta prompts", "Summarize, Compare — run on the full report (one final call). category=\"meta\"."),
            HelpCard("Fan-out prompts", "Run across every pair of report-models (N×(N-1) calls). category=\"fan_out\"."),
            HelpCard("Fan-in prompts", "Combine the report's responses + their fan-out responses into one final report. Run once on a single picked model. category=\"fan_in\"."),
            HelpCard("Other internal prompts", "Fixed list — intro, model_info, translate, rerank, moderation. Editable but not addable / deletable. category=\"internal\"."),
            HelpCard("Title bar", "Plain back. No 🔄 / ℹ️ / 🗑 / 🐞."),
            HelpCard("Tips", "Names need to be unique within a category — \"Compare\" can exist under both meta and fan_in without collision."),
            HelpCard("Related", "Housekeeping → Internal prompts has a one-shot \"Load new prompts from assets/prompts.json\" merge that adds bundled rows you don't yet have.")
        )
    ),
    "internal_prompts" to HelpContent(
        title = "Internal prompts (list)",
        cards = listOf(
            HelpCard("Overview", "CRUD list pinned to one category (meta / fan_out / fan_in / internal). The screen title and Add label adapt — Add meta prompt vs. Add fan-out prompt etc. Other internal is a fixed list — no Add / Delete."),
            HelpCard("Item rows", "Show name plus a chip line: ref · agent (omitted when *select), then \"— title or first 60 chars of body\". Tap to edit; trash to delete (hidden for Other internal)."),
            HelpCard("Add", "The button label reflects the active category (e.g. \"Add meta prompt\"). Hidden for Other internal."),
            HelpCard("Title bar", "helpTopic=internal_prompts. Plain back."),
            HelpCard("Tips", "Sorted alphabetically by name. Edits stay scoped to this category — saving in the editor pushes back into the same bucket."),
            HelpCard("Related", "The Report Result screen's Meta and Fan out buttons surface meta and fan_out prompts respectively; Fan out (L1) surfaces fan_in.")
        )
    ),
    "internal_prompt_edit" to HelpContent(
        title = "Internal prompt edit",
        cards = listOf(
            HelpCard("Overview", "Form for one Internal Prompt. Category is fixed by where you arrived from (cannot be changed in this screen)."),
            HelpCard("Name / Title", "Name is unique within the category. Title is a short tag shown alongside the name on Fan out; optional. For Other internal the Name is read-only."),
            HelpCard("Append reference legend", "Switch — adds a [N] = Provider / Model footer to the response."),
            HelpCard("Agent dropdown", "*select = ask the user at run time (legacy default). *n/a = no agent applies (fan_out only). Anything else = the named agent in Settings.agents resolved at run time."),
            HelpCard("Template body", "8–22 visible lines. Helper text lists the placeholders allowed: @QUESTION@, @RESULTS@, @COUNT@, @TITLE@, @DATE@ (meta); @RESPONSE@/@QUESTION@/@TITLE@/@DATE@/@COUNT@ (fan_out); @COUNT@, @FAN_OUT_COUNT@, the iterable @REPORT@@RESPONSES@ block with @RESPONSE@ inside (fan_in)."),
            HelpCard("Title bar", "helpTopic=internal_prompt_edit. The screen title reads \"Edit/Add <category singular>\"."),
            HelpCard("Pitfalls", "If you deep-link into edit before aiSettings has bootstrapped, the screen shows a \"Loading…\" placeholder and re-keys when the prompt id resolves — saving an empty form there would silently create a duplicate, so it is blocked.")
        )
    ),
    "providers" to HelpContent(
        title = "Providers",
        cards = listOf(
            HelpCard("Overview", "List of every registered provider. Filter chips at the top split between Active (state == \"ok\") and All (39 by default plus user-added). The state of each row is shown by an emoji."),
            HelpCard("State emojis", "🔑 = ok (key tested + working). ❌ = error (key set but tests fail). 💤 = inactive (manually disabled). ⭕ = not-used (no key set yet)."),
            HelpCard("Active filter", "Default view. Empty state hint suggests switching to All to set a key."),
            HelpCard("All filter", "Shows every provider plus a green \"+ Add provider\" button at the top — opens ProviderAddScreen."),
            HelpCard("Item rows", "Provider display name in white plus the configured default model in dim text (only shown when state == ok). Tap a row to edit."),
            HelpCard("Title bar", "helpTopic=providers. Plain back."),
            HelpCard("Tips", "The active/all toggle is hoisted into the parent SettingsScreen so navigating to a detail and back preserves it."),
            HelpCard("Pitfalls", "Active count drops to 0 if every provider's key is invalid — you'll need to switch to All to fix one."),
            HelpCard("Related", "Provider configuration card on AI Setup → Providers also leads here. ProviderAddScreen creates new ones; ProviderAdminScreen is the deep-link to each provider's web console.")
        )
    ),
    "provider_edit" to HelpContent(
        title = "Provider edit",
        cards = listOf(
            HelpCard("Overview", "Two layers in one screen: per-config runtime state at the top (key, default model, parameters, advanced URLs) and the catalog Definition cards underneath (display name, base URL, API format, type paths, pricing/feature flags). All edits autosave."),
            HelpCard("Provider inactive", "Switch at the very top. Toggling on flips state to inactive immediately. Toggling off triggers a fresh API test — pass = state ok, fail = state error, no key = not-used."),
            HelpCard("API Key card", "Field plus a Test button. On pass: state flips to \"ok\" AND the provider's default agent gets added to the \"default agents\" flock. On fail: 🐞 inline icon links to the trace from this run when tracing is on."),
            HelpCard("Default Model card", "Tap to open the SelectModelScreen overlay. Auto-refresh fetches the model list when the provider's source is API. Returning here updates the agent named after the provider in lock step with the chosen model."),
            HelpCard("Parameters card", "Multi-select dialog. Selected presets show as a comma-joined list inside the card."),
            HelpCard("Models card", "Tap to drill into the per-provider Models screen — same one you reach from AI Setup → Models → this provider. Back from there returns here."),
            HelpCard("Advanced (per-config overrides)", "Custom model list URL + Admin URL override — these stay attached to your runtime ProviderConfig, not the catalog."),
            HelpCard("Definition cards", "Basics / API / Models / Pricing & cost / Features / Storage. Edits push through ProviderRegistry.update — same store loaded from assets/setup.json on first launch. ID and prefs key are read-only (changing them would orphan stored keys, models, statistics)."),
            HelpCard("Title bar", "helpTopic=provider_edit. Plain back."),
            HelpCard("Pitfalls", "If you change apiFormat, the captured `service` may go stale — Test re-resolves through AppService.findById to use the fresh registry entry. Don't rely on the old reference if you tweak apiFormat then test in the same session.")
        )
    ),
    "provider_add" to HelpContent(
        title = "Provider add",
        cards = listOf(
            HelpCard("Overview", "Form to register a brand-new provider. Mirrors every field on ProviderDefinition so the result behaves identically to the providers shipped in assets/setup.json."),
            HelpCard("Basics", "ID (uppercase, unique — typed input is auto-uppercased and non-alphanumeric chars become _; existing ids show \"Already in use\"), Display name, Base URL, Default model, Admin URL."),
            HelpCard("API", "API format chips (OPENAI_COMPATIBLE / ANTHROPIC / GOOGLE — only the dispatch format that matters), Chat path (default v1/chat/completions), Models path (default v1/models), Model list format (object/array), Seed field name (default \"seed\")."),
            HelpCard("Models", "Default source (API / MANUAL), Model filter regex, Hardcoded models comma-separated."),
            HelpCard("Pricing & cost", "OpenRouter name (for fan out-provider price lookup), LiteLLM prefix, Cost ticks divisor, Extract API cost switch."),
            HelpCard("Features", "Supports citations, Supports search recency. Honored only when sending requests."),
            HelpCard("Storage (collapsible)", "Prefs key — leave blank to use id.lowercase(). Once saved you cannot rename it."),
            HelpCard("Title bar", "helpTopic=provider_add. Plain back."),
            HelpCard("Save", "Add provider button at bottom. Disabled until ID + Display name + Base URL + Default model are populated and the ID is unique. Saves via ProviderRegistry.add and pops to the new provider's Edit screen."),
            HelpCard("Pitfalls", "ID format is enforced — A-Z / 0-9 / _ only; lowercase typing is uppercased on input.")
        )
    ),
    "models" to HelpContent(
        title = "Models",
        cards = listOf(
            HelpCard("Overview", "Top-level list of every active provider with a model count. Each row drills into that provider's per-provider model list (fetch / test / manual CRUD). Inactive providers are hidden — fix their key first."),
            HelpCard("Bulk refresh button", "\"Call all API retrieve models lists\" at the bottom — same path as Refresh → Models. forceRefresh=true bypasses the cache-validity check; an in-progress dialog shows live status."),
            HelpCard("Refresh results dialog", "After the bulk call finishes, a dialog reports per-provider counts (green) or \"failed\" (red)."),
            HelpCard("Per-provider screen", "API / Manual filter chips at top; API mode adds Fetch Models / Test all models buttons; Manual mode adds an inline Model ID add/edit form."),
            HelpCard("Test all models", "Visible after fetch when there are models. Spawns up to 5 concurrent tests via a Semaphore, gating in flight per row to ⏳ / ✅ / ❌. A failed ❌ links to its trace."),
            HelpCard("Remove failed", "Surfaces only after a Test all run completed with at least one ❌. Tap removes the failed list from the persisted models — the auto-save effect picks it up. Trace icons for those rows clear with them."),
            HelpCard("Per-row badges", "👁 vision / 🌐 web search / 🧠 reasoning + a colored type chip (chat/embedding/rerank/image/tts/stt/moderation/classify). The chip respects manual overrides instantly."),
            HelpCard("Title bar", "helpTopic=models. Plain back."),
            HelpCard("Pitfalls", "Manual lists are user-curated — Fetch Models is hidden in Manual mode. If you switch to Manual mid-edit, partial form state is wiped to avoid surprises on return."),
            HelpCard("Related", "Click a model row to open its Model Info screen.")
        )
    ),
    "model_edit" to HelpContent(
        title = "Per-provider models",
        cards = listOf(
            HelpCard("Overview", "Per-provider model list (the screen you reach by tapping a provider on Models). Same screen also opens via the Models card on Provider Edit. Auto-saves both modelSource and the models list."),
            HelpCard("API / Manual chips", "Switch model-source at the top. Auto-save fires whenever you flip — and switching away from Manual clears any half-typed add/edit state."),
            HelpCard("Fetch Models", "API-mode only. Disabled while a fetch is in flight. Inline error row beneath surfaces fetch errors with a 🐞 trace deep-link."),
            HelpCard("Manual add / update form", "Single \"Model ID\" field plus Add or Update button. Duplicate detection in red. Editing an existing model shows a Cancel button alongside."),
            HelpCard("Per-row test status", "After Test all models: ⏳ rotating, ✅ green, ❌ red. ❌ rows with a captured trace are tappable and jump to the trace."),
            HelpCard("Type chips & badges", "Per row — color-coded type pill (Blue=chat, Purple=embedding, Indigo=rerank, Orange=image, Green=tts/stt, Red=moderation/classify) and capability emoji."),
            HelpCard("Title bar", "Title reads \"<Provider> models\". Back returns to the Provider Edit screen if you arrived via the Models card; otherwise to the Models list."),
            HelpCard("Tips", "manual mode shows an ✕ delete button on each row; API mode does not — there refresh is the source of truth."),
            HelpCard("Pitfalls", "If the API key is blank in API mode, the action row replaces itself with a hint pointing back to Providers.")
        )
    ),
    "model_types" to HelpContent(
        title = "Model Types",
        cards = listOf(
            HelpCard("Overview", "Sets the default API path per ModelType (chat / embedding / rerank / image / tts / stt / moderation / classify). Resolution at dispatch time: per-provider override → these defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Auto-save", "Editing a field pushes the trimmed map back through onSave — no Save button. Blank means \"use the hardcoded default\" for that type."),
            HelpCard("Field placeholder", "Each input shows the hardcoded fallback as a dim placeholder so you know what will run if you leave the field empty."),
            HelpCard("Title bar", "helpTopic=model_types. Plain back."),
            HelpCard("Tips", "Per-provider Type paths under Provider edit → Definition · API win over these — so use this screen for global defaults, the provider screen for exceptions."),
            HelpCard("Pitfalls", "If you blank a default that the hardcoded fallback also doesn't have, dispatch will throw at runtime — easiest case is if you typo a path and the underlying provider doesn't expose that type."),
            HelpCard("Related", "Provider edit's Definition · API → Type paths overrides are the per-provider equivalent.")
        )
    ),
    "manual_model_types" to HelpContent(
        title = "Manual model types",
        cards = listOf(
            HelpCard("Overview", "CRUD list of (provider, model, type) triples that win over the autodetected type stored on ProviderConfig.modelTypes. Useful when the heuristic and native list APIs both miss — e.g. an embedding model whose name doesn't contain \"embed\"."),
            HelpCard("Item rows", "\"<providerId> / <modelId>\" with \"→ <type>\" plus capability flags (👁 / 🌐 / 🧠) on the second line. Tap to edit, trash to delete with confirmation."),
            HelpCard("Add override", "Top button switches to the editor with the form blank."),
            HelpCard("Edit form", "Provider dropdown over all providers; Model dropdown over the provider's known models (placeholder \"No models — fetch this provider first\" when empty); Type filter chips (3 cols × 3 rows over the 9 ModelType.ALL); Capabilities checkboxes for vision / web search / reasoning."),
            HelpCard("Capability flags", "Wired into Settings.isVisionCapable / isWebSearchCapable / isReasoningCapable so a tick here surfaces the badge anywhere this model appears, even when the provider's auto-derived sets miss it."),
            HelpCard("Title bar", "helpTopic=manual_model_types. Plain back."),
            HelpCard("Tips", "Reachable from Model Info → \"Add manual override\" too — it pre-fills the form with the current model's heuristic flags so you only confirm or change them."),
            HelpCard("Pitfalls", "Switching the provider in the editor wipes the model id (model lists are provider-scoped)."),
            HelpCard("Related", "ManualModelOverrideEntryScreen is the same form pre-populated for direct entry from Model Info.")
        )
    ),
    "external_services" to HelpContent(
        title = "External Services",
        cards = listOf(
            HelpCard("Overview", "API keys for three non-LLM auxiliary services consumed by the app. Each field auto-saves on every keystroke (no debounce here)."),
            HelpCard("HuggingFace", "Used by the Model Info lookup to pull model cards / context-length / license fields. Get a free token at huggingface.co/settings/tokens."),
            HelpCard("OpenRouter", "Used for pricing data and model specifications (capability flags, supported parameters). Same key flows into Refresh → OpenRouter."),
            HelpCard("Artificial Analysis", "Pricing snapshot plus quality / speed scores. Free tier — sign up at artificialanalysis.ai/api. Used by Refresh → Artificial Analysis."),
            HelpCard("Title bar", "helpTopic=external_services. Plain back."),
            HelpCard("Tips", "Filling these unlocks the matching Refresh actions on the Refresh screen — without an OpenRouter key the OpenRouter button is disabled, same for Artificial Analysis."),
            HelpCard("Pitfalls", "These are NOT LLM provider keys — adding an OpenAI / Anthropic key here will not register the provider. Use Settings → AI Setup → Providers for that."),
            HelpCard("Related", "Refresh → OpenRouter / Artificial Analysis. Backup includes these keys; Clear all configuration removes them.")
        )
    ),
    "refresh" to HelpContent(
        title = "Refresh",
        cards = listOf(
            HelpCard("Overview", "Bulk refresh hub. Each card has a button + helper text. \"Refresh all\" at the top runs every action below in sequence — catalogs first (so capability data is fresh before per-provider tests fire), then provider tests, model lists, default agents, and finally an automatic process restart."),
            HelpCard("Refresh all (auto-restart)", "Sequence: OpenRouter (if key) → LiteLLM → models.dev → Helicone → llm-prices.com → Artificial Analysis (if key) → Providers → Models → Default agents → kill+relaunch via FLAG_ACTIVITY_NEW_TASK / CLEAR_TASK. Saves you from a manual kill/relaunch."),
            HelpCard("Catalog refreshes", "OpenRouter (needs key), LiteLLM (BerriAI/litellm GitHub), models.dev (LiteLLM fallback), Helicone (helicone.ai/api/llm-costs — pricing-only), llm-prices.com (Simon Willison's curated 10-vendor tables), Artificial Analysis (needs key)."),
            HelpCard("Providers", "Tests every provider's saved key with a small live model call. Marks each as ok / error / inactive (already disabled) / not-used (no key). Live progress dialog with per-provider rows updating from \"pending\" to ok/error."),
            HelpCard("Models", "Calls every active provider's model-list endpoint (forceRefresh=true). Replaces the cached lists used by every model picker. Result dialog shows per-provider counts."),
            HelpCard("Default agents", "Per active provider: ensure an agent named after the provider exists (using its current default model), then ensure the \"default agents\" flock contains exactly the successfully-tested agents. Live progress with per-provider rows."),
            HelpCard("Capability recompute", "LiteLLM and models.dev refreshes call aiSettings.recomputeAllCapabilities() so vision/web-search precomputed sets pick up the new state. Helicone is pricing-only — no recompute."),
            HelpCard("Title bar", "helpTopic=refresh. Plain back."),
            HelpCard("Pitfalls", "OpenRouter and Artificial Analysis buttons disable themselves until you set their keys under External Services."),
            HelpCard("Related", "Reachable both from AI Setup → Refresh and from Housekeeping → Refresh.")
        )
    ),
    "import_export" to HelpContent(
        title = "Export / Import",
        cards = listOf(
            HelpCard("Overview", "Three card groups: Export, Import, Layered costs. All exports use Android's Storage Access Framework (CreateDocument) — the picker decides where the file lands; the app never sees the location."),
            HelpCard("Export · Config", "Full ai_config-<yyMMdd-HHmm>.json — provider configs (with keys), agents/flocks/swarms/parameters/prompts, default type paths, External Services keys."),
            HelpCard("Export · API Keys", "Just the keys (per-provider + HuggingFace + OpenRouter + Artificial Analysis) as a flat JSON object. Toast reports the populated key count."),
            HelpCard("Export · Costs", "Manual cost overrides as CSV (provider,model,input_per_million,output_per_million)."),
            HelpCard("Export · providers.json / prompts.json", "Drop-in shape for the bundled assets — no API keys included. Useful when shipping a tuned catalog as new defaults."),
            HelpCard("Import", "Five buttons matching the five export shapes (Config, API Keys, Costs, providers.json, prompts.json). Layered-costs files are re-imported from their own card via Import manual changed costs. Config import picks up apiSettings + general settings + 3 external keys atomically."),
            HelpCard("Layered costs", "One row per (provider, model). Two empty new_input/new_output columns up front for a manual override; rest show every tier's $/M-token price in run-time precedence order (LiteLLM > models.dev > Helicone > llm-prices > Artificial Analysis > Override > OpenRouter > Default). Export all covers every model; Export filtered drops rows already covered by LiteLLM/models.dev/Helicone/llm-prices/AA/OpenRouter. Import manual changed costs reads the same CSV back: only rows where the user filled in the two override columns are applied; blank rows are silently ignored."),
            HelpCard("Title bar", "helpTopic=import_export. Plain back."),
            HelpCard("Pitfalls", "Importing API Keys with a full Config payload throws ConfigBundleMistakenForKeysException — the toast points you back to the Config import button. Costs CSV importer skips malformed rows silently."),
            HelpCard("Related", "Backup & Restore (Housekeeping) is the all-in-one zip alternative; this screen is for selective shape-typed transfer.")
        )
    ),
    "local_runtime" to HelpContent(
        title = "Local models",
        cards = listOf(
            HelpCard("Overview", "Sub-hub for the two on-device runtimes. Each card shows installed count and drills into a dedicated screen."),
            HelpCard("Local LLMs", "On-device .task chat models (MediaPipe Tasks GenAI). Drives the synthetic Local provider that surfaces in every chat / report flow alongside cloud providers."),
            HelpCard("Local LiteRT models", "On-device .tflite text embedders (MediaPipe Tasks). Drives Local Semantic Search and Knowledge bases whose embedderProviderId == \"LOCAL\"."),
            HelpCard("Title bar", "helpTopic=setup_local_models. Plain back."),
            HelpCard("Tips", "Counts are read once on screen entry — switch back to AI Setup and forward again if you just installed something via the deeper screens."),
            HelpCard("Pitfalls", "Backup explicitly excludes local_llms/ and local_models/ (FILES_DIR_BACKUP_EXCLUDES) — these are big, easily re-downloadable, and personal. Same set is preserved through clearFilesDirForRestore."),
            HelpCard("Related", "Both runtimes are wiped by Housekeeping → Clear all configuration but kept by Clear all runtime data and by Reset application's default chain.")
        )
    ),
    "local_litert_models" to HelpContent(
        title = "Local LiteRT models",
        cards = listOf(
            HelpCard("Overview", "On-device .tflite text embedders. Models live in <filesDir>/local_models/ — nothing leaves the device once installed. Only models with MediaPipe Tasks metadata baked into the .tflite can load — the curated list is the verified set."),
            HelpCard("Curated downloads", "One indigo button per spec in LocalEmbedder.downloadable. Shows displayName plus approximate MB. Already-installed entries display \"<name> ✓\" and become non-clickable."),
            HelpCard("Download progress", "Live status text below the buttons updates with byte percentage as the download streams (\"Downloading <name>… NN%\")."),
            HelpCard("Add model from file", "Blue button opens an SAF picker (application/octet-stream + */*) — for users who've stamped MediaPipe Tasks metadata onto their own .tflite via Model Maker. Imported under a sanitized filename in local_models/."),
            HelpCard("Installed list", "Below the buttons. One row per installed model with a red \"Remove\" — releases the embedder and deletes <name>.tflite from disk."),
            HelpCard("Title bar", "helpTopic=local_litert_models. Plain back."),
            HelpCard("Tips", "MediaPipe Tasks metadata is mandatory; arbitrary .tflite files won't load even though the picker accepts them."),
            HelpCard("Pitfalls", "Backup excludes this directory — restoring a backup leaves your installed embedders untouched, but a fresh device starts empty."),
            HelpCard("Related", "Knowledge → embedderProviderId=\"LOCAL\" KBs use these. Local Semantic Search (Search → Local Semantic) does too.")
        )
    ),
    "local_llms" to HelpContent(
        title = "Local LLMs",
        cards = listOf(
            HelpCard("Overview", "On-device LLMs via MediaPipe Tasks GenAI. Most useful models (Gemma, Phi, Llama) are licence-gated — open one of the recommended links in your browser, accept the terms, download the .task file (typically 0.5–2.5 GB), then return and Add LLM from file."),
            HelpCard("Download links", "One indigo button per LocalLlm.recommendedLinks entry. Each opens the model's web page in the system browser — they don't download into the app, they just hand off."),
            HelpCard("Add LLM from file", "Blue button opens an SAF picker. Accepts .task, .zip, .tar.gz, .tgz, .tar — the first .task entry in an archive is extracted automatically (Apache Commons Compress for tar; built-in for zip)."),
            HelpCard("Installed list", "One row per installed model with a red Remove — releases the runtime and deletes <name>.task from <filesDir>/local_llms/."),
            HelpCard("Title bar", "helpTopic=local_llms. Plain back."),
            HelpCard("Tips", "AppService.LOCAL is synthetic — not in ProviderRegistry, only reachable via findById(\"LOCAL\"). Once you have at least one .task installed, Local appears as a normal provider in every picker."),
            HelpCard("Pitfalls", "Empty / corrupt extractions are detected (target.length() == 0L) and the partial file is deleted; the toast says \"Could not import model\". Backup excludes local_llms/."),
            HelpCard("Related", "Local LLM dispatch routes to LocalLlm.generate instead of Retrofit — no network, no cost, but token speed is bound by your device.")
        )
    ),
    "setup_providers" to HelpContent(
        title = "Providers (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards: per-provider configuration list, the catalog admin (web consoles), and a one-shot import of bundled providers from assets/providers.json."),
            HelpCard("Provider configuration", "Drills into the Providers list — set keys, default models, parameters, and edit catalog definitions per provider."),
            HelpCard("Provider administration", "Opens ProviderAdminScreen — one row per provider with state emoji and a deep-link to its admin / signup web URL."),
            HelpCard("Import new providers from assets/providers.json", "Tapping calls ProviderRegistry.importFromAsset(context). Adds providers in the bundle that aren't yet registered. Status text underneath reports how many were added (or that no new ones were found)."),
            HelpCard("Refresh tick", "providerCount is keyed on a refreshTick that increments after a successful import — so the count badge above updates immediately."),
            HelpCard("Title bar", "helpTopic=setup_providers. Plain back."),
            HelpCard("Tips", "If you've forked or extended the bundle, this is the cheap way to add new entries without touching settings.json."),
            HelpCard("Pitfalls", "Existing providers with the same id are NOT overwritten by this card — use Export / Import → providers.json with upsertFromJson for that."),
            HelpCard("Related", "Provider edit's Definition cards modify already-registered entries in place.")
        )
    ),
    "setup_models" to HelpContent(
        title = "AI Models setup",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards: Models (per active provider), Model Types (default API path per type), Manual model types overrides (per-model type assignments)."),
            HelpCard("Models", "Disabled until you have at least one active provider. Drills into the per-provider model lists. Count = total models across active providers."),
            HelpCard("Model Types", "List of the 9 model kinds from ModelType.ALL with their default API paths. Count = number of types."),
            HelpCard("Manual model types overrides", "CRUD for (provider, model, type, capabilities) overrides that win over autodetection. Count = number of overrides currently saved."),
            HelpCard("Title bar", "helpTopic=setup_models. Plain back."),
            HelpCard("Tips", "Resolution order at dispatch: per-provider Type paths (Provider edit → Definition · API) → Model Types defaults → ModelType.DEFAULT_PATHS hardcoded fallback."),
            HelpCard("Pitfalls", "If no provider is active the Models card stays grey-blue and unclickable."),
            HelpCard("Related", "Provider edit → Definition · API → Type paths exposes the per-provider override layer.")
        )
    ),
    "setup_workers" to HelpContent(
        title = "AI Workers (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Three cards (Agents / Flocks / Swarms) — all three disabled until at least one provider has an API key set."),
            HelpCard("Agents", "Named (provider, model, key, params, prompt, endpoint) tuples. Count = active agents (whose provider is currently active)."),
            HelpCard("Flocks", "Groups of agents with optional shared parameters/system prompt. Count = number of flocks."),
            HelpCard("Swarms", "Groups of (provider, model) pairs without going through agents. Count = number of swarms."),
            HelpCard("Title bar", "helpTopic=setup_workers. Plain back."),
            HelpCard("Tips", "When you import a config bundle these counts jump in lockstep with the imported data."),
            HelpCard("Pitfalls", "If your only API key was just removed, all three cards lock — the hub uses aiSettings.hasAnyApiKey() as its enable gate."),
            HelpCard("Related", "Refresh → Default agents auto-creates one agent per active provider plus a \"default agents\" flock that ties them together.")
        )
    ),
    "setup_prompts" to HelpContent(
        title = "Prompt management (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Two cards: System Prompts (free-form reusable text) and Internal Prompts (templated, category-scoped — Meta/Fan-out/Fan-in/Other)."),
            HelpCard("System Prompts", "Direct CRUD list. Count = number of system prompts."),
            HelpCard("Internal Prompts", "Drills into the Internal Prompts hub which splits further into Meta / Fan-out / Fan-in / Other. Count = total across all four categories."),
            HelpCard("Title bar", "helpTopic=setup_prompts. Plain back."),
            HelpCard("Tips", "System Prompts are referenced by id from Agents/Flocks/Swarms/Providers; Internal Prompts are referenced by name + category by app features (Meta button on report results, Fan out drill-in, Translate, Model info)."),
            HelpCard("Pitfalls", "The two are NOT interchangeable — a system prompt cannot replace a meta prompt because Internal Prompts use placeholder substitution that System Prompts do not."),
            HelpCard("Related", "Housekeeping → Internal prompts → \"Load new prompts from assets/prompts.json\" merges in any bundled rows missing from your set.")
        )
    ),
    "setup_local_models" to HelpContent(
        title = "Local models (setup)",
        cards = listOf(
            HelpCard("Overview", "Sub-hub under AI Setup. Two cards: Local LLMs (.task chat / completion bundles driving the Local provider) and Local LiteRT models (.tflite text embedders driving Local Semantic Search and Local-embedder Knowledge)."),
            HelpCard("Local LLMs", "Counts installed .task files in <filesDir>/local_llms/."),
            HelpCard("Local LiteRT models", "Counts installed .tflite files in <filesDir>/local_models/."),
            HelpCard("Title bar", "helpTopic=setup_local_models. Plain back."),
            HelpCard("Tips", "Both card counts are read with remember{} on entry — they don't auto-refresh on changes inside the deeper screens."),
            HelpCard("Pitfalls", "Backup excludes both directories. Clear all configuration removes both; Clear all runtime data and Reset application leave them alone."),
            HelpCard("Related", "AppService.LOCAL is synthetic and only registered when a .task is present.")
        )
    ),
    "housekeeping" to HelpContent(
        title = "Housekeeping",
        cards = listOf(
            HelpCard("Overview", "Maintenance hub. Backup/restore, Export/Import, Refresh, Trim by age, Usage statistics, Manual cost overrides cleanup, Internal prompts merge, and three destructive Reset variants."),
            HelpCard("Backup & Restore", "Single .zip via Android's SAF — destination picker shows Google Drive, Dropbox, etc. Backup includes everything except local_llms/ and local_models/ (FILES_DIR_BACKUP_EXCLUDES). Restore validates first then writes; auto-restarts the app on success."),
            HelpCard("Export & Import / Refresh", "NavCards — tap anywhere on the row to deep-link into the matching screen."),
            HelpCard("Trim by age", "\"Days to keep\" numeric field → \"Clear Reports/Chats/Traces\" button (disabled until N>0). Deletes reports older than the cutoff, then chats, then trace files; reports a per-kind count toast."),
            HelpCard("Usage statistics", "One purple button. Wipes the per-(provider,model) call counts/tokens/cost. Confirmed via toast, no extra dialog."),
            HelpCard("Manual cost overrides", "Drops every dormant or redundant override (covered by LiteLLM, OpenRouter, equal to default, equal to lookup-without-it). Toast tells you how many were removed."),
            HelpCard("Internal prompts", "Indigo button: merges any prompt in assets/prompts.json that's missing by name. Existing rows with the same name are overwritten. Result text under the button (\"Added N new prompts\")."),
            HelpCard("Reset block", "Three escalating buttons. Clear all runtime data (red): wipes reports, chats, traces, prompt history, KBs, pricing cache, model-list cache, semantic-search cache. Clear all configuration (dark red): wipes provider keys/models/endpoints, agents/flocks/swarms/parameters/prompts, External Services keys, user name + email, ALL Local LLMs and LiteRT models. Reset application (dark red, type-RESET-to-confirm): preserves API keys only, reloads providers + internal prompts from assets, then runs Refresh-all chain."),
            HelpCard("Title bar", "helpTopic=housekeeping. Plain back."),
            HelpCard("Pitfalls", "Reset's confirmation is case-sensitive — must literally type RESET, trimmed. Restore overwrites everything in place; a failed restore leaves the device in an inconsistent state.")
        )
    ),
    "statistics" to HelpContent(
        title = "AI Usage",
        cards = listOf(
            HelpCard("Overview", "Per-provider usage breakdown. Top card is a summary (total calls, total tokens via formatCompactNumber, total cost in green, pricing-source stats). Per-provider rows expand to show per-model details."),
            HelpCard("Pricing fetch", "On entry, if an OpenRouter key is set and the cache is stale, fetchOpenRouterPricing runs in the background; rows display once pricingReady flips true."),
            HelpCard("Provider rows", "Show display name + total calls + total cost + ▾/▸ chevron. Tap toggles expansion. Expanded provider state is rememberSaveable across navigation to Model Info and back."),
            HelpCard("Per-model rows", "model id (white) + optional kind pill (rerank/summarize/compare/moderation/translate; report kind is hidden as the implicit default), call count + tokens or search-units, total cost, pricing-source tag color-coded (OVERRIDE=Orange, OPENROUTER=Blue, LITELLM=Purple, others=dim)."),
            HelpCard("Rerank rows", "Bill per search-unit, not per token. Token columns stay zero by design; per-query cost lands in the input column."),
            HelpCard("Title bar", "helpTopic=statistics. 🗑 trashcan visible only when stats are non-empty — opens \"Clear all statistics?\" confirmation."),
            HelpCard("Tips", "Tap a model row to drill into Model Info for that (provider, model)."),
            HelpCard("Pitfalls", "Legacy rows written before the kind field exist deserialize without one — SettingsPreferences.loadUsageStats backfills, but the row defends with `(swc.stat.kind as String?) ?: \"report\"` to keep the renderer safe."),
            HelpCard("Related", "Cost Config (Settings → Costs) edits the OVERRIDE tier; Refresh → OpenRouter / LiteLLM rebuild the curated tiers consulted here.")
        )
    ),
    "cost_config" to HelpContent(
        title = "Cost Config",
        cards = listOf(
            HelpCard("Overview", "Manual price-override list. Top button \"Add Manual Override\" opens AddManualOverrideScreen as a full-screen overlay. Empty state shows the lookup precedence (LiteLLM > OpenRouter > Default) — manual overrides slot into that chain after curated tiers."),
            HelpCard("Per-row card", "Provider name (blue), model id, current input/output prices in $/1M tokens. Two buttons in view mode: Remove (red) / Edit. Edit mode shows two input fields plus Cancel / Save."),
            HelpCard("Pricing precedence", "From PricingCache.getPricing: provider self-report → LiteLLM → models.dev → llm-prices → Artificial Analysis → manual override → OpenRouter fan out-provider fallback → Helicone → DEFAULT."),
            HelpCard("Title bar", "helpTopic=cost_config. Plain back."),
            HelpCard("Tips", "Stored as $/token internally — the form takes $/1M tokens and divides by 1,000,000 on save (and multiplies by it on edit-load)."),
            HelpCard("Pitfalls", "Manual override comes AFTER the curated tiers — if LiteLLM has a price, your override may not actually win. Use Housekeeping → Manual cost overrides cleanup to drop redundant entries."),
            HelpCard("Related", "Export/Import → Layered costs CSV is the bulk-edit path; Add Manual Override is the single-row form.")
        )
    ),
    "cost_override" to HelpContent(
        title = "Cost override (add / edit)",
        cards = listOf(
            HelpCard("Overview", "Form to add or edit one (provider, model, input $/M, output $/M) override. Reachable from Cost Config's Add button or directly from Model Info → \"Add manual cost override\"."),
            HelpCard("Provider button", "Outlined button opens SelectProviderScreen as a full-screen overlay. Default = first provider in AppService.entries."),
            HelpCard("Model row", "Free-text \"Model\" field plus a Select button that opens SelectModelScreen for the chosen provider. Either typing or picking works."),
            HelpCard("Reference price", "When provider+model are populated, a small dim line shows the current price the lookup would return WITHOUT the override (PricingCache.getPricingWithoutOverride) plus the source tier."),
            HelpCard("Save", "Disabled until provider + non-blank model + parseable input + parseable output. Numbers are $/1M tokens; saved divided by 1,000,000."),
            HelpCard("Title bar", "helpTopic=cost_override. Title \"Add Override\". Plain back."),
            HelpCard("Tips", "When opened from Model Info on an existing override, both price fields pre-populate from the saved values."),
            HelpCard("Pitfalls", "Negative prices and non-numeric input fail the Save gate without an explicit error message — the button just stays disabled."),
            HelpCard("Related", "Layered costs CSV → Import manual changed costs is the bulk equivalent.")
        )
    ),
    "trace_list" to HelpContent(
        title = "API Traces",
        cards = listOf(
            HelpCard("Overview", "List of every captured API trace. First open of the session populates ApiTracer's cache via streaming parse over the trace dir; subsequent opens are O(1). Title varies: \"Report Traces\" when scoped by reportId, \"Traces — <model>\" when scoped by model, \"API Traces\" otherwise."),
            HelpCard("Filter row", "Up to four slots in one row, each conditionally rendered: Category (when >1 distinct), Hostname (when >2), Provider (when >1), Model (when any pickable). \"(All)\" hides itself on the button label so chips read just \"Category\" instead of \"Category: (All)\"."),
            HelpCard("Errors-only", "Checkbox below the filter row when error count > 0. Filters to status 0 (transport) or >= 400 (HTTP)."),
            HelpCard("Auto-collapse", "When a deep-link arrives with preset filters and the resulting list has exactly one entry, the screen jumps straight into that trace's detail. Flag is rememberSaveable so popping back lands on the (single-entry) list, not the detail again."),
            HelpCard("Pagination", "Computed from BoxWithConstraints maxHeight (52dp per row, 130dp overhead). Prev/Next + position indicator only when total > 1 page."),
            HelpCard("Per-row", "Hostname (left), date/time (center, MM/dd HH:mm:ss), status code (right; green 2xx / orange 4xx / red 5xx / dim others)."),
            HelpCard("Title bar", "helpTopic=trace_list. 🗑 trashcan visible only when neither reportId nor modelFilter scope is active AND there is at least one trace — opens a \"Clear all traces?\" dialog."),
            HelpCard("Tips", "Picking a model uses a full-screen overlay (TraceModelPickerOverlay) since the option list can be too long for a popup menu."),
            HelpCard("Pitfalls", "PROVIDER_AUX_HOSTS maps Cohere's api.cohere.com onto the Cohere baseUrl host — without that the rerank API traces would land in \"(unknown)\". Extend the map for any provider with a similar second host."),
            HelpCard("Related", "Most 🐞 ladybug icons across the app deep-link here with category/model filters preset.")
        )
    ),
    "trace_detail" to HelpContent(
        title = "Trace detail",
        cards = listOf(
            HelpCard("Overview", "One trace's full request/response. Title shows status code; background turns dark red on >=300. Body content is rendered as a colored JSON tree (auto-expands depth 0 and 1) when valid JSON, otherwise plain text."),
            HelpCard("Top deep-links", "URL line beneath the title bar (centered, ellipsised). When the hostname resolves to a provider, a Provider button (blue) and possibly an Agent button (indigo, when a matching agent exists) appear. For Translation traces a \"Translation result\" button opens a side-by-side compare view."),
            HelpCard("View selector", "5 outlined buttons: All / Req Hdr / Rsp Hdr / Req / Rsp. Active button highlights blue. Switching view resets when navigating prev/next."),
            HelpCard("Bottom action row", "< (prev trace, fixed 36dp width) — Copy — Edit — Share — > (next). Copy and Share use a redacted variant (URL query params, sensitive headers, sensitive JSON keys → [REDACTED]) so secrets don't leave the device. The on-screen tree always shows raw bytes."),
            HelpCard("Edit", "Persists the trace's request body + url + model into eval_prefs, then opens EditApiRequestScreen with that JSON loaded — fast \"replay this request\" path."),
            HelpCard("Title bar", "helpTopic=trace_detail. ℹ️ visible when host resolves to a provider AND a model id is captured — opens Model Info for (provider, model)."),
            HelpCard("Tips", "Translation extraction looks for \"TEXT TO TRANSLATE:\" in the user prompt and walks OpenAI / Anthropic / Gemini response shapes to find the assistant text. Best-effort only — null hides the button."),
            HelpCard("Pitfalls", "Share writes a temp file under cacheDir/shared_traces/<filename> and grants FileProvider read access; long traces survive only as long as the cache survives.")
        )
    ),
    "trace_pick_model" to HelpContent(
        title = "Trace pick model",
        cards = listOf(
            HelpCard("Overview", "Full-screen overlay used by the trace list to pick one (provider, model) pair to filter on. Top card is \"(All models)\" — clears the filter when tapped."),
            HelpCard("Item rows", "Provider name in blue (small) plus model id in white (or blue when selected) below. Trailing ✓ marks the current selection."),
            HelpCard("Sourcing", "Options come from pickableModels in the parent — distinct (provider, model) pairs from traces in the currently scoped Category + Provider + Hostname subset. Picking an unpopulated combination is impossible."),
            HelpCard("Title bar", "helpTopic=trace_pick_model. Plain back returns to the trace list with no change."),
            HelpCard("Tips", "Sorted (provider, model) by lowercased name; ties broken by model id."),
            HelpCard("Pitfalls", "If you change the upstream Category / Provider / Hostname filters AFTER picking a model, your model selection may no longer match anything — the list will go empty until you clear it."),
            HelpCard("Related", "Reachable only from the trace list's Model launcher button.")
        )
    ),
    "developer_test" to HelpContent(
        title = "API Test",
        cards = listOf(
            HelpCard("Overview", "Developer fixture for hand-crafting an API call. Selects provider/endpoint/key/model/prompt/system/temperature/max_tokens, persists to eval_prefs, and opens Edit Request to send a real call."),
            HelpCard("Provider selector", "OutlinedButton cycles through AppService.entries on tap (round-robin). LaunchedEffect resets endpoint URL / key / model fields to the new provider's defaults whenever the provider changes."),
            HelpCard("Endpoint", "Free-text URL plus a \"...\" picker that opens a dialog over the provider's configured Endpoints (default + per-config). Picking sets the URL field."),
            HelpCard("Model picker", "\"...\" button calls AnalysisRepository().fetchModels in a loading dialog, then shows a scrolling list of model ids — tap to pick."),
            HelpCard("API Parameters card", "Collapsible — System Prompt (multi-line), Temperature, Max Tokens. Optional fields."),
            HelpCard("Build Request", "Green button at bottom. Clears any previous raw_json so EditApiRequestScreen builds fresh JSON from the form. Always remembers everything else for next session."),
            HelpCard("Title bar", "helpTopic=developer_test. Plain back."),
            HelpCard("Tips", "Reachable from Hub → Developer (long-press / hidden flag depending on build); not part of the regular Settings tree."),
            HelpCard("Pitfalls", "If the endpoint URL doesn't match an actual chat completion path on the chosen provider's host, the call will get a 404 — the form does not validate the URL.")
        )
    ),
    "developer_edit" to HelpContent(
        title = "Edit Request",
        cards = listOf(
            HelpCard("Overview", "Raw JSON editor for an API request. Loads either the captured request body of a previous trace OR a freshly-built body from the API Test form (Gson pretty-printed). Provider/model/url come from eval_prefs."),
            HelpCard("Info card", "Top non-editable card with \"<provider> / <model>\" and the endpoint URL — sanity check before submitting."),
            HelpCard("Editor", "OutlinedTextField with monospace font occupies the rest of the screen. Edits are local until Submit."),
            HelpCard("Submit", "Turns tracing on for this single call regardless of the global setting (and restores it after), runs AnalysisRepository.testApiConnectionWithJson, then jumps to TraceDetail for the new trace if one was captured. If nothing new came in, just toasts \"Request sent. Check traces.\""),
            HelpCard("Title bar", "helpTopic=developer_edit. Plain back."),
            HelpCard("Tips", "This bypasses the Retrofit serialization layer entirely — perfect for testing edge-case body shapes a provider's docs claim to support."),
            HelpCard("Pitfalls", "API key for the call comes from eval_prefs and is also sent in plain text via the standard provider Authorization scheme; if you mis-typed it on the test screen the failure looks like a 401 in the trace, not a UI error here."),
            HelpCard("Related", "Trace Detail → Edit jumps here pre-populated with the trace's request — fast \"replay with tweaks\" loop.")
        )
    ),
    "provider_admin" to HelpContent(
        title = "Provider administration",
        cards = listOf(
            HelpCard("Overview", "One row per provider showing state plus its configured Admin URL. Tap a row to open the URL in the system browser — useful for sign-up, top-up, key rotation, or usage check."),
            HelpCard("Sort order", "ok first, then error, then not-used, then inactive. Within each bucket alphabetical by display name. Counts every registered provider, not just active ones."),
            HelpCard("Per-row card", "Display name in white + state emoji on the right (🔑 ok / ❌ error / 💤 inactive / ⭕ not-used / unknown for anything else). Admin URL line beneath in blue underlined monospace, or \"(no admin URL configured)\" when blank."),
            HelpCard("Tap behavior", "Fires Intent.ACTION_VIEW with the URL. Toast on missing URL or browser-launch exception."),
            HelpCard("Title bar", "helpTopic=provider_admin. Plain back."),
            HelpCard("Tips", "Reached from AI Setup → Providers → Provider administration."),
            HelpCard("Pitfalls", "Admin URL is part of the catalog — if it's wrong / missing for a custom provider, edit it under Provider edit → Definition · Basics. Edits to that field flow into the catalog directly; there is no longer a separate per-config override layer.")
        )
    )

)
