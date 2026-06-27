# User Manual

A multi-provider AI app for Android. Run the same prompt against many
models at once, compare what they say, fan one model's response into
another's prompt, save the result, share it, and keep an audit trail
of every API call you made. Forty-two cloud providers ship with the
app, plus optional on-device LLMs and embedders.

## First run

1. Install the APK and open it. On first launch the app imports a
   default catalog of **49 cloud providers** from a bundled
   `providers.json` (the registry starts empty and is seeded on
   demand) and seeds Internal Prompts (Meta / Compare / Fan-out /
   Fan-in / Workers / Alt / fixed templates) from `internal-prompts/`,
   so you don't have to type any URLs or prompt templates yourself.
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

## The home screen

The app ships in two interchangeable home presentations, chosen under
**Settings → UI tweaks → App home** (**Home bar** / **Home screen**).
The default is **Home bar**.

### Home screen (card hub)

In **Home screen** mode the home screen shows the app logo and a column
of big cards. Cards appear only when they're usable — before any
provider has a key the AI Reports / AI Chat cards are replaced by an
**AI Examples** card so a first-run user can still open a real bundled
report.

- **AI Reports** — multi-model reports with rerank / chat-meta /
  fan-out / moderate / translate / tournament / judges / compare.
- **AI Chat** — single-model conversation. Chat titles are
  AI-generated (the bundled `chat-title` prompt fires after the
  first assistant response); a first-words fallback fills the row
  instantly so nothing is ever blank.
- **AI Knowledge** — RAG knowledge bases. Only shown when
  **Experimental features** *and* **Show Knowledge card on home page**
  are both enabled ([knowledge.md](knowledge.md), [experimental.md](experimental.md)).
- **AI Monitor** — the observability hub. Drills into **Live
  Dashboard** (in-flight calls, caps and throttle state), **API
  Traces**, **Application log**, **Audit**, **Statistics** (lifetime
  totals across reports, providers, models, spend and the model
  fleet), and **Crash reports** (only when crashes exist). Models,
  Usage/Spend, Traces, and the App log all live under here now — they
  are no longer separate home cards.
