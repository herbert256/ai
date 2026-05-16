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
- **AI Chat** — single-model conversation. Chat titles are
  AI-generated (the bundled `chat_title` prompt fires after the
  first assistant response); a first-10-words fallback fills the
  row instantly so nothing is ever blank.
- **AI Knowledge** — knowledge bases for retrieval-augmented
  generation. Attach to Reports / Chats. (Hidden by default on
  fresh installs — flip **Settings → Show AI Knowledge card on
  home page** to surface it. The Knowledge flows themselves stay
  fully functional whether the card is visible or not.)
- **AI Models** — search every model across all your providers.
- **AI Usage** — running token / cost statistics.
- **AI API Traces** — every recent API call (cloud or local) as a
  JSON file. (Hidden when API tracing is disabled in Settings.)
- **AI App log** — daily-rotating in-app log file with a
  searchable / filterable viewer ([applog.md](applog.md)). Useful
  when you want to hand a clean log to support without
  needing `adb logcat`.

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
- **Existing reports** — combined card with Pinned, Recent, and a
  `View previous reports` row in one place. Pinned reports appear
  first (when present), then the most recent few. Every row's
  leading icon is the report's **generated emoji** (or the
  static 🕘 / 📌 when icon-gen is off or the call failed). The
  card is styled to match Start so the hub reads as two parallel
  entry points.
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
- The full action row (Regenerate / Export / Copy / Translate /
  Delete / Rerank) is available from the moment Generate is
  tapped — you can navigate away and the run **continues in the
  background** on `appViewModel.viewModelScope`. Coming back to
  the screen recovers stale placeholders and shows finished
  rows; a toast confirms completion if you stayed elsewhere.
- Tap **STOP** to cancel.

#### Per-report icon

Right after Generate the app fires a background call to pick a
fitting emoji for the report. The icon appears as the leftmost
title-bar glyph and as the row icon on every list that
references the report. If the call fails it shows ❌ with the
error reason — the row stays usable; the icon just isn't there
yet. Toggle this off under **Settings → Generate report icons**.

#### Per-agent icons

If **Generate per model icons** is on (default), each successful
agent call kicks off a 3-tier icon-generation chain — chat
continuation against the agent's own model, one-shot template,
fixed-agent fallback. The result becomes the row's emoji on the
**Icons** view (Create → View → Icons). Costs accumulate on the
row's cost cell and show up under the Costs view's per-call
**All** tab. See [report-icons.md](report-icons.md).

### Result phase

When the report is complete, the top of the screen carries a
two-tier toggle action bar:

- **View** — Results / Prompt / Costs / **Icons** / Trace, plus
  one button per Meta-prompt name with at least one row on this
  report. The **every:** kinds (rerank / moderation / translate)
  fold into the View row with a smart drill-in.
- **Edit** — Prompt / Title / Models / Parameters. Edits queue
  up; tap **Regenerate** in the Actions row to re-run with the
  changes. When only the model list changed, Regenerate is
  **additive** — it runs only the new models and merges them in.
- **Create** — Regenerate / Export / Copy / Translate / Delete /
  Rerank and other generators including **Report icons** when
  per-model icons are enabled.

The TitleBar above the result screen carries a 💬 Chat icon that
starts a new chat session pre-populated with the report's
prompt, a 📋 Copy icon, and a 📤 Share icon. The leftmost
title-bar glyph is the report's generated emoji (when
icon-gen is enabled). The footer row mirrors the agent-row
layout and shows the report's total cost on the right; a
**Costs from deleted items** line surfaces above the Total when
non-zero so deleting rows doesn't lose visibility into what the
API actually billed.

#### View → Icons

Minimal grid of every agent's generated emoji. Tap a glyph to
open that agent's **Model response** detail screen; back returns
to the grid. Grid spacing adapts down when not every icon fits
at the default size.

#### Per-agent prev/next on Model response

Per-agent detail screens carry **Prev / Next** chevrons under
the title bar that walk the agent list by model name. The
chevrons live tight against the cost column so the eye stays in
one place as you scan.

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
   - **Level 1** lists one row per answerer. A pinned stats grid
     spans **Total / Running / Queued / Throttled / Done / Errors /
     Costs**; each row's background acts as a per-row progress bar.
     Empty-body successes count as Done. Action buttons live on one
     row — **Show icons** flips into fan-icons mode, the **One-page
     view** drills into a flat per-pair list.
   - **Level 2** lists one row per (answerer, source) pair,
     virtualised. A **Switch role** button toggles between
     Responder mode (one answerer, list of sources) and Source mode
     (one source, list of answerers).
   - **Level 3** is the single-response detail. The ℹ️ / 🐞 icons
     follow the *other* model in the pair so one tap goes to the
     right Model Info / trace; left-swipe goes to the next response,
     right-swipe to the previous.
