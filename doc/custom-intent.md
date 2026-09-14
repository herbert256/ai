# Custom Intent

The exported `com.ai.ACTION_NEW_REPORT` intent is a distinct contract
handled by `MainActivity.handleIntent` (it stages `externalTitle /
externalSystem / externalPrompt / externalInstructions`) and processed
in `AppNavHost`. For the standard Android share-sheet flow, see
[share-target.md](share-target.md).

Two behaviours, by how much the intent asks for:

- **Bare prompt** (no `<instructions>` block and no `-- end prompt --`
  marker) → merely pre-fills the New Report editor via
  `aiNewReportWithParams`. The user still picks models and taps
  Generate, so no credits move without consent.
- **Prompt + instructions** (the `instructions` string extra, or a
  `-- end prompt --` marker splitting prompt from instructions) → the
  instructions are parsed into a `PendingExternalReport` (including
  `title`, `systemPrompt`, `aiPrompt`, `openHtml`,
  `closeHtml`, `reportType`, `email`, `nextAction`, `hasReturn`,
  `hasEdit`, `hasSelect`, `agentNames`, `flockNames`, `swarmNames`,
  `modelSpecs`, extracted from `<open>`, `<close>`, `<type>`,
  `<email>`, `<next>`, `<return>`, `<edit>`, `<select>`, `<agent>`,
  `<flock>`, `<swarm>`, `<model>` tags) and an
  `ExternalIntentConfirmScreen` is shown before generation.

## Select saved prompts and parameters

Use these paired tags in the `instructions` extra to select definitions
already saved in the AI app:

```xml
<system>Chess coach</system>
<parameters>Careful analysis</parameters>
<default>Analyse a position</default>
<fen>r4rk1/1b2bppp/ppq1p3/2ppB2n/5P2/1P1BP3/P1PPQ1PP/R4RK1 w - - 0 15</fen>
<select>
```

| Tag | Definition selected | Effect |
|---|---|---|
| `<system>Name</system>` | System prompt | Sets the report-level system prompt for all selected models. |
| `<parameters>Name</parameters>` | Parameters preset | Sets the report-level generation parameters, above worker/provider defaults. |
| `<default>Name</default>` | Default prompt | Supplies the prompt for all selected models when no explicit prompt is present, above their assigned worker defaults. |

Names are trimmed and matched ignoring case; stable definition IDs also
work. These three tag names are case-insensitive. Escape XML characters in
names, for example `Research &amp; writing`. Missing, ambiguous or empty
names are reported on the confirmation screen and prevent continuing.
An empty saved default prompt also prevents continuing.

`<system>` takes precedence over the older `<systemprompt>` alias and the
literal `system` intent extra. The chosen system prompt also overrides any
system text embedded in the chosen Parameters preset. An explicit `prompt`
extra or a template selected with `<prompt>` takes precedence over
`<default>`. Without `<default>`, worker defaults behave as before.

The chosen settings appear in confirmation and remain available in the
report's model/worker selection flow, including `<edit>` and `<select>`.
Named system and default prompts receive the placeholder substitutions
described below. Generation saves the resolved prompt and parameter values
for replay; saved definitions and worker assignments are unchanged.

## Prompt placeholders

The `instructions` intent extra (held internally as `externalInstructions`)
can supply named values using paired tags:

```xml
<topic>Climate in Amsterdam</topic>
<language>Dutch</language>
<agent>My researcher</agent>
<type>Classic</type>
<select>
```

If the selected worker's default prompt is `Explain @topic@ in @language@.`,
the model receives `Explain Climate in Amsterdam in Dutch.` A system prompt
such as `Answer in @language@.` becomes `Answer in Dutch.` This applies to
the effective system prompt from any level: report override, worker,
provider, external `system` extra, or application default.

- Names match ignoring case: `<topic>` supplies both `@topic@` and `@TOPIC@`.
  Names start with a letter or underscore and may also contain digits,
  dots, hyphens and colons.