- **AI Setup** — providers, models, workers, prompts, parameters,
  pricing, external keys (see [AI Setup hub](#ai-setup-hub)).
- **AI Housekeeping** — backup/restore, export/import, trim, reset,
  diagnostics (see [Housekeeping](#housekeeping)).

Below those, a fixed footer of **Settings**, **Help**, and **About**.
The **About** card hosts the two documentation hubs — the user
**Manual** and the **Technical documentation** — each a
JavaScript-disabled WebView over the bundled `docs/` HTML.

The home logo doubles as a one-tap shortcut to the most recent
report's result page (or to the AI Reports hub when no report exists
yet).

### Home bar (default)

In **Home bar** mode the classic card screen is no longer the main
navigation surface: a persistent top icon bar sits above every screen's
title bar with shortcuts for **About** (the leading AI-logo glyph),
**Reports**, **Chat**, **Monitor**, **Housekeeping**, **Application
log**, **Traces**, **AI Setup**, **Settings**, and **Help** (the
trailing red ❓). The 📤 share and 📋 copy icons stay in each screen's
bottom bar, exactly as in Home screen mode.

Pressing the Home/About logo opens whatever makes sense for your state:
if you have a report it lands on the newest one's **Manage** screen; if
you have no report but have API keys it opens the **AI Reports** hub;
and a freshly-installed, unconfigured app opens **First launch**, with
cards for **Import API keys**, **AI Setup**, **Example reports**,
**Housekeeping**, **Settings**, **Help**, and **About**.

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
- **Search** — escalating-cost options:
  - **Quick local search** — fast keyword scan across saved report
    prompts and bodies.
  - **Extended local search** — slower, broader scan (also into
    secondaries / translations).
  - **Remote semantic search** — embed your query against any chat
    provider and rank reports by cosine similarity.
  - **Local semantic search** — the same cosine ranking but embedded
    with an on-device embedder; only shown when one is installed.
- **Manage** — bulk housekeeping (pin/unpin, delete, export many).

### Selection phase

1. Tap **New AI Report**. You land on the model selection screen.
2. Add models to the report using the **+chip** row:
   - **+Agent** — full-screen Agent picker with rich rows.
   - **+Flock** — full-screen Flock picker (with Edit + One-time
     entry points right on the picker).
   - **+Swarm** — full-screen Swarm picker (with Edit + One-time).
   - **+Report** — appears only once a saved report is selectable;
     pulls every model from an existing report into this one.
   - **+Model** — full-screen multi-select picker across every active
     provider's catalog. This supersedes the old provider-then-model
     two-step (there's no separate +Provider button); installed
     on-device LLMs appear under the synthetic **Local** provider in
     the same picker.
3. Optionally tap **Params** to apply a parameter preset (temperature,
   max_tokens, system prompt, reasoning effort, etc.). See
   [parameters.md](parameters.md) for how presets resolve.
4. Optionally attach a vision image (📎), toggle web-search 🌐, or
   pick a reasoning level 🧠.
5. Tap **Next** (pinned to the top of the selection screen) to reach
   **Report - select workers** — three cards that decide who handles
   the worker jobs for this report:
   - **Report info** (icon, titles, language): the worker prompts'
     configured chains, or a custom Model / Agent / Flock / Swarm
     fallback chain you compose inline.
   - **Model info** (icons & titles): the configured chains, or
     **Own model** — each answer model writes its own title and icon.
   - **Worker batches** (Fan Meta, Translation, Tournament, Judges,
     Compare, Rerank, Moderation, Meta, Fan-in): each prompt's
     configured chain, the report's own models (**Report models**,
     with a **When available** / **Round robin** worker-selection
     sub-choice), a picker on every batch start, or a one-time picker
     whose group is reused for every later batch.
   Leave everything on **Prompt configuration** for the default
   behaviour, then tap **Generate report**. The same screen reopens
   later from the 👷 icon on Manage a report (without the Generate
   button).

### Generation phase

While the report runs:
- A progress bar shows X/Y completed.
- Each agent's status icon spins ⏳ until the call finishes (✅ / ❌).
- The full action row (Regenerate / Export / Copy / Translate /
  Delete / Rerank / Create) is available from the moment Generate is
  tapped — you can navigate away and the run **continues in the
  background** on `appViewModel.viewModelScope`. Coming back to
  the screen recovers stale placeholders and shows finished
  rows; a toast confirms completion if you stayed elsewhere.
- Tap **STOP** to cancel.

Deleting one of the report's models mid-run no longer hangs the
progress bar — the removed slot is still counted toward completion,
so the report reaches "complete" and the screen-on lock releases.

#### Per-report icon

Right after Generate the app fires a background **worker** call to
pick a fitting emoji for the report from its long title. The icon
appears as the leftmost title-bar glyph and as the row icon on every
list that references the report. If the call fails it shows ❌ with
the error reason — the row stays usable; the icon just isn't there
yet. Toggle this off under **Settings → Metadata & icons → Generate
report icon**.

#### Per-agent icons

If **Generate per model icons** is on (default), each successful
agent's response is first *titled*, then an emoji is picked **from
that title** — both via the **worker engine** (a randomly-picked,
429-falling-back chain of cheap models). There is no longer a
response-based fallback chain: when a model has no title, or no emoji
can be parsed, the agent is simply left icon-less. The result becomes
the row's emoji on the **Icons** view (View → Icons). Costs accumulate
on the row's cost cell and show up under the Costs view's per-call
**All** tab. See [report-icons.md](report-icons.md).

### Result phase

A finished report lives on the **Manage a report** screen — a row per
agent, the running total cost in the bottom bar, and a full bottom
**action bar**. There are two gestures worth knowing on the title bar:

- **Tap the title** (or the orange report-name line) to **cycle three
  report screens**: **Manage a report** → **Report - Get info** →
  **Report - second results** → back to Manage. *Get info* lists every
  metadata-generation job (report icon, language, short/long title,
  per-model icons + titles) with its status and cost; *second results*
  lists every secondary result (see below). The second-results step is
  skipped when the report has none.
- **Tap the report icon** (the leftmost title-bar emoji, shown when
  icon-gen is on) — or the bottom-bar 👁 — to open the **View a report**
  hub, a grid of content tiles.

The bottom action bar carries direct icons for the common secondary
actions — **Rerank**, **Moderation**, **🌐 Translate**, **Fan out**, and
the head-to-head **Tournament** tools — alongside 👁 View, ✏️ Edit,
🆕 Create, 💬 Chat (starts a new chat pre-populated with the report's
prompt), 📋 Copy, 📤 Share, 🔄 Regenerate, 🐞 Trace, 🗑 Delete,
📌 Pin, ℹ️ report info, ✍️ add note / 📒 notes list, and 👷
**Select workers** (re-opens "Report - select workers" to change who
handles report info, model info and the worker batches — without the
Generate button). Single-shot kinds (Rerank,
Moderation) jump straight to the existing result if one exists;
otherwise they open the picker. The footer row mirrors the agent-row
layout and shows the report's total cost on the right; a **Costs from
deleted items** line surfaces above the Total when non-zero so deleting
rows doesn't lose visibility into what the API actually billed.

#### The View hub (View a report)

The report icon / 👁 opens **View a report**, a reorderable grid of
content tiles (long-press to drag; the order persists across reports):

- Fixed document tiles: **Prompt**, **Reports**, **Matrix**, **Costs**,
  **Icons**, and an optional **Value view** (only when the report has a
  ranking to draw on — a Rerank, a Tournament, a Judge-the-judges run,
  or a Rank-the-translators run). The **Matrix** tile (between Reports
  and Costs) opens the read-only **Answer matrix** described below; the
  **Value view** tile opens the cost × quality view ([Value
  view](#value-view)).
- One tile per **Meta run** (e.g. Compare, Critique), per **Fan-out**
  run, and per **Fan-in** run — each labelled with its prompt name and
  its own generated emoji, opening that specific result.
- The computed structured tiles **Rerank**, **Tournament**, and
  **Moderation**, each shown only when at least one such row exists. A
  tile that resolves to a single result opens it directly; with two or
  more it expands an inline picker list. (Translate has no View tile —
  its source/translation list lives on the 🌐 Translations screen;
  Judges, Compare-with-meta and Rank-the-translators are reached from
  the **second results** screen.)

#### ✏️ Edit a report

The bottom-bar **✏️** opens a full-screen **Edit report** overview
(layer on top of the screen, not a small pop-up): a big centred report
icon, the short + long title, a `Parameters: …` line, a `System
prompt: …` line and the prompt body — each with its own ✏️ that opens
the existing editor — plus three buttons: **Edit models**, **Edit
icons**, **Edit titles**. **Edit icons** lists every icon in the report
(report, language, per-model, meta, ranking, moderation, per-language
translation, fan-out response) and opens that icon's Icon-lookup /
Find-alternative flow; **Edit titles** lists every dynamic title
(report short / long, per-model, fan-out response), each with a
manual-edit ✏️ and a **Find** (multi-model) button. Prompt / parameter
edits queue up; tap **Regenerate** to re-run. A model-list-only change
makes Regenerate **additive** — it runs just the new models and merges
them in. The phased regenerate engine is detailed in
[regenerate.md](regenerate.md).

#### 🆕 Create

The bottom-bar **🆕** opens the **Meta** launcher (layer on top): two
big-icon + description rows — **Meta** (compare / critique / synthesize
the answers) and **Compare with meta** (score each answer's similarity
to a meta result). The head-to-head tools have their own launcher
behind the **Tournament** bottom-bar icon — **Tournament** and **Judge
the judges**. The remaining secondary kinds (Rerank, Moderation,
Translate, Fan out) each have their own direct bottom-bar icon because
reusing or jumping to an existing one is the common path. Disabled rows
(no prompt configured, not enough source rows) render dimmed.

#### Report - second results

The Manage screen folds every secondary result into a single
**second** row (status ⏳/✅/❌ and the summed secondary spend). Tapping
it — or cycling the title twice — opens **Report - second results**,
which lists the Tournament / Judges / Compare / Rank-the-translators
batches, then the individual Meta / Rerank / Moderation / Fan-in rows,
the Fan-out + Fan-meta rows, and the live + finished Translation rows,
each with its icon and per-row cost. Tapping a row opens that result's
detail or drill-in.

#### View → Matrix (Answer matrix)

A read-only, horizontally-scrollable comparison table — one row per
**successful** agent, sorted by rerank rank (ranked rows first, then
original order). Columns: **#** (ordinal), **Model**
(provider / title or short model name), **Rank** (rerank rank +
score), **Stance**, **Confidence**, **Recommendation**, **Risks**,
**Cost** (in cents), **Latency**, **Tokens**. A summary card at the
top shows the model count, the ranked count, and total cost.

Stance, Confidence, Recommendation, and Risks are **text-mined from
each response body with regular expressions** — they are *not* model
self-reports and cost nothing extra. Stance is one of
*Refuses / Mixed / Recommends / Cautious / Neutral*; Confidence is
*Low / Medium / High*. Rank/score are pulled from the report's latest
Rerank result. The matrix follows the View screen's language picker,
so it shows translated bodies/titles when you switch language. It
reuses the report's main help topic — there is no separate Matrix
help page.

#### View → Icons

Minimal grid of every agent's generated emoji. Tap a glyph to
open that agent's **Model response** detail screen; back returns
to the grid. Grid spacing adapts down when not every icon fits
at the default size.

#### Value view

The 💎 **Value view** tile plots every successful model on a
**cost × quality** plane and marks the **best-value** model and the
**Pareto frontier** — answering "which model gives the most quality for
the least money?". It's a pure local derivation (no API calls): the X
axis is each agent's recorded cost, and the Y axis is a per-model
**ranking** you pick with a chip switch along the top — the report's
**Rerank**, the **Judge-the-judges** consensus, any **Rank the
translators** run (one chip per language), the **Tournament** total or
any individual Tournament method, or the weighted **Combined** blend
(see [Ranking weights](#preferences) below). Switching the ranking
re-ranks the chart, the 💎 best-value pick and the list instantly.

The tile only appears once the report has at least one ranking to draw
on (a Rerank, Tournament, Judge-the-judges, or Rank-the-translators
run). Full detail in [value-view.md](value-view.md).

#### Per-agent prev/next on Model response

Per-agent detail screens carry **Prev / Next** chevrons under
the title bar that walk the agent list by model name. The
chevrons live tight against the cost column so the eye stays in
one place as you scan.

### Meta prompts and Translate

Meta-result flows that operate on a finished report's outputs.
The available Meta buttons are entirely user-driven via the
Meta-prompt CRUD: Settings → AI Setup → **Prompt management →
Internal prompts → Meta prompts**. A typical setup:

- **Compare** — chat-type prompt asking the model to identify where
  responses agree, where they diverge, and what each one uniquely
  contributed. Tick **reference** on the entry to get a deterministic
  `[N] = Provider / Model` legend appended automatically.
- **Critique** / **Synthesize** / etc. — any chat-type analysis
  you want; the prompt name becomes the button label and the
  view tab name in exports. These are *user-defined names*, not
  built-in operations — under the hood every chat-type Meta row is
  the same `META` kind, distinguished only by its prompt name.
- **Rerank** — use the bundled Rerank action (or a chat-style Meta
  prompt if you'd rather use a chat model) to rank responses 1..N
  with a score and reason. The structured Rerank action routes through
  the provider's dedicated rerank endpoint only when the picked model
  is itself a rerank model (e.g. Cohere `/v2/rerank`); a chat model
  picked for rerank goes through the normal analyse path.
- **Moderation** — use the bundled Moderation action to run the
  report's responses through a provider's `/moderations` endpoint
  and show the flagged-categories table.

Translate is a separate Create action (not a Meta prompt) — it
translates the prompt and every successful agent response (plus any
chat-type Meta rows in scope) to one or more languages, fanning out
one API call per language × source. Rerank and Moderation rows are
never translated. See [translation.md](translation.md) for the full
flow.

You can run any of them multiple times per report — each run is a
separate, independently viewable, independently deletable entry.
Once results exist, the View grid gains a tile per Meta run (e.g.
**Compare**, **Critique**) plus the computed **Rerank** and
**Moderation** tiles; every run also shows up on the **second
results** screen. See [secondary-results.md](secondary-results.md)
for the data model.

#### Tournament, Judge the judges, Compare with meta

These are **worker-judged** analysis batches rather than one-call
Meta rows:

- **Tournament** judges every pair of successful model responses
  twice, A-vs-B and B-vs-A (to cancel position bias), using the
  `workers/tournament` prompt and the `tournament` swarm. It stores
  N(N−1) match rows plus one aggregate leaderboard. The View side can
  switch between **eleven ranking methods** — Copeland, Elo, Davidson,
  Tideman, Markov, Schulze, Minimax, Colley, Glicko-2, Points, and
  TrueSkill2 — (a pure local recompute — no API calls) and drill into
  model head-to-heads. The Copeland win-rate is computed per model as a
  percentage of the head-to-heads that model actually contested
  (not a fixed N−1), so a missing or errored match no longer scores
  like a loss.
- **Judge the judges** gives every judge model in that same swarm the
  same random set of answer pairs, then reports agreement with
  consensus and per-judge cost/time. You can add/remove judges from
  the run; the underlying swarm is updated too.
- **Compare with meta** first asks you to select existing Meta
  results, then a `meta_compare` prompt. It scores every answer
  against every selected Meta row as a 0..100 similarity grid.

All three have L1/L2/L3 drill-ins, running/waiting/error counters,
restart-failed and redo actions, per-cell cost, app-kill resume, and
trace links when tracing was enabled. See
[tournament-judges-compare.md](tournament-judges-compare.md).

#### Rank the translators

A fourth worker-judged batch, but it grades **translations** instead of
the original answers: it scores which translator *model* produced the
best translation of a finished [Translate](translation.md) run. It does
not re-translate anything — it reuses one existing translation run (one
run = one target language) and has a panel of judge models score each
long-form translated answer 0–100, then ranks each translator model by
the average score its translations earned.

There's no separate button: a run starts from the **🏅 medal** on a
translation row — on the 🌐 **Translations** list (one per run) and on a
**Translation run** screen's title bar. A confirm dialog shows the
planned number of scoring calls; on **Rank** the batch builds and runs,
landing on the **Rank the translators** screen — an L1 leaderboard
(`#`, translator model, items, average score) with a 🐜 workers panel,
🔄 restart-failed and 🗑 delete. Because it's keyed per language, you
can rank each language's translation run independently. Its result feeds
the [Value view](#value-view) as a ranking source. Full detail in
[rank-translators.md](rank-translators.md).

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
the model picker. Rerank / Moderation actions skip the scope screen —
they always operate on the full set.

### Fan out / Fan in

A separate flow that turns one report into many — reachable from the
report's bottom-bar **Fan out** icon, with a sibling **Fan in** action
once fan-out rows exist.

1. **Run a fan-out** — pick a Fan-out prompt (CRUD'd under AI
   Setup → Prompt management → Internal prompts → **Fan out/in
   prompts → Fan Out**). The flow runs a single combined card: the
   prompt picker is hoisted above the scope card, the answerer +
   source cards are collapsed into one. The popup confirmation reads
   as "N reports × M responses = pairs".
2. The runtime fans out one API call per (answerer, source) pair
   — each `@RESPONSE@` placeholder in the template is replaced
   by the source response text. Concurrency is controlled by
   Settings → Network settings → Maximal API calls plus the
   per-provider throttle, so overlapping report / chat / fan-out work
   shares the same host budgets.
3. **Drill in** — three levels deep:
   - **Level 1** lists one row per answerer with progress bars,
     ✅/❌ status, per-row cost, and a Total banner. Empty-body
     successes count as Done. The Actions card collapses
     Resume stale / Restart failed / Rerun complete / Delete.
   - **Level 2** lists one row per (answerer, source) pair,
     virtualised so long lists scroll smoothly.
   - **Level 3** is the single-response detail with a 🐞 link
     to the original report-model trace.
4. **Run a fan-in** — pick a Fan-in prompt (under **Fan out/in
   prompts → Fan in, total**) to combine every per-pair row into a
   single combined-report row. The `***Report*** @REPORT@@RESPONSES@`
   block in the template expands once per source agent. An L2 active
   model's fan-out conversation can also be promoted into a
   standalone report.

**Fan Meta** is its own separate screen (it used to be folded into the
fan-out drill-in): a per-pair **title + icon** batch that the worker
engine runs over a fan-out run's responses, so each fan-out pair gets
its own generated title and emoji. It has its own L1/L2/L3 drill-in and
🐜 workers panel, can autostart on a finished fan-out (Settings →
Autostart → **Autostart Fan Meta**), and shows up as a **fan-meta**
sibling row on the second-results screen.

After-fan-out runs surface as standalone secondary rows on the
report's **second results** screen and inside the Fan-out drill-in.

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
  individual call as its own row (including the per-report icon-gen
  call, per-agent icon/title calls, fan-out / fan-in rows,
  translation calls). The Costs Type column reads the Meta-prompt
  name lowercased — `compare`, `critique`, … — for chat-type rows;
  structured kinds keep their fixed labels: `rerank`, `meta`,
  `moderation`, `translate`, `icon`. Provider and Model are split
  into separate columns; the summary tabs include a **Calls** column.
  The Total row uses the 💰 icon; a `deleted` row surfaces non-zero
  `costsFromDeletedItems` alongside the active rows.
- Stable `result-N` anchors on each agent's card.
- One view-picker tab per chat-type Meta prompt name (e.g.
  Compare / Critique / Synthesize), plus Reranks / Moderations /
  Translations tabs for the structured kinds. Rerank entries
  render as a linked rank table; chat-type Meta references like
  `[3]` are clickable jumps back to that agent's card.
- Markdown tables (GFM pipe-style) render as proper HTML tables in
  the in-app viewer and in every export.

A whole report (with its secondary rows and traces) can also be
exported and re-imported as a single bundle zip — see
[backup-restore.md](backup-restore.md). Import always lands as a
fresh report with new ids.

### Edit / regenerate

After a report has finished, you can tweak the prompt, the title, the
model list, or the parameters and re-run. A pending-changes banner
appears at the top of the result screen until you tap **Regenerate**.
- Regenerate stays on the same report and **cascades by impact**:
  prompt edits run all agents fresh; parameter edits do too;
  model-list-only edits run just the additions / changes and merge
  them in.
- Regenerate runs as a **phased batch** (title → icon → language →
  agents → meta → fan-out → fan-in → translations → fan-meta →
  tournament). If a phase errors it pauses on the failing row; fix
  the row and tap Restart. A paused or app-killed batch is flagged on
  the ⚠️ Broken-work screen (the app detects it but no longer resumes
  it for you). Full detail in [regenerate.md](regenerate.md).

### Broken work

A background scan watches every report for batch work that stalled —
items left mid-flight by an app-kill, or cells that errored out. When it
finds any, the right-hand **AI-logo** in the top bar is replaced
app-wide by a ⚠️ badge (with a count). Tapping it opens the **Broken
work** screen, which lists each affected batch (model responses, meta,
fan-out, fan-meta, translations, tournament / judges / compare / rank
batches). Per line you can **view** the broken items, **restart** them,
or **delete** them; tapping a card opens the underlying report. Nothing
is re-run automatically — the screen just surfaces what needs a nudge.
When the last broken item clears, the screen closes itself and the ⚠️
reverts to the AI logo.

## Chat

Single-model conversation with full history. Pick a provider+model at
the top, type messages, get streaming replies. Sessions are
auto-saved.

### AI Chat hub

Like the Reports hub, the Chat hub is rich:

- **Start** — `New Chat with Agent` (greyed until an agent has a key
  on an active provider), `New Chat – Configure On The Fly` (pick
  provider/model/parameters at start), `Dual Chat`, and `Start with
  photo` (camera capture rides into the first user turn).
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
- A 📚 Knowledge attach chip when Experimental features are on — RAG
  context is retrieved against the last user message and prepended
  ([knowledge.md](knowledge.md)).

A mid-session system-prompt change takes effect on the next turn,
not just on a fresh session. A chat against the synthetic **Local**
provider runs entirely on-device via the MediaPipe runtime
([local-runtime.md](local-runtime.md)).

## Dual Chat

Two models in conversation with each other. You define two prompt
templates that reference each other's output (`%subject%`,
`%answer%`), pick a subject, and an interaction count. The first
model answers about the subject; the second responds to the first;
they take turns until they hit the count. Useful for adversarial
cross-examination, devil's-advocate setups, or multi-step pipelines.
Conversations persist across rotation and process recreation.

### Share-target

Other apps can share text and documents into AI. From any app's
share sheet, pick "AI"; you'll get a chooser screen with up to three
destinations: **New Report**, **New Chat**, and (when Experimental
features are on) **Add to Knowledge**. See
[share-target.md](share-target.md).

## Models

Reached from **AI Monitor → Statistics → Models** (and from the
+Model picker inside report selection): a flat searchable view of
every model across every active provider. Filter by provider, by
capability (vision / web-search / function calling / reasoning), or
just by name. Tap a model for the **Model Info** screen.

### Model Info

Cards stacked top-to-bottom:

1. **Actions** — Start AI chat • Create AI Agent.
2. **Capabilities** — vision / web-search / function-calling /
   reasoning toggles plus the underlying signals from each layer.
3. **Provider** — provider's name links to the per-provider
   help page.
4. **Sources** — buttons that open the model's page on each external
   repository (HuggingFace / OpenRouter / LiteLLM / models.dev /
   Helicone / llm-prices / Artificial Analysis), each with an ℹ
   button next to it that deep-links to that repository's help
   page. **Show all** opens a side-by-side raw-JSON dump of every
   source.
5. **Costs** — input / output prices from each tier and the resolved
   layered price (see [costs.md](costs.md)).
6. **API Traces** — every API call to this provider+model that's
   still on disk, filtered by hostname so unrelated traces don't
   pollute the count.
7. **Last usage** — running cost+token stats for this model with
   an AI Usage counter pulled from `usage-stats.json`.

The model name is the page subject; the Provider card sits under
Capabilities so users start by understanding what the model can do
before they navigate to provider-level admin.

Standalone model-name labels across the app are clickable and open
the Model Info screen. The **Model name layout** Settings preference
controls whether labels show only the model id or both provider
and model.

## AI Monitor

The observability hub. Its drill-ins:

- **Live Dashboard** — pinnable, reorderable cards showing in-flight
  calls, per-flow concurrency caps, throttle state, and the
  on-device runtime. Each card refreshes only while expanded.
- **API Traces** — see [API Traces](#api-traces) below.
- **Application log** — see [Application log](#application-log) below.
- **Audit** — per-report trail of mutating actions, batches, and
  API calls (when the Audit log is enabled).
- **Statistics** — lifetime aggregates: **Models** (capabilities,
  types, context, states), **Reports** (reports + secondary results),
  **Providers / Models** (the whole model fleet), and **Spend &
  usage** (calls, tokens and cost broken down by provider, type,
  report, and model). The Spend column is coloured by which pricing
  tier supplied the price; a small Type pill marks non-report calls
  (`rerank`, `meta`, `moderation`, `translate`, and fan-out / Meta
  rows under their own prompt name).
- **Crash reports** — only present when the crash reporter has
  captured something; tap to view and share.

## API Traces

Every API call (request + response) is dumped as a JSON file under
`<filesDir>/trace/`. The Trace screen (AI Monitor → API Traces)
lets you browse them by hostname, status code, model, or the report
they belonged to. The Trace list **auto-collapses to detail when
filters yield a single entry**. When the trace was captured inside a
specific report, the icons-grid render appears in the trace
detail so you can recognise the run at a glance.

The Trace detail's TitleBar carries 🗑 (delete) and 🔄 (refresh)
icons. The detail page surfaces the request body's first line in a
Get-style preview, with an ℹ️ icon that deep-links to the matching
info-provider help page (falling back to the provider help when no
per-model help exists). On-device LLM/embedder calls write synthetic
`local://…` traces that appear here alongside the HTTP traces.

API tracing is **on by default** but can be toggled off under
Settings → Logging and tracing → API tracing. When tracing is off,
no new traces are written. The same Logging screen also has **Show
Ladybug icons**: turning that off keeps trace capture active but
hides every 🐞 shortcut, so traces are reached from the API Traces
screen instead.

Secrets (Authorization / x-api-key / `?key=` query params / JSON
key fields) are redacted before a trace is written to disk, so trace
files are safe to share and to roll into a backup. Each retry attempt
is its own trace with the originating call's `(reportId, category)`
tags propagated. See [throttle.md](throttle.md).

## Application log

A daily-rotating in-app log file with a structured viewer
(AI Monitor → Application log). Useful when you want to send a clean
log to support without needing `adb`. The viewer offers search, level
checkboxes (default WARN + ERROR), time-range pickers, and a tag
dropdown. Tap any row to open the entry detail, with a 🐞 Trace
link when the entry's tag + timestamp match a captured API
trace. The Copy/Share dialog asks Filtered-only vs whole file
and Last-N-lines vs Complete. Sensitive headers and API keys are
redacted inline at write time, so a shared log never carries
plain secrets. See [applog.md](applog.md).

## Settings

The **Settings** main screen is a hub of icon cards. It separates
general app preferences from the larger AI Setup area and keeps
visual customization under dedicated UI screens.

### Preferences

- **UI tweaks** — full-screen mode, model-name layout, **App home**
  (Home bar vs Home screen, see [The home screen](#the-home-screen)),
  **Experimental features** master toggle, and **Show Knowledge card
  on home page**.
- **UI Colors** — collapsed color-picker cards for App background,
  title colors, card/button backgrounds, text, borders, and role
  accents. Edits apply live and use `AppColors`. See
  [ui-customization.md](ui-customization.md).
- **Default icons** — edit the fallback/action glyphs used across
  navigation cards, action bars, status rows, secondary kinds, and
  fallback report/model icons. The app reads these through
  `MetadataIcons`, so overrides apply globally.
- **Metadata & icons** — master metadata switch plus per-feature
  toggles: **Generate report icon**, **Generate per model icons**,
  **Generate per model titles**, report language/title, and
  internal-prompt icons. See [report-icons.md](report-icons.md).
- **Ranking weights** — two cards of 0–10 sliders: one for the named
  rankings (**Rerank**, **Judges**, **Translations**), one with a
  slider per Tournament method (Copeland, Elo, Davidson, …). These feed
  the **Combined** score in the report [Value view](#value-view); the
  🧽 in the icons bar resets every slider to its default.
- **Autostart** — report-completion automation: **Auto create Rerank
  and Moderation**, **Autostart Fan Meta**, and **Default meta
  items**.
- **Other settings** — identity (name + email).

### Network settings

Subject "Timeouts, throttling and retry rules", grouped into cards:

- **Network read timeouts** — **Streaming (seconds)** is the read
  timeout for SSE chat / report streams (default ~240 s; this is the
  gap *between* chunks, so a long default is normal).
  **Non-streaming (seconds)** is the read timeout for analyze / meta /
  rerank / translate / model-list calls (default 120 s, much shorter
  so a hung provider can't gate a whole batch). **Batch item
  (seconds)** is the wall-clock ceiling for one batch item — a
  fan-out pair, translation item, tournament match, judge / compare /
  translator-rank cell — including worker fallbacks and retries
  (default 180 s); a timed-out item becomes a restartable error
  instead of stalling the batch. Provider-test calls always cap at
  30 s regardless.
- **Per-provider throttling** — **Max calls per provider per minute**
  (default **60**, a sliding 60 s rate cap per provider hostname) and
  **Max concurrent calls per provider** (default **5**, concurrency
  cap applied across overlapping flows: report + meta + chat). A
  **Per provider** button opens the per-provider override list.
- **429 error handling** — **Max retries on 429** (default **3**;
  0 disables in-line retries) and **Wait between retries (ms)**
  (default **1000**).
- **529 error handling** — same shape for provider-overload
  responses, with an independent budget (default 3 retries, 1000 ms).
- **Maximal API calls** (one tap deeper) — per-flow concurrency caps:
  total API calls (default 100), primary reports (50), translation
  (50), fan-out (50), Fan Meta (50, shared with workers), and Test
  all models. These are the coroutine-level caps layered *above* the
  per-host throttle.

Each provider has its own override card on its edit screen that
inherits these values when left blank. See [throttle.md](throttle.md)
for the two-layer (per-host gate + per-flow caps) design.

### Logging and tracing

- **API tracing** — master switch for `ApiTracer` (default on).
- **Show Ladybug icons** — hides the per-screen 🐞 links while keeping
  trace files enabled.
- **Audit log** — records report-level mutating actions, batches, and
  API calls.
- **Log level** — threshold for the in-app file logger
  (`AppLog`). One of `TRACE` / `DEBUG` / `INFO` / `WARN` /
  `ERROR` / `OFF`. Default `INFO`. See [applog.md](applog.md).

Edits debounce and flush on screen leave so a quick back
doesn't lose typed changes.

### AI Setup hub

| Card | What it does |
|---|---|
| Providers | API keys, state, and default model per provider. The subtitle reads "42 built-in plus your own providers". The list sorts by state, and the **+ Add provider** entry is at the bottom. Each provider edit screen carries a Network card with per-provider rate-limit / concurrency / 429-retry overrides |
| Models (sub-hub) | Models / Model Types / Manual model-type overrides / Local Models / Model cooldowns / Blocked / Test-excluded / Inaccessible (see [model-states.md](model-states.md)) |
| Workers (sub-hub) | Models / Agents / Flocks / Swarms (see [workers.md](workers.md)) |
| Prompt management | System Prompts / **Internal prompts** (Meta / Compare / Fan out-in / Other internal / Worker / Alternative) / Example prompts |
| Parameters | Reusable parameter presets (incl. reasoning effort) |
| Costs | Manual price overrides + Cleanup + Layered costs |
| External Services | HuggingFace / OpenRouter / Artificial Analysis keys (debounced keystroke saves; flush on dispose) |
| App settings | App-wide & report-model default system prompt / parameters |

> **Note:** Anything user-driven that runs on a report's outputs
> (Compare, Critique, Synthesize, …) is configured under **Internal
> prompts → Meta prompts** — these are user-named entries of the same
> `META` kind. Compare-with-meta scoring prompts live under **Compare
> prompts** (`meta_compare`). Fan-out / Fan-in templates live under
> **Fan out/in prompts**; "Other internal prompts" (chat-title /
> model-info / model-intro / second-rerank / second-moderation /
> test-model) is a fixed list with no Add / Delete. The icon / title /
> language generators, the Tournament judge prompt, and the
> **translate-text / translate-title** prompts (each with its own
> worker swarm — translation runs through the worker engine now, no
> model picker) live in **Worker prompts**, as does the
> **translate-rank** judge prompt that drives [Rank the
> translators](#rank-the-translators); Find-alternative variants
> live in **Alternative prompts**.

### Refresh

Refreshing the **seven external repositories** (LiteLLM, OpenRouter,
models.dev, Helicone, llm-prices, Artificial Analysis, HuggingFace)
is reached from **AI Monitor / Housekeeping → Refresh**. Each card
has its own button; **Refresh all** runs a full-screen progress page
that fetches catalogs in parallel, then re-tests every active
provider in dependency order, and finally auto-restarts the app to
pick up the freshly-persisted caches. Refresh all skips the
default-agent re-test (it trusts the catalog-fetch results) and lists
any failed providers with a one-tap nav-to-edit. See
[repositories.md](repositories.md).

### Housekeeping

A compact landing screen — each drill-in is a full screen with
its own help topic.

| Card | What it does |
|---|---|
| Backup & Restore | Export the entire app to a `.zip`; restore from one. The Restore screen carries a red warning that the zip contains your API keys |
| Export & Import | Collapsible cards for Settings / Model lists / Parameters / System prompts / Workers / Costs CSV / Prompts JSON / Runtime data / API keys / All (the All bundle has its own card; API keys are a dedicated card so they can be exported and shared separately) |
| Update from cloud | Install the APK from a previously selected cloud/storage file |
| Costs | Maintain manual pricing overrides and layered cost data |
| Test | Run diagnostics such as Test all models and Stress test |
| Prompt translations | Generate and manage internal-prompt translations per language |
| Cached prompts | View and clear the on-disk cache of internal-prompt responses (48 h TTL); per-row age / size / STALE marker; delete one or clear all |
| Refresh | Hand-off to the per-tier Refresh screen |
| Trim by age | Drop reports / chats / traces / log files older than a chosen cutoff. Hidden when there's nothing to trim |
| Reset | Five dedicated sub-screens (see below) |

**Reset** is split into five sub-screens (each is a collapsible
card, all collapsed by default):

- **Clear all runtime data** — wipes app log, traces, chats, reports,
  prompt history, and usage stats. Pricing / model-list caches stay
  put.
- **Clear Info providers** — wipes the seven external-info
  caches (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace) and their timestamps.
- **Clear all configuration** — wipes provider config and prompts.
  Asks before destructive actions.
- **Restore bundled assets** — re-merges `providers.json` /
  `internal-prompts/` / `examples.json` from the APK. User edits to
  existing rows are preserved.
- **Reset application** — factory-style reset that preserves API
  keys (written to a temp file under `cacheDir/reset_keys_*`,
  restored after the wipe). Does not run a trailing Refresh-all
  chain — fire it from Refresh if you want it.

After any wholesale-state-replace op, a **Restart-app** dialog
prompts you to relaunch so the in-memory state matches the
fresh on-disk state (restore does not live-reload — see
[backup-restore.md](backup-restore.md)).

### Export / Import

Granular sub-bundles plus an All bundle (split into "with API
keys" and "without API keys" so you can share configs without
leaking secrets). The Workers bundle round-trips agents + flocks
+ swarms together; Settings / Model lists / Parameters / System
prompts / Costs CSV / Prompts JSON each have their own button.
CSVs use RFC-4180 quoting on export and a tolerant RFC-4180 parser
on import.

## Help

Every screen's TitleBar carries a red ❓ that opens a per-screen
help topic. Provider cards on Model Info / Trace detail / Costs
carry ℹ buttons that deep-link to a per-provider help page
covering that provider's setup, capabilities, quirks, and known
issues.

On the report **Manage / Create** screens (and other crowded bars) a
white ❔ sits just left of the red ❓. Tapping the white ❔ opens a
full-screen "<screen> — icons" overlay (or a live legend on certain
screens) that lists the icons **currently visible** in that screen's
bar — big glyph + name + a one-line description — and tapping a row
performs that icon's action. The red ❓ always opens the screen's
normal help page. (Full details live in [help.md](help.md).)

The Help home page surfaces an icon legend rendered as a 3-column
table — every TitleBar icon you'll see in the app, with a one-line
description — plus tap-through cards to the reference topics (About,
Getting started, Concepts, Glossary, Costs, Privacy, Backup,
Translations) and a substring search box across every topic.

## Tips

- **Rate limits + concurrency**: every provider has a per-host
  sliding-window rate cap (default **60** calls / minute) and a
  per-host concurrency cap (default **5** in flight) enforced
  globally across every flow — report, meta, fan-out, chat,
  translate, model-list fetches. A 429 retries up to **3×** with
  **1 s** back-off by default (529 overload responses get their own
  identical, separate budget). All of these are configurable under
  **Settings → Network settings**, and any provider can override them
  on its own edit screen. Each retry is a separate trace with the
  originating call's `(reportId, category)` tags propagated. See
  [throttle.md](throttle.md).
- **Vision attachments** are stored on the Report so a Regenerate
  re-uses the same image. Images are downscaled + JPEG-encoded
  before base64.
- **Reasoning effort** (low / medium / high) is plumbed through to
  models that support it (gpt-5.x / o-series via OpenAI Responses
  API; Anthropic thinking models; Gemini thinking models).
  Non-reasoning models silently ignore the field; on chat session
  resume it's clamped to the active model's supported range. See
  [parameters.md](parameters.md).
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
  launches (Rerank / Meta / Moderate / Translate / Tournament /
  Judges / Compare), the alternative-icons fan-out, and the
  per-agent icon/title workers all continue running when you navigate
  away from the result page. Cancelling a report (delete) cancels
  every in-flight call for that report including any icon jobs.
- **Background ↔ chat from cross-app** — When another app launches a
  report via `ACTION_NEW_REPORT`, the user gets a one-tap
  confirmation before generation starts — no silent background runs.
- **On-device models** — with **Experimental features** on you can
  install MediaPipe `.task` LLMs and `.tflite` embedders under
  **AI Setup → Models → Local Models**; they surface as a synthetic
  **Local** provider in every picker and run with no network calls.
  See [local-runtime.md](local-runtime.md).
