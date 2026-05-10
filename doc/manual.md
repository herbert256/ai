# User Manual

A multi-provider AI app for Android. Run the same prompt against many
models at once, compare what they say, fan one model's response into
another's prompt, ground the run in your own documents, save the
result, share it, and keep an audit trail of every API call you made
— cloud or on-device.

## First run

1. Install the APK and open it. The app imports a default catalog of
   42 cloud providers from a bundled `providers.json` and seeds
   Internal Prompts (Meta / Fan-out / Fan-in / fixed templates) from
   `prompts.json` so you don't have to type any URLs or prompt
   templates yourself.
2. Open **Settings → AI Setup → Providers**. Pick the providers you
   want to use, paste in their API keys (each card has a 🔗 link to
   that provider's console), and tap **Test API Key**. A successful
   model-list fetch + key test marks the provider as 🔑 and adds it
   to **Active**. Activation is gated on both passing — a stale
   model list never lets a misconfigured provider count as active.
3. Optionally, paste a HuggingFace token, OpenRouter token, and/or
   Artificial Analysis key under **AI Setup → External Services**.
   None of these are required to use the app — they only enable model
   metadata, pricing, and intelligence/speed scores.

The on-device LLM and on-device embedder paths require **no API key**
— they run a `.task` or `.tflite` model file you supply. See the
"Local Runtime" section below.

## The Hub

The home screen has these big cards:

- **AI Reports** — multi-model reports with rerank / chat-meta /
  fan-out / moderate / translate.
- **AI Chat** — single-model conversation.
- **AI Knowledge** — knowledge bases for retrieval-augmented
  generation. Attach to Reports / Chats. (Hidden by default on
  fresh installs — flip **Settings → Show AI Knowledge card on
  home page** to surface it. The Knowledge flows themselves stay
  fully functional whether the card is visible or not.)
- **AI Models** — search every model across all your providers.
- **AI Usage** — running token / cost statistics.
- **AI API Traces** — every recent API call (cloud or local) as a
  JSON file. (Hidden when API tracing is disabled in Settings.)

Plus shortcut buttons for **Settings**, **Housekeeping**, **AI
Setup**, **Help**.

The Hub logo doubles as a one-tap shortcut to the most recent
report's result page.

## Reports

Reports are the killer feature. A report = one prompt run against many
models in parallel.

### AI Reports hub

Tapping **AI Reports** lands on a hub screen with several cards:

- **Start** — `New AI Report`, `Start with an example prompt`,
  `Start with a previous prompt`.
- **Pinned** (when present) — pinned reports surface above Recent so
  the ones you keep coming back to are one tap away.
- **Recent** — compact list of the last few reports with a 🕐 icon.
  In-flight runs show a tiny spinner.
- **Search** — three options:
  - **Quick local search** — fast keyword scan across saved report
    prompts and bodies.
  - **Extended local search** — slower, broader scan (also into
    secondaries / translations).
  - **Remote semantic search** — embed your query against any chat
    provider and rank reports by cosine similarity.
  - **Local semantic search** — same idea, but the embedder is the
    on-device `LocalEmbedder` — no network.
- **Manage** — bulk housekeeping (pin/unpin, delete, export many).

### Selection phase

1. Tap **New AI Report**. You land on the model selection screen.
2. Add models to the report using the buttons at the top:
   - **+Agent** — full-screen Agent picker with rich rows.
   - **+Flock** — full-screen Flock picker (with Edit + One-time
     entry points right on the picker).
   - **+Swarm** — full-screen Swarm picker (with Edit + One-time).
   - **+Provider** — pick any provider (including the synthetic
     **Local** provider for on-device `.task` models), then any of its
     models.
   - **+Model** — full-screen multi-select picker across every active
     provider's catalog. The **+Report** entry only appears once an
     existing report is selectable.
3. Optionally tap **Params** to apply a parameter preset (temperature,
   max_tokens, system prompt, reasoning effort, etc.).
4. Optionally tap **📚 Knowledge** to attach one or more knowledge
   bases — every agent call gets a context block prepended for the
   prompt.
5. Optionally attach a vision image (📎), toggle web-search 🌐, or
   pick a reasoning level 🧠.
6. Type your prompt and tap **Generate**.

### Generation phase

While the report runs:
- A progress bar shows X/Y completed.
- Each agent's status icon spins ⏳ until the call finishes (✅ / ❌).
- Tap **Background** to send the run to the background and continue
  using the app — you'll get a toast when it's done.
- Tap **STOP** to cancel.

### Result phase

When the report is complete, the top of the screen carries a single
collapsed action card with three rows:

- **View** — Results / Prompt / Costs / Trace, plus one button per
  Meta-prompt name with at least one row on this report.
- **Edit** — Prompt / Title / Models / Parameters. Edits queue up;
  tap **Regenerate** in the Actions row to re-run with the changes.
  When only the model list changed, Regenerate is **additive** — it
  runs only the new models and merges them in.
- **Actions** — Regenerate / Export / Copy / Translate / Delete /
  Rerank.
- **Meta** — one orange button per Meta prompt configured under AI
  Setup → Prompt management → Meta prompts. Empty state is hinted
  when no Meta prompts exist.

The TitleBar above the result screen carries a 💬 Chat icon that
starts a new chat session pre-populated with the report's prompt.
The footer row mirrors the agent-row layout and shows the report's
total cost on the right.

### Meta prompts and Translate

Meta-result flows that operate on a finished report's outputs.
The available Meta buttons are entirely user-driven via the
Meta-prompt CRUD: Settings → AI Setup → **Prompt management →
Meta prompts**. A typical setup:

- **Compare** — chat-type prompt asking the model to identify where
  responses agree, where they diverge, and what each one uniquely
  contributed. Tick **reference** on the entry to get a deterministic
  `[N] = Provider / Model` legend appended automatically.
- **Critique** / **Synthesize** / etc. — any chat-type analysis
  you want; the prompt name becomes the button label and the
  view tab name in exports.
- **Rerank** — pick a `type=rerank` Meta prompt (or a chat-type
  one if you'd rather use a chat model) to rank responses 1..N
  with a score and reason. Rerank-typed entries route through the
  provider's dedicated rerank endpoint when the picked model
  supports it (Cohere `/v2/rerank` is wired today). Action row
  also has a dedicated **Rerank** button.
- **Moderation** — `type=moderation` Meta prompt that runs the
  report's responses through a provider's `/moderations` endpoint
  and shows the flagged-categories table.

Translate is a separate Actions button (not a Meta prompt) — it
translates the prompt and every successful agent response (plus any
chat-type Meta rows in scope) to one or more languages, fanning out
one API call per language × source. See
[translation.md](translation.md) for the full flow.

You can run any of them multiple times per report — each run is a
separate, independently viewable, independently deletable entry.
Once results exist, the View row gains a button per Meta prompt
name that has at least one row on this report (e.g.
**Compare (k)**, **Critique (n)**), plus **Reranks / Moderations /
Translations** buttons for those structured kinds.

#### Scope step

For chat-type Meta prompts and Translate, a **scope** screen shows
up to let you narrow what gets fed in:

- **All model reports** (default) — feed every successful response.
- **Only top ranked reports** — narrow to the top-N entries of a
  chosen rerank (when the report has at least one rerank).
- **Manual selection** — explicitly pick which agents to feed in.
- For chat-type Meta runs and Translate on a report that already
  has translations, you can also restrict to **All present
  languages** or **Selected** specific languages. See
  [translation.md](translation.md).

The chosen scope is **encoded onto each row** at run time, so a
cascade-on-prompt-change re-runs at exactly the same scope rather
than silently widening to AllReports. Then **Continue** lands on
the model picker. Rerank / Moderation–typed Meta prompts skip the
scope screen — they always operate on the full set.

### Fan out / Fan in

A separate flow that turns one report into many — sitting under
its own **Fan out** card on the Result page (and a sibling **Fan
in** action once fan-out rows exist).

1. **Run a fan-out** — pick a Fan-out prompt (CRUD'd under AI
   Setup → Prompt management → **Fan-out prompts**). The flow
   runs a single combined card: the prompt picker is hoisted
   above the scope card, the answerer + source cards are
   collapsed into one. The popup confirmation reads as
   "N reports × M responses = pairs".
2. The runtime fans out one API call per (answerer, source) pair
   — each `@RESPONSE@` placeholder in the template is replaced
   by the source response text. Concurrency is capped at 3 per
   provider so even 6 reports against one provider keeps three
   in flight; against 6 different providers all 18 run
   concurrently.
3. **Drill in** — three levels deep:
   - **Level 1** lists one row per answerer with progress bars,
     ✅/❌ status, per-row cost, and a Total banner. Empty-body
     successes count as Done. The Actions card collapses
     Resume stale / Restart failed / Rerun complete / Delete.
   - **Level 2** lists one row per (answerer, source) pair,
     virtualised so long lists scroll smoothly.
   - **Level 3** is the single-response detail with a 🐞 link
     to the original report-model trace.
4. **Run a fan-in** — pick a Fan-in prompt (under AI Setup →
   Prompt management → **Fan-in prompts**) to combine every
   per-pair row into a single combined-report row. The
   `***Report*** @REPORT@@RESPONSES@` block in the template
   expands once per source agent.

After-fan-out runs surface as standalone secondary rows on the
Report Result and inside the Fan-out drill-in.

### Export

Tap **Export** in the Actions row. Choose:

- **Format**: HTML, JSON, PDF (print the HTML), DOCX (Word), ODT
  (LibreOffice), RTF, or **Zipped HTML** — a self-contained HTML
  site (one folder per language, anchored cross-links, embedded CSS).
- **Detail**: full per-agent results vs. condensed.
- **Sections**: include / exclude prompt, costs, citations, traces.
- **Action**: share-sheet, email, or open in a browser.

The HTML export contains:
- A toggle to switch between **One by one** (tabbed) and **All
  together** (grid card layout).
- The original prompt and a Costs table with **By type** and **By
  model** rollup tables. Costs Type column reads the Meta-prompt
  name lowercased — `compare`, `critique`, … — for chat-type
  rows; structured kinds keep their fixed labels: `rerank`,
  `meta`, `moderation`, `translate`.
- Stable `result-N` anchors on each agent's card.
- One view-picker tab per chat-type Meta prompt name (e.g.
  Compare / Critique / Synthesize), plus Reranks / Moderations /
  Translations tabs for the structured kinds. Rerank entries
  render as a linked rank table; chat-type Meta references like
  `[3]` are clickable jumps back to that agent's card.
- Markdown tables (GFM pipe-style) render as proper HTML tables in
  the in-app viewer and in every export.

### Edit / regenerate

After a report has finished, you can tweak the prompt, the title, the
model list, or the parameters and re-run. A pending-changes banner
appears at the top of the result screen until you tap **Regenerate**.
- Regenerate stays on the same report and **cascades by impact**:
  prompt edits run all agents fresh; parameter edits do too;
  model-list-only edits run just the additions / changes and merge
  them in.

## Chat

Single-model conversation with full history. Pick a provider+model at
the top, type messages, get streaming replies. Sessions are
auto-saved.

### AI Chat hub

Like the Reports hub, the Chat hub is rich:

- **Start** — `New AI Chat`, `Configure on the fly`
  (`ModelSearchScreen` for fast picking), `Chat with a local LLM`
  (only when at least one `.task` file is installed),
  `Dual AI Chat`.
- **Pinned chats** (when present).
- **Recent** — last few chats with the 🕐 icon.
- **Unfinished** pill — when a chat from a previous session was left
  mid-turn, a one-tap resume.
- **Search** — full-text scan across saved chat sessions.
- **Manage** — bulk housekeeping.

A chat session can have:
- A vision image attached per turn (📎). Images are downscaled and
  JPEG-encoded before base64 to keep transit + storage size low.
- The 🌐 web-search tool toggled per provider.
- The 🧠 reasoning-effort selector per turn (clamped to the active
  model's supported range on session resume).
- One or more knowledge bases attached (📚) — every user turn the
  retriever pulls top-K chunks across the attached KBs and prepends a
  context block to the system message.

A mid-session system-prompt change takes effect on the next turn,
not just on a fresh session.

## Dual Chat

Two models in conversation with each other. You define two prompt
templates that reference each other's output (`%subject%`,
`%answer%`), pick a subject, and an interaction count. The first
model answers about the subject; the second responds to the first;
they take turns until they hit the count. Useful for adversarial
cross-examination, devil's-advocate setups, or multi-step pipelines.
Conversations persist across rotation and process recreation.

## Knowledge (RAG)

The **AI Knowledge** card on the Hub manages knowledge bases. A
knowledge base is a named collection of source documents that have
been chunked + embedded so that report and chat calls can prepend
the most-relevant excerpts to the prompt.

### Creating a KB

1. **AI Knowledge → + New knowledge base**.
2. Give it a name and pick an embedder. Embedders fall in two
   categories:
   - **Local (LiteRT)** — on-device MediaPipe TextEmbedder
     (universal-sentence-encoder-lite by default; you can add other
     `.tflite` models).
   - **Provider (e.g. OpenAI / text-embedding-3-small)** — any active
     provider with an embedding model.
3. Tap Create.

### Adding sources

Inside a KB:
- **+ File** opens a SAF picker that accepts:
  - PDFs (with OCR fallback for image-only PDFs)
  - Word documents (`.docx`, `.odt`)
  - Spreadsheets (`.xlsx`, `.ods`, `.csv`, `.tsv`)
  - Images (`.jpg`, `.jpeg`, `.png`) — Latin OCR via ML Kit
  - Plain text and Markdown (`.md`)
- **+ Web page** fetches a URL with Jsoup and ingests the visible
  text.

Every source goes through a per-type extractor → paragraph chunker
(2 KB target, 200-char overlap) → embedder. Status reports
"Extracting…" → "Embedding batch N/M…" → "Indexed (chunks=K)".
KB stores the source file locally with an atomic temp + fsync +
rename so a crash mid-ingest can't leave a half-written copy.

### Attaching a KB to a Report or Chat

- **Reports**: on the New AI Report screen, tap **📚 Knowledge** to
  multi-select KBs. Every agent call gets a context block prepended
  to the prompt with the top-K most-relevant chunks (cosine similarity
  against the prompt's embedding).
- **Chats**: on the Chat parameters screen (or via the chat session's
  own 📚 button), pick KBs the same way. Every user turn gets a
  fresh top-K retrieval and the context block goes in front of the
  system message.

### Share-target

Other apps can share documents into AI. From any app's share sheet,
pick "AI"; you'll get a chooser screen with three destinations:
**New Report**, **New Chat**, **Add to Knowledge**. The Knowledge
path drops the shared file(s) or URL(s) into the next KB you tap.
See [share-target.md](share-target.md).

### Deeper

See [knowledge.md](knowledge.md) for the full RAG pipeline: data
model, ten extractors, chunker, retriever, on-disk layout, and the
embedding cache.

## Local Runtime

Two on-device subsystems live alongside the cloud providers:

- **Local LLM** (`LocalLlm`, MediaPipe Tasks GenAI) — runs a `.task`
  bundle (Gemma, Phi, Llama …) for chat or report generation. The
  Local provider appears in every model picker as a normal provider;
  the Hub's "Chat with a local LLM" card is a shortcut.
- **Local Embedder** (`LocalEmbedder`, MediaPipe Tasks TextEmbedder)
  — runs a `.tflite` text embedder for Local Semantic Search and as
  an embedder option when creating a Knowledge base. A default
  Universal Sentence Encoder is downloaded on first use.

Both record synthetic API trace entries (hostname `local`,
url `local://generate/<model>` or `local://embed/<model>`) so
on-device traffic shows up on the Trace screen alongside HTTP
calls.

Maintenance lives under **AI Setup → Local Models** (broken into
"Local LiteRT models" + "Local LLMs" cards) — they're configuration
of on-device runtimes, not housekeeping. See
[local-runtime.md](local-runtime.md) for the full story.

## Models

A flat searchable view of every model across every active provider.
Filter by provider, by capability (vision / web-search / function
calling / reasoning), or just by name. Tap a model for the **Model
Info** screen.

### Model Info

Six cards stacked top-to-bottom:

1. **Actions** — Start AI chat • Create AI Agent.
2. **Capabilities** — vision / web-search / function-calling /
   reasoning toggles plus the underlying signals from each layer.
3. **Provider** — provider's display name links to the per-provider
   help page.
4. **Sources** — buttons that open the model's page on each external
   repository (HuggingFace / OpenRouter / LiteLLM / models.dev /
   Helicone / llm-prices / Artificial Analysis), each with an ℹ
   button next to it that deep-links to that repository's help
   page. **Show all** opens a side-by-side raw-JSON dump of every
   source.
5. **Costs** — input / output prices from each tier and the resolved
   layered price.
6. **API Traces** — every API call to this provider+model that's
   still on disk, filtered by hostname so unrelated traces don't
   pollute the count.
7. **Last usage** — running cost+token stats for this model with
   an AI Usage counter pulled from `usage-stats.json`.

The model name is the page subject; the Provider card sits under
Capabilities so users start by understanding what the model can do
before they navigate to provider-level admin.

Standalone model-name labels across the app are clickable and open
the Model Info screen. The "Model name layout" Settings preference
controls whether labels show only the model id or both provider
and model.

## AI Usage

A breakdown of every API call you've made. Provider cards expand into
per-model rows; each row has a small Type pill when it isn't a normal
report call (`rerank`, `meta`, `moderate`, `translate`, fan-out
prompts surface under their own Meta-prompt name). The Cost column
is coloured by which tier supplied the price.

## API Traces

Every API call (request + response) is dumped as a JSON file under
`<filesDir>/trace/`. The Trace screen lets you browse them by
hostname, status code, model, or the report they belonged to.
Local LLM and local embedder calls are traced too with hostname
`local`. The Trace list **auto-collapses to detail when filters
yield a single entry**.

The Trace detail's TitleBar carries 🗑 (delete) and 🔄 (refresh)
icons. The detail page surfaces the request body's first line in a
Get-style preview, with an ℹ icon that falls back to the provider
help when no per-model help exists.

API tracing is **on by default** but can be toggled off under
Settings → API tracing — when off, no new traces are written, the
Hub's AI API Traces card is hidden, and every 🐞 ladybug icon
disappears from result screens.

## Settings

The **Settings** main screen carries per-card preferences (each in
its own card):

- **Identity** — name + email (one card; the email pre-fills the
  export sheet).
- **API tracing** — master switch.
- **Model name layout** — model only / provider and model.
- **Subject to title bar mode** — tri-state. **HARDCODED** keeps the
  legacy fixed label + green sub-header; **SUBJECT** folds the
  dynamic subject into the TitleBar and drops the green line;
  **BOTH** joins them with `/` and drops the green line.
- **Show < Back** — when off the visible Back button hides; system
  / gesture back still works.
- **Icon bar at bottom** — when on, the action icons + back arrow
  move into a bar pinned at the bottom of the screen; the top bar
  shows only the title. The bar lives at AppNavHost scope so it
  survives nav transitions.
- **Icon-gen** — master switch for the per-report emoji-generation
  feature. When on (default) every new report kicks off a
  background LLM call that picks a fitting emoji; the icon row
  appears on the result page and the dynamic emoji shows in title
  bars / hub list / history / search hits, with a 📝 memo icon
  mirroring it. When off, the call is skipped and per-row icons
  fall back to the static 🕘 / 📌. Existing icons stay on disk
  for re-enable.
- **Show AI Knowledge card on home page** — gates the Knowledge
  card on the Hub. Default off; toggle on once you start using KBs.

Edits debounce 400 ms and flush on screen leave so a quick back
doesn't lose typed changes.

### AI Setup hub

| Card | What it does |
|---|---|
| Providers | API keys, state, and default model per provider |
| Models (sub-hub) | Models / Model Types / Manual model types overrides |
| Workers (sub-hub) | Agents / Flocks / Swarms |
| Prompt management (sub-hub) | System Prompts / Internal Prompts (Meta + Fan-out + Fan-in + Other) / Example prompts |
| Parameters | Reusable parameter presets (incl. reasoning effort) |
| Costs | Manual price overrides + Cleanup + Layered costs (collapsed at the bottom) |
| External Services | HuggingFace / OpenRouter / Artificial Analysis keys (debounced keystroke saves; flush on dispose) |
| Local Models | Local LiteRT models (.tflite) and Local LLMs (.task) |
| Refresh | Per-tier refresh + Refresh all chain |

> **Note:** Anything user-driven that runs on a report's outputs
> (Compare, Critique, Synthesize, …) is configured under **Prompt
> management → Meta prompts**. Fan-out / Fan-in templates live
> under their own siblings; "Other internal" (intro / model_info /
> translate / rerank / moderation) is a fixed list with no
> Add / Delete.

### Refresh

A dedicated screen for refreshing the seven external repositories.
Each card has its own button; **Refresh all** runs a full-screen
progress page that fetches catalogs in parallel, then re-tests
every active provider in dependency order, and finally auto-restarts
the app to pick up the freshly-persisted caches. Refresh all skips
the default-agent re-test (it trusts the catalog-fetch results)
and lists any failed providers with a one-tap nav-to-edit.

### Housekeeping

A compact landing screen with six NavCards — each one drills into
its own full screen with its own help topic.

| Card | What it does |
|---|---|
| Backup & Restore | Export the entire app to a `.zip`; restore from one |
| Export & Import | Granular bundles for Settings / Model lists / Parameters / System prompts / Workers / Costs CSV / Prompts JSON / All (with or without API keys) |
| Refresh | Hand-off to the per-tier Refresh screen |
| Trim by age | Drop reports / chats / traces older than a chosen cutoff |
| Usage statistics | Reset the per-(provider, model, kind) counters |
| Reset | Three full-reset variants (clear runtime data / clear configuration / reset application) plus per-section bundled-asset reseed buttons |

The Reset card holds:
- **Clear all runtime data** — wipes reports, chats, traces, KBs,
  the embeddings cache, the local-semantic-search cache, the
  knowledge / pricing / model-list caches.
- **Clear all configuration** — wipes provider config, prompts,
  Local LLMs, LiteRT models. Asks before destructive actions.
- **Reset application** — factory-style reset that preserves API
  keys (written to a temp file, restored after the wipe), then
  runs the full Refresh-all chain at the end.

### Export / Import

Granular sub-bundles plus an All bundle (split into "with API
keys" and "without API keys" so you can share configs without
leaking secrets). The Workers bundle round-trips agents + flocks
+ swarms together; Settings / Model lists / Parameters / System
prompts / Costs CSV / Prompts JSON each have their own button.
CSVs use RFC-4180 quoting on export and a tolerant RFC-4180 parser
on import.

## Help

Every screen's TitleBar carries a ❓ that opens a per-screen help
topic. Provider cards on Model Info / Trace detail / Costs carry
ℹ buttons that deep-link to a per-provider help page covering
that provider's setup, capabilities, quirks, and known issues.

The Help home page surfaces an icon legend rendered as a 3-column
table — every TitleBar icon you'll see in the app, with a one-line
description.

## Tips

- **Reports run in parallel up to 4 at a time.** Bigger reports
  honour that limit, so you can queue 12 models without hammering any
  single provider. Fan-out caps at 3 per provider, so a fan-out
  spread across many providers can run more concurrently than a
  single-provider run.
- **Rate limits**: a 429 from any provider is automatically retried
  with a short back-off, capped at a few attempts; permanent 4xx
  failures and cancellations bail immediately. Each retry is a
  separate trace with the originating call's `(reportId, category)`
  tags propagated.
- **Vision attachments** are stored on the Report so a Regenerate
  re-uses the same image. Images are downscaled + JPEG-encoded
  before base64.
- **Reasoning effort** (low / medium / high) is plumbed through to
  models that support it (gpt-5.x / o-series via OpenAI Responses
  API; Gemini thinking models). Non-reasoning models silently ignore
  the field; on chat session resume it's clamped to the active
  model's supported range.
- **External intent**: another app can launch this one with a
  prompt, a list of agents/flocks/swarms/models, and an action
  ("view", "share", "browser", "email"). See the in-app **Help**
  screen for the full intent contract — and
  [share-target.md](share-target.md) for the standard
  `ACTION_SEND` flow.
- **Multiple translation runs at once** — the Translate flow lets
  you fire off several language batches concurrently; results land
  in their own rows on the Result screen.