- Values retain their whitespace and may span lines. XML entities
  (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`) are decoded once;
  `<open>`, `<close>` and `<board>` retain their raw markup.
- An empty entry replaces its placeholder with an empty string. When an
  entry repeats, its last value is used. Entries without a matching
  placeholder have no effect on the prompt.
- Replacement is a single pass: placeholders inside an inserted value
  are literal text. Placeholders without an entry are left to the normal
  prompt handling, including existing built-ins such as `@MODEL@` in
  default prompts; other unmatched placeholders remain unchanged.
- The selected default prompt is expanded when no explicit report prompt
  was supplied. The usual Agent / Flock / Swarm default precedence applies.
  System prompts are expanded after their precedence is resolved.
- Saved templates are unchanged. The report stores the resolved prompt and
  system parameters for retry and regeneration.

An instruction-only request that names an Agent, Flock or Swarm uses those
workers' defaults unless `<default>` selects a report-wide default. Without
a worker selection or `<default>`, it opens the saved-prompt
picker. An explicit `<prompt>name-or-id</prompt>` still selects a saved
template; `<systemprompt>name-or-id</systemprompt>` selects a saved system
prompt. See [default-prompts.md](default-prompts.md) for worker defaults.

Custom value bodies are removed before interpreting control tags, so a
`<select>` or `<email>` inside a value cannot become an app command.

## Report presentation and confirmation

`<open>` and `<close>` supply the report's opening and closing content.
HTML bodies are inserted verbatim, including CSS, `<script>` elements and
event handlers, into Complete / Short HTML and the zipped HTML index.
They run in the app's HTML preview and in a browser opening the export.
Reports with either field show an **HTML** tile in **View**; Export → HTML →
View in app also opens the preview. Text without HTML keeps Markdown
formatting (including fenced code examples). When HTML is present, the whole
body is treated as HTML; write HTML for its surrounding text too.

Instruction tags are read only outside these two bodies, so an HTML
`<select>` or a script string containing `<email>` is not an app command.
The delimiters `</open>` and `</close>` terminate their respective bodies;
avoid writing the matching literal delimiter inside JavaScript strings.
These fields are report presentation content, separate from the AI prompt.

The confirmation overlay (help topic `external_intent`, title
"External request") lays out exactly what will happen — which models
get called, which side effects fire (`<email>`, `<next>`, return-on-
completion), and a prompt preview — with **Cancel** and a confirm
button labelled **Generate** when the intent would auto-generate
(`willAutoGenerate` = no `<edit>`/`<select>`, a `<type>`, and at least
one model source) or **Continue** otherwise. This explicit consent
step matches the app's "no background billing without acknowledgement"
posture; previously such intents could run silently and mask surprise
spend. On confirm with `<edit>`, the user lands in the New Report
editor; otherwise the agent-selection / generation flow runs.

The launch intent is staged once (`savedInstanceState == null`) and the
source extras are cleared after staging so a configuration change can't
re-stage the confirmation after the user has cancelled or confirmed.

## Files

- `ai/src/main/AndroidManifest.xml` — the `com.ai.ACTION_NEW_REPORT`
  intent filter.
- `ai/src/main/java/com/ai/MainActivity.kt` — `handleIntent`,
  external-extra staging, and the fresh-start guard.
- `ai/src/main/java/com/ai/ui/navigation/AppNavHost.kt` — processes
  the staged request and routes confirmation, editing, and generation.
- `ai/src/main/java/com/ai/ui/share/ExternalIntentConfirmScreen.kt` —
  `PendingExternalReport` + the custom-intent confirmation overlay.
- `ai/src/main/java/com/ai/ui/share/ExternalAppCommandParser.kt` —
  separates instruction entries, presentation bodies, and control tags.
- `ai/src/main/java/com/ai/ui/share/ExternalReportContext.kt` —
  decodes entries and substitutes prompt placeholders.
- `ai/src/main/java/com/ai/viewmodel/ReportLaunchPlan.kt` — resolves
  default prompts and freezes each model's execution configuration.
