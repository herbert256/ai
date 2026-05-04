# User Manual

A multi-provider AI app for Android. Run the same prompt against many
models at once, compare what they say, ground the run in your own
documents, save the result, share it, and keep an audit trail of every
API call you made — cloud or on-device.

## First run

1. Install the APK and open it. The app imports a default catalog of
   39 cloud providers from a bundled `setup.json` so you don't have
   to type any URLs yourself.
2. Open **Settings → AI Setup → Providers**. Pick the providers you
   want to use, paste in their API keys (each card has a 🔗 link to
   that provider's console), and tap **Test API Key**. A successful
   test marks the provider as 🔑 and adds it to **Active**.
3. Optionally, paste a HuggingFace token, OpenRouter token, and/or
   Artificial Analysis key under **AI Setup → External Services**.
   None of these are required to use the app — they only enable model
   metadata, pricing, and intelligence/speed scores.

The on-device LLM and on-device embedder paths require **no API key**
— they run a `.task` or `.tflite` model file you supply. See the
"Local Runtime" section below.

## The Hub

The home screen has these big cards:

- **AI Reports** — multi-model reports with rerank / summarize /
  compare / moderate / translate.
- **AI Chat** — single-model conversation.
- **AI Knowledge** — knowledge bases for retrieval-augmented
  generation. Attach to Reports / Chats.
- **AI Models** — search every model across all your providers.
- **AI Usage** — running token / cost statistics.
- **AI API Traces** — every recent API call (cloud or local) as a
  JSON file.

Plus shortcut buttons for **Settings**, **Housekeeping**, **AI
Setup**, **Help**.

## Reports

Reports are the killer feature. A report = one prompt run against many
models in parallel.

### AI Reports hub

Tapping **AI Reports** lands on a hub screen with several cards:

- **Start** — `New AI Report`, `Start with a previous prompt`.
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
   - **+Agent** — pick a single saved Agent.
   - **+Flock** — pick a Flock (a saved group of agents).
   - **+Swarm** — pick a Swarm (a saved group of provider/model pairs).
   - **+Provider** — pick any provider (including the synthetic
     **Local** provider for on-device `.task` models), then any of its
     models.
   - **+Model** — full-screen multi-select picker across every active
     provider's catalog.
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

When the report is complete, three rows of buttons appear:

- **View** — Results / Prompt / Costs / Trace.
- **Edit** — Prompt / Title / Models / Parameters. Edits queue up;
  tap **Regenerate** in the Actions row to re-run with the changes.
  When only the model list changed, Regenerate is **additive** — it
  runs only the new models and merges them in.
- **Actions** — Regenerate / Export / Copy / Translate / Delete.
- **Meta** — one button per Meta prompt configured under AI Setup
  → Prompt management (see below).

### Meta prompts and Translate

Meta-result flows that operate on a finished report's outputs.
The available Meta buttons are entirely user-driven via the
Meta-prompt CRUD: Settings → AI Setup → **Prompt management →
Report Meta Prompts**. A typical setup:

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
  supports it.
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

Then **Continue** lands on the model picker. Rerank /
Moderation–typed Meta prompts skip the scope screen — they always
operate on the full set.

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
- The original prompt and a Costs table (the Type column reads the
  Meta-prompt name lowercased — `compare`, `critique`, … — for
  chat-type rows; structured kinds keep their fixed labels:
  `rerank`, `meta`, `moderation`, `translate`).
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

- **Start** — `New AI Chat`, `Configure on the fly`, `Chat with a
  local LLM` (only when at least one `.task` file is installed),
  `Dual AI Chat`.
- **Pinned chats** (when present).
- **Recent** — last few chats with the 🕐 icon.
- **Unfinished** pill — when a chat from a previous session was left
  mid-turn, a one-tap resume.
- **Search** — full-text scan across saved chat sessions.
- **Manage** — bulk housekeeping.

A chat session can have:
- A vision image attached per turn (📎).
- The 🌐 web-search tool toggled per provider.
- The 🧠 reasoning-effort selector per turn.
- One or more knowledge bases attached (📚) — every user turn the
  retriever pulls top-K chunks across the attached KBs and prepends a
  context block to the system message.

## Dual Chat

Two models in conversation with each other. You define two prompt
templates that reference each other's output (`%subject%`,
`%answer%`), pick a subject, and an interaction count. The first
model answers about the subject; the second responds to the first;
they take turns until they hit the count. Useful for adversarial
cross-examination, devil's-advocate setups, or multi-step pipelines.

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
  - Images (`.jpg`, `.jpeg`, `.png`) — Latin OCR via MediaPipe
  - Plain text and Markdown (`.md`)
- **+ Web page** fetches a URL with Jsoup and ingests the visible
  text.

Every source goes through a per-type extractor → paragraph chunker
(2 KB target, 200-char overlap) → embedder. Status reports
"Extracting…" → "Embedding batch N/M…" → "Indexed (chunks=K)".

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