4. **Fan-icons mode** — tap **Show icons** on L1 to flip the drill-in
   into a parallel emoji chain. Each completed pair gets a 3-tier
   icon-generation run; the icons batch is gated by its own
   concurrency cap (`maxConcurrentFanIconsCalls`, default 15) and
   costs surface on a dedicated `fan-icons` row in the Costs view.
   A **Clear fan-icons** button wipes icon state without deleting
   pairs. Re-open the report mid-batch and the icons batch
   auto-resumes on screen entry.
5. **Run a fan-in** — pick a Fan-in prompt (under AI Setup →
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
- **Action**: share-sheet, email, or **open in app** (an in-app
  WebView preview of the Complete/Short HTML — handy on phones
  where the system browser handles `file://` poorly). Short
  detail and Complete both honour the inline-preview path.

The 📤 share-as icon on the title bar of result-screen siblings
(per-agent detail, costs view, secondary results, …) opens the
same Export sheet on those scopes.

The HTML export contains:
- A toggle to switch between **One by one** (tabbed) and **All
  together** (grid card layout).
- The original prompt and a Costs view with three in-page tabs:
  **By type** rollup, **By model** rollup, and **All** — every
  individual call as its own row (including failed earlier tiers
  of the per-agent icon chain, the per-report icon-gen call,
  fan-out / fan-in rows, translation calls). Costs Type column
  reads the Meta-prompt name lowercased — `compare`, `critique`,
  … — for chat-type rows; structured kinds keep their fixed
  labels: `rerank`, `meta`, `moderation`, `translate`, `icon`.
  Provider and Model are split into separate columns; the
  summary tabs include a **Calls** column. The Total row uses
  the 💰 icon; a `deleted` row surfaces non-zero
  `costsFromDeletedItems` alongside the active rows.
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

The Model Info page paints immediately and every card loads in
parallel so a slow external lookup never gates the rest:

1. **Actions** — Start AI chat • Create AI Agent • **Test** (fires
   one probe against this model, captures the trace, and surfaces
   the response inline).
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
7. **In AI configuration** — Agents whose `(provider, model)` match
   this entry; tap to edit.
8. **Workers** — same agent list framed as workers (helps users who
   build per-flock setups think in terms of who's already wired up).
9. **Last usage** — running cost+token stats for this model with
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
yield a single entry**. When the trace was captured inside a
specific report, the icons-grid render appears in the trace
detail so you can recognise the run at a glance.

The Trace detail's TitleBar carries 🗑 (delete) and 🔄 (refresh)
icons. The detail page surfaces the request body's first line in a
Get-style preview, with an ℹ️ icon that falls back to the provider
help when no per-model help exists.

API tracing is **on by default** but can be toggled off under
Settings → Privacy & backup → API tracing — when off, no new
traces are written, the Hub's AI API Traces card is hidden, and
every 🐞 ladybug icon disappears from result screens.

## AI App log

A daily-rotating in-app log file with a structured viewer
(Hub → AI App log). Useful when you want to send a clean log to
support without needing `adb`. The viewer offers search, level
checkboxes (default WARN + ERROR), time-range pickers, and a tag
dropdown. Tap any row to open the entry detail, with a 🐞 Trace
link when the entry's tag + timestamp match a captured API
trace. The Copy/Share dialog asks Filtered-only vs whole file
and Last-N-lines vs Complete. Sensitive headers and API keys are
redacted inline at write time, so a shared log never carries
plain secrets. See [applog.md](applog.md).

## Settings

The **Settings** main screen is split into three sub-screens.
Each card on the main screen is collapsed by default and shows
only the title; tapping expands it.

### Preferences

- **Identity** — name + email (the email pre-fills the export sheet).
- **Model name layout** — model only / provider and model.
- **Title bar** — the top bar is the unified three-section layout:
  the **AI** logo (left, vivid Material A700 blue, alpha 0.75) and
  the **❓ Help** icon, the centred per-report icon (when in scope),
  and the hardcoded screen title (right, top-aligned). Below the
  bar, a single green-coloured 26 sp subject row (the
  **HARDCODED** subject row, used app-wide via `HardcodedSubjectRow`)
  shows the dynamic per-screen subject and is clickable into Model
  Info when a `(provider, model)` is supplied.
- The action icons + back arrow always live in a fixed bar
  pinned at the bottom of the screen. The bar lives at
  AppNavHost scope so it survives nav transitions.
- **Generate report icons** — master switch for the per-report
  emoji. Default on. See [report-icons.md](report-icons.md).
- **Generate per model icons** — master switch for the per-agent
  3-tier icon chain. Default on.
- **Show AI Knowledge card on home page** — default off.

### Privacy & backup

- **API tracing** — master switch for `ApiTracer`.

### Network

- **Streaming read timeout (s)** — read timeout for SSE chat /
  report streams (default = ~10 min). Shrink it on flaky networks.
- **Non-streaming read timeout (s)** — read timeout for analyze /
  meta / rerank / translate / model-list calls. Default is much
  shorter than streaming so a hung provider can't gate a whole
  batch for 10 minutes.
- **Max calls per provider per minute** (default 30) — sliding
  60 s rate cap per provider hostname. See
  [throttle.md](throttle.md).
- **Max concurrent calls per provider** (default 3) — concurrency
  cap. Applies across overlapping flows (report + meta + chat).
- **Maximal API calls** — six per-kind concurrency ceilings
  applied across hosts. Each engine first acquires from its kind's
  semaphore, then through the per-host throttle.

  | Knob | Default | Use |
  |---|---|---|
  | Global API calls | 50 | Hard ceiling for every dispatcher |
  | Report calls | 15 | Per-agent calls inside one report-gen run |
  | Translation calls | 15 | Total across all in-flight Translate runs |
  | Fan-out calls | 15 | Fan-out pair calls |
  | Fan-icons calls | 15 | Fan-icons batch — independent of Fan-out |
  | Test sweep calls | 40 | "Test all models" sweep |

- **Max 429 retries** (default 3) — in-line retries on a 429
  response. 0 disables.
- **429 retry backoff (ms)** (default 1000).
- **Max 529 retries** / **529 backoff (ms)** — independent budget
  for "server overloaded" (Anthropic) responses.

Each provider has its own override card on its edit screen that
inherits these values when left blank.

### Logging

- **Log level** — threshold for the in-app file logger
  (`AppLog`). One of `TRACE` / `DEBUG` / `INFO` / `WARN` /
  `ERROR` / `OFF`. Default `INFO`. See [applog.md](applog.md).

Edits debounce and flush on screen leave so a quick back
doesn't lose typed changes.

### AI Setup hub

| Card | What it does |
|---|---|
| Providers | API keys, state, and default model per provider. The list sorts by state, and the **+ Add provider** entry is at the bottom. Each provider edit screen carries a Network card with per-provider rate-limit / concurrency / 429-retry overrides |
| Models (sub-hub) | Models / Model Types / Manual model types overrides |
| AI Models setup (sub-hub) | Local Models / **Model cooldowns** / **Blocked models** / **Test-excluded models** / **Inaccessible models**. Cooldowns + Blocked are auto-populated by the throttle layer + Test sweep; Test-excluded and Inaccessible are also seeded from `assets/excluded.json` and `assets/inaccessible.json` on every cold start |
| Workers (sub-hub) | Agents / Flocks / Swarms |
| Prompt management | Top-level page (the legacy Internal Prompts sub-hub was collapsed): System Prompts / Internal Prompts grouped by category (Meta + Fan-out + Fan-in + Other internal — including the icon prompts) / Example prompts. Each row carries a per-card help icon |
| Parameters | Reusable parameter presets (incl. reasoning effort) |
| Costs | Manual price overrides + Cleanup + Layered costs (collapsed at the bottom) |
| External Services | HuggingFace / OpenRouter / Artificial Analysis keys (debounced keystroke saves; flush on dispose) |
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

A compact landing screen — each drill-in is a full screen with
its own help topic. **Refresh** and **Reset** are grouped next
to each other on the landing page.

| Card | What it does |
|---|---|
| Backup & Restore | Export the entire app to a `.zip`; restore from one. The Restore screen carries a red warning that the zip contains your API keys |
| Export & Import | Collapsible cards for Settings / Model lists / Parameters / System prompts / Workers / Costs CSV / Prompts JSON / Runtime data / API keys / All (the All bundle has its own card; API keys are a dedicated card so they can be exported and shared separately) |
| Refresh | Hand-off to the per-tier Refresh screen |
| Test | Hand-off to the per-provider Test screen + "Test all models" sweep (see [model-test.md](model-test.md)) |
| Trim by age | Drop reports / chats / traces / log files older than a chosen cutoff. Hides "Trim by age" when there's nothing to trim |
| Usage statistics | Reset the per-(provider, model, kind) counters |
| Reset | Five dedicated sub-screens (see below) |

**Reset** is split into five sub-screens (each is a collapsible
card, all collapsed by default):

- **Clear all runtime data** — narrower than before: wipes app
  log, traces, chats, reports, prompt history, and usage stats.
  Knowledge / pricing / model-list caches stay put.
- **Clear Info providers** — wipes the seven external-info
  caches (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace) and their timestamps.
- **Clear all configuration** — wipes provider config, prompts,
  Local LLMs, LiteRT models. Asks before destructive actions.
- **Restore bundled assets** — re-merges `providers.json` /
  `prompts.json` / `examples.json` from the APK. User edits to
  existing rows are preserved.
- **Reset application** — factory-style reset that preserves API
  keys (written to a temp file under `cacheDir/reset_keys_*`,
  restored after the wipe). No longer runs a trailing
  Refresh-all chain — fire it from Refresh if you want it.

After any wholesale-state-replace op, a **Restart-app** dialog
prompts you to relaunch so the in-memory state matches the
fresh on-disk state.

### Test all models

**Housekeeping → Test → Test all models** probes every model in
every active provider with a fixed `"Reply with exactly: OK"` prompt,
in parallel up to the **Maximal API calls → test sweep** cap (default
40 concurrent). Each result is captured with a trace deep-link, latency,
and cost.

1. **Select screen** — pick which providers to include (or just hit
   "Test all models" with no L1 to skip straight to the picker).
2. **L1** — top stats panel with the catalog partition snapshot
   (For testing / Inaccessible / Excluded / No-chat) and live
   progress: Running, Queued, Throttled, Done, Errors. Math
   reconciles by construction (Queued + Running + Done + Errors =
   For-testing). Provider rows alphabetical, in-row progress bars,
   per-provider Test buttons, **Rerun errors** action.
3. **L2** — per-provider list of models with status icons, error
   tooltips, cost, latency, and the source prompt at the top.
4. **L3** — single-model detail with the captured trace, request
   prompt, response body, and the chevron nav to walk siblings.

The sweep auto-feeds three Settings lists from its results:

- **Inaccessible models** — tier-gated catalog entries (e.g. Together
  non-serverless) that aren't reachable on the user's account. Hidden
  from every model picker. Auto-seeded from `assets/inaccessible.json`
  on every cold start so a clean install doesn't waste 64 sweep slots.
- **Test-excluded models** — models whose probe cost would exceed 5¢
  (auto), plus anything the user adds. Skipped by the sweep but still
  visible in pickers. Auto-seeded from `assets/excluded.json`.
- **Blocked models** — models that returned an error above the user
  threshold. Counted as FAIL until manually un-blocked.

All three are CRUD'd under **AI Setup → AI Models setup**
(Blocked / Test-excluded / Inaccessible). See
[model-test.md](model-test.md) for the full flow.

### Model cooldowns

When a provider answers a 429 with a Retry-After hint longer than
**1 hour** (Google quota exhausted, Cohere monthly cap, per-day
token quotas reset at Pacific midnight), the model is benched in
the **Model cooldowns** store until the hint expires. Model pickers
dim the row and show "rate-limited · back HH:mm" (or "back May 15
14:30" when the cooldown crosses midnight). The Fan-out engine,
the Test sweep, and the per-agent icon chain all skip the benched
pair automatically for the rest of the run.

Manage the list under **AI Setup → AI Models setup → Model
cooldowns** — CRUD entries, clear all, and tap a row to deep-link
into the API trace whose 429 produced the cooldown. See
[cooldowns.md](cooldowns.md).

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

- **Rate limits + concurrency**: every provider has a per-host
  sliding-window rate cap (default 30 calls / minute) and a
  per-host concurrency cap (default 3 in flight) enforced
  globally across every flow — report, meta, fan-out, chat,
  translate, model-list fetches. A 429 retries up to 3× with 1 s
  back-off by default. All of these are configurable under
  **Settings → Network**, and any provider can override them on
  its own edit screen. Each retry is a separate trace with the
  originating call's `(reportId, category)` tags propagated.
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
- **Background continuation** — Generate, Regenerate, secondary
  launches (Rerank / Meta / Moderate / Translate), the
  alternative-icons fan-out, and the per-agent icon chain all
  continue running when you navigate away from the result page.
  Cancelling a report (delete) cancels every in-flight call
  for that report including any icon-chain jobs.
- **Background ↔ chat from cross-app** — When another app
  launches a report via `ACTION_NEW_REPORT`, the user gets a
  one-tap confirmation before generation starts — no silent
  background runs.
