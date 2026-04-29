# User Manual

A multi-provider AI app for Android. Run the same prompt against many
models at once, compare what they say, save the result, share it, and
keep an audit trail of every API call you made.

## First run

1. Install the APK and open it. The app imports a default catalog of
   38 providers from a bundled `setup.json` so you don't have to type
   any URLs yourself.
2. Open **Settings → AI Setup → Providers**. Pick the providers you
   want to use, paste in their API keys (each card has a 🔗 link to
   that provider's console), and tap **Test API Key**. A successful
   test marks the provider as 🔑 and adds it to **Active**.
3. Optionally, paste a HuggingFace token, OpenRouter token, and/or
   Artificial Analysis key under **AI Setup → External Services**.
   None of these are required to use the app — they only enable model
   metadata, pricing, and intelligence/speed scores.

## The Hub

The home screen has six big cards:

- **AI Reports** — the main flow.
- **AI Chat** — single-model conversation.
- **AI Dual Chat** — two models talk to each other.
- **AI Models** — search every model across all your providers.
- **AI Usage** — running token / cost statistics.
- **AI API Traces** — every recent API call as a JSON file.

## Reports

Reports are the killer feature. A report = one prompt run against many
models in parallel.

### Selection phase

1. Tap **AI Reports**. You land on the model selection screen.
2. Add models to the report using the buttons at the top:
   - **+Agent** — pick a single saved Agent.
   - **+Flock** — pick a Flock (a saved group of agents).
   - **+Swarm** — pick a Swarm (a saved group of provider/model pairs).
   - **+Provider** — pick any provider, then any of its models.
   - **+Model** — full-screen multi-select picker across every active
     provider's catalog.
3. Optionally tap **Params** to apply a parameter preset (temperature,
   max_tokens, system prompt, etc.).
4. Type your prompt and tap **Generate**.

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
- **Edit** — Prompt / Models / Parameters. Edits queue up; tap
  **Regenerate** in the Actions row to re-run with the changes.
- **Actions** — Regenerate / Export / Delete, plus
  **Rerank / Summarize / Compare** (see below).

### Rerank / Summarize / Compare

Three meta-result flows that operate on a finished report's outputs:

- **Rerank** — picks rerank-aware models (Cohere etc.) or any chat
  model and asks it to rank the responses 1..N with a score and reason.
- **Summarize** — picks chat model(s) and asks for a single synthesised
  answer combining the strongest points of every response.
- **Compare** — picks chat model(s) and asks for an analysis of where
  responses agree, where they diverge, and what each one uniquely
  contributed.

You can run any of them multiple times per report — each run is a
separate, independently viewable, independently deletable entry. Once
results exist, **View Reranks (n) / Summaries (m) / Compares (k)**
buttons appear on the View row.

#### Scope step

If a report already has at least one rerank, tapping **Summarize** or
**Compare** opens a small **scope** screen first:

- **All model reports** (default) — feed every successful response into
  the meta-result.
- **Only top ranked reports** — narrow the input to the top-N entries
  of a chosen rerank. A number field and a dropdown let you pick the
  count and which rerank's ranking to use.

Then **Continue** lands on the model picker. The rerank step never
shows this screen — it always operates on the full set.

### Export

Tap **Export** in the Actions row. Choose:

- **Format**: HTML (browsable in Chrome), JSON (raw), or PDF (print
  the HTML).
- **Detail**: full per-agent results vs. condensed.
- **Sections**: include / exclude prompt, costs, citations.
- **Action**: share-sheet, email, or open in a browser.

The HTML export contains:
- A toggle to switch between **One by one** (tabbed) and **All
  together** (grid card layout).
- The original prompt and a Costs table (with a Type column for
  rerank/summarize/compare API spend).
- Stable `result-N` anchors on each agent's card.
- Reranks, Summaries, and Compares blocks at the end. Rerank entries
  render as a linked rank table; summary / compare references like
  `[3]` are clickable jumps back to that agent's card.

### Edit / regenerate

After a report has finished, you can tweak the prompt, the model list,
or the parameters and re-run. A pending-changes banner appears at the
top of the result screen until you tap **Regenerate**.

## Chat

Single-model conversation with full history. Pick a provider+model at
the top, type messages, get streaming replies. Sessions are auto-saved;
**AI Chat → History** browses them.

## Dual Chat

Two models in conversation with each other. You define two prompt
templates that reference each other's output (`%subject%`,
`%answer%`), and a subject. The first model answers about the subject;
the second responds to the first; they take turns until you stop them.
Useful for adversarial cross-examination, devil's-advocate setups, or
multi-step pipelines.

## Models

A flat searchable view of every model across every active provider.
Filter by provider, by capability (vision / web-search), or just by
name. Tap a model for the **Model Info** screen.

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
5. **API Traces** — every API call to this provider+model that's still
   on disk (Hub → AI API Traces purges them).
6. **AI Usage** — running cost+token stats for this model.

## AI Usage

A breakdown of every API call you've made. Provider cards expand into
per-model rows; each row has a small Type pill when it isn't a normal
report call (`rerank`, `summarize`, `compare`). The Cost column is
coloured by which tier supplied the price.

The expanded provider list survives navigation away to Model Info and
back, so you can dive in and out without losing your place.

## API Traces

Every API call (request + response) is dumped as a JSON file under
`<filesDir>/trace/`. The Trace screen lets you browse them by
hostname, status code, model, or the report they belonged to.

## Settings

### AI Setup hub

| Card | What it does |
|---|---|
| Providers | API keys, state, default model per provider |
| Models | Source + model list per active provider |
| Agents | Named (provider, model, params, system prompt) configs |
| Flocks | Groups of agents |
| Swarms | Groups of (provider, model) pairs |
| Parameters | Reusable parameter presets |
| System Prompts | Reusable system prompts |
| Internal Prompts | Agent-bound templates with `@MODEL@` etc. variables |
| Costs | Manual price overrides |
| Rerank, Summarize, Compare | Custom prompt templates for those three flows |
| External Services | HuggingFace / OpenRouter / Artificial Analysis keys |

### Refresh

A dedicated screen for refreshing the seven external repositories. Each
card has its own button; **Refresh all** chains them in dependency
order and then auto-restarts the app to pick up the freshly persisted
caches.

### Housekeeping

Backup / restore the entire app to a single `.zip`. Clear individual
caches (chats, reports, traces, prompt history, usage stats, manual
pricing). "Start clean" deletes the lot.

### Import / Export

Import a full `setup.json`-shape configuration, just an API-keys JSON,
or a layered-cost CSV. Export the same shapes. The keys export is kept
separate from the full config so you can share configs without leaking
keys.

## Tips

- **Reports run in parallel up to 4 at a time.** Bigger reports
  honour that limit, so you can queue 12 models without hammering any
  single provider.
- **Rate limits**: a 429 from any provider is automatically retried
  up to 5 times with a 3-second back-off. Each retry is a separate
  trace.
- **Vision attachments** are stored on the Report so a Regenerate
  re-uses the same image.
- **External intent**: another app can launch this one with a prompt,
  a list of agents/flocks/swarms/models, and an action ("view",
  "share", "browser", "email"). See the in-app **Help** screen for
  the full intent contract.