Maintenance lives under **Housekeeping → Local LLMs** and
**Housekeeping → Local Models** (see Housekeeping below). See
[local-runtime.md](local-runtime.md) for the full story.

## Models

A flat searchable view of every model across every active provider.
Filter by provider, by capability (vision / web-search / function
calling), or just by name. Tap a model for the **Model Info** screen.

### Model Info

Six cards stacked top-to-bottom:

1. **Actions** — Start AI chat • Create AI Agent.
2. **Sources** — buttons that open the model's page on each external
   repository (HuggingFace / OpenRouter / LiteLLM / models.dev /
   Helicone / llm-prices / Artificial Analysis), plus **Show all** for
   a side-by-side raw-JSON dump of every source.
3. **Costs** — input / output prices from each tier and the resolved
   layered price.
4. **Capabilities** — vision / web-search / function-calling toggles
   plus the underlying signals from each layer. The **Add manual
   override** button is at the bottom of this card.
5. **API Traces** — every API call to this provider+model that's
   still on disk.
6. **AI Usage** — running cost+token stats for this model.

## AI Usage

A breakdown of every API call you've made. Provider cards expand into
per-model rows; each row has a small Type pill when it isn't a normal
report call (`rerank`, `summarize`, `compare`, `moderate`,
`translate`). The Cost column is coloured by which tier supplied the
price.

## API Traces

Every API call (request + response) is dumped as a JSON file under
`<filesDir>/trace/`. The Trace screen lets you browse them by
hostname, status code, model, or the report they belonged to.
Local LLM and local embedder calls are traced too with hostname
`local`.

## Settings

### AI Setup hub

| Card | What it does |
|---|---|
| Providers | API keys, state, default model per provider |
| Models | Source + model list per active provider |
| Agents | Named (provider, model, params, system prompt) configs |
| Flocks | Groups of agents |
| Swarms | Groups of (provider, model) pairs |
| Parameters | Reusable parameter presets (incl. reasoning effort) |
| System Prompts | Reusable system prompts |
| Costs | Manual price overrides |
| Prompt management | CRUD for Meta prompts (`category=meta`) — each entry becomes a button on the report's Meta row. Type picks the routing (chat / rerank / moderation); the name picks the bucket label everywhere |
| Rerank, Translate, Intro, Model Info | Custom prompt templates for those system flows |
| External Services | HuggingFace / OpenRouter / Artificial Analysis keys |

> **Note:** Anything user-driven that runs on a report's outputs
> (Compare, Critique, Synthesize, …) is configured under **Prompt
> management → Report Meta Prompts**, not under the system-prompt
> cards.

### Refresh

A dedicated screen for refreshing the seven external repositories.
Each card has its own button; **Refresh all** chains them in
dependency order and then auto-restarts the app to pick up the
freshly persisted caches.

### Housekeeping

Cards are **collapsed by default** for a compact landing screen — tap
a card header to expand it.

| Card | What it does |
|---|---|
| Backup & Restore | Export the entire app to a `.zip`; restore from one |
| Trim by age | Drop reports / chats / traces older than a chosen cutoff |
| Usage statistics | Reset the per-(provider, model, kind) counters |
| Configuration | Wipe configuration only; manual cost cleanup |
| Local Models | Download / pick / remove `.tflite` text embedders for the LocalEmbedder |
| Local LLMs | Hand-off links to download `.task` LLM bundles + SAF picker for `.task` / `.zip` / `.tar.gz` archives + per-row Remove |
| Full reset | Clear all runtime data; clear all configuration |

### Import / Export

Import a full `setup.json`-shape configuration, just an API-keys JSON,
or a layered-cost CSV. Export the same shapes. The keys export is
kept separate from the full config so you can share configs without
leaking keys.

## Tips

- **Reports run in parallel up to 4 at a time.** Bigger reports
  honour that limit, so you can queue 12 models without hammering any
  single provider.
- **Rate limits**: a 429 from any provider is automatically retried
  up to 5 times with a 3-second back-off. Each retry is a separate
  trace.
- **Vision attachments** are stored on the Report so a Regenerate
  re-uses the same image.
- **Reasoning effort** (low / medium / high) is plumbed through to
  models that support it (gpt-5.x / o-series via OpenAI Responses
  API; Gemini thinking models). Non-reasoning models silently ignore
  the field.
- **External intent**: another app can launch this one with a
  prompt, a list of agents/flocks/swarms/models, and an action
  ("view", "share", "browser", "email"). See the in-app **Help**
  screen for the full intent contract — and
  [share-target.md](share-target.md) for the standard
  `ACTION_SEND` flow.
- **Multiple translation runs at once** — the Translate flow lets
  you fire off several language batches concurrently; results land
  in their own rows on the Result screen.
