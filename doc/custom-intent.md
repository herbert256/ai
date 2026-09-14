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
  instructions are parsed into a `PendingExternalReport` (a 15-field
  payload: `title`, `systemPrompt`, `aiPrompt`, `openHtml`,
  `closeHtml`, `reportType`, `email`, `nextAction`, `hasReturn`,
  `hasEdit`, `hasSelect`, `agentNames`, `flockNames`, `swarmNames`,
  `modelSpecs`, extracted from `<open>`, `<close>`, `<type>`,
  `<email>`, `<next>`, `<return>`, `<edit>`, `<select>`, `<agent>`,
  `<flock>`, `<swarm>`, `<model>` tags) and an
  `ExternalIntentConfirmScreen` is shown first.

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
