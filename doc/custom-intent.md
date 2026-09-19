# Custom Intent

AI's receiver-side guide to `com.ai.ACTION_NEW_REPORT`.

Companion: [Eval repo — CALL_AI.md](../../eval/CALL_AI.md)
([GitHub](https://github.com/herbert256/eval/blob/master/CALL_AI.md)).
The local link assumes sibling `ai` and `eval` checkouts. Keep the shared
contract and examples between the marked comments identical in both files
when either side changes. The implementation notes below are repo-specific.

<!-- BEGIN SHARED AI INTENT CONTRACT -->
## Intent contract

Send action `com.ai.ACTION_NEW_REPORT` restricted to package `com.ai`.
This is separate from Android's `ACTION_SEND` share-sheet flow.

| String extra | Requirement | Meaning |
|---|---|---|
| `title` | Optional | Report title; generation uses `AI Report` when the title is blank. |
| `instructions` | Required for Eval's current handoff | Paired instruction/data tags and standalone control flags. AI calls this state `externalInstructions` internally; the actual extra key is **`instructions`**. |
| `prompt` | Optional | Explicit question, for other/older callers. It can be omitted when `instructions` is present. |
| `system` | Optional | Literal system-prompt text, for other/older callers. It is a fallback below configured worker/provider/report prompts. |

**Eval currently sends only `title` and `instructions`.** It stores named
instruction entries; prompts, system prompts, parameter presets and worker
configurations belong to the AI app. Do not send an extra named
`externalInstructions`.

A bare `prompt` without instructions pre-fills the New Report editor.
Instruction-bearing requests first resolve their saved definitions, then
show **External request** confirmation. They never generate before that
confirmation. When no explicit prompt, named default or Agent/Flock/Swarm
selection supplies the question, AI opens its saved-prompt picker first.

For older callers, `prompt` can contain `-- end prompt --`: text before the
marker is the question and text after it is instructions. A supplied
`instructions` extra takes precedence over this split, even when empty.

## Instruction tags

Use paired tags for values and standalone tags for flags. Use lowercase
control tags as shown; the three new saved-definition selectors also accept
uppercase/mixed-case tag names.

| Tag | Meaning |
|---|---|
| `<system>Name or text</system>` | Select a saved **System prompt**; if no definition resolves, use the content as literal system-prompt text. Applies as the report-level override for all selected models. |
| `<parameters>Name</parameters>` | Select a saved **Parameters** preset as the report-level generation settings. |
| `<default>Name</default>` | Select a saved **Default prompt** for every selected model when no explicit question is present, ahead of worker defaults. Works with bare models too. |
| `<prompt>Name or text</prompt>` | Select an existing Example Prompt or eligible Internal Prompt, by ID or unique name. If no definition resolves, use the content as literal system-prompt text, leaving the report question unchanged. This is a different catalog from Default prompts. |
| `<agent>Name</agent>` | Select a configured Agent by name; repeatable. |
| `<flock>Name</flock>` | Select a configured Flock by name; repeatable. |
| `<swarm>Name</swarm>` | Select a configured Swarm by name; repeatable. |
| `<model>Provider/model-id</model>` | Select a model directly; repeatable. The first slash separates provider from model, so the model ID may contain more slashes. |
| `<type>Classic</type>` / `<type>Table</type>` | Choose the report format. |
| `<open>content</open>` / `<close>content</close>` | Opening/closing report presentation, including HTML, CSS and JavaScript. |
| `<next>View</next>` | Completion action: `View`, `Share`, `Browser` or `Email`. `Email` uses AI's configured default email address. |
| `<email>recipient@example.com</email>` | Open the email chooser with the completed HTML report attached and the recipient filled in. This does not silently send email. |
| `<edit>` | After confirmation, open New Report for editing; a named default is pre-filled for editing when no explicit question was supplied. |
| `<select>` | After confirmation, open model selection rather than immediately generating. |
| `<return>` | Finish the AI activity after the requested email/next action; no report data is returned as an Android activity result. |
| `<name>value</name>` | Supply a custom value for matching `@name@` placeholders in templates. |

Names in `<system>`, `<parameters>` and `<default>` are trimmed and matched
ignoring case. Stable definition IDs also work and take priority over name
matches. XML-escape names containing special characters, for example
`Research &amp; writing`. Unresolved `<system>` and `<prompt>` values
(including ambiguous names) are used as literal system-prompt text instead.
Missing, ambiguous or empty `<parameters>` / `<default>` names, and an
empty saved default prompt, are shown on confirmation and disable continuation.
When `<prompt>` falls back to system text, the question still comes from the
explicit `prompt` extra, `<default>` or worker defaults; if none is supplied,
the saved-prompt picker supplies the question.

Prompt precedence is: a template selected with `<prompt>` (when supplied),
otherwise explicit `prompt` text, then `<default>`, then the selected
workers' assigned defaults. Without a named default, a selected Flock falls
back to its members' Agent defaults; directly selected Agents use their own
defaults, and Swarm members use their Swarm's default. Bare models have no
worker default.

`<system>` sets the report-level system choice, above
worker/provider defaults, the literal `system` extra and system text inside
a Parameters preset. An unresolved `<prompt>` has the same system precedence,
but `<system>` wins when both are supplied. Literal system text supports the
same placeholder substitution as saved system templates. The parameter preset applies above worker/provider
parameter defaults. Users can change report-level choices in report setup.
Saved definitions and worker assignments are unchanged by a request.
Generation captures the resolved prompt and parameters for retry/regenerate.

After confirmation, immediate generation requires a `<type>`, at least one
worker/model source, and neither `<edit>` nor `<select>`. Otherwise the user
continues through editing/selection. A valid model selection, prompt and
provider configuration are still required. Completion actions run only after
generation; Share and Email open Android choosers, and Browser opens an HTML
viewer.

## Context and placeholder substitution

1. **Eval sends templates and data separately.** It does not replace tokens
   in prompt, system-prompt or presentation text. The standard six context
   fields below are always sent so templates saved only in AI can use them.
   Each unique supported placeholder has one matching lowercase data tag;
   repeated tokens reuse that value. `@DATE@` additionally supplies `<date>`
   with the current local date (`yyyy-MM-dd`). Token detection ignores case.
2. **AI expands the text.** Matching `@name@` placeholders in the effective
   prompt and system prompt use the supplied data, ignoring case. AI also
   expands opening/closing report presentation. For example,
   `<player>Alice</player>` supplies both `@PLAYER@` and `@player@`.
   Supplied values are decoded and inserted literally in one pass.

| Eval-supplied tag | Value |
|---|---|
| `<fen>…</fen>` | Current position, including an explored variation. |
| `<color>…</color>` | `White` or `Black`, from the FEN's side to move. |
| `<server>…</server>` | Chess server when known, otherwise empty. |
| `<player>…</player>` | Side-to-move player's name for a position report; selected player for a profile report. May be empty when unknown. |
| `<pgn>…</pgn>` | Available game PGN; the separate FEN is authoritative for the current position. |
| `<board>…</board>` | Generated board HTML/JavaScript. |
| `<date>…</date>` | Current local date when a date placeholder is used in the interface text. |

A player-only request sends empty `fen`, `color`, `pgn` and `board`; it does
not reuse the last opened position. Plain fields are XML-escaped (`&amp;`,
`&lt;`, `&gt;`, `&quot;`, `&#39;`); `board` is raw markup. Eval replaces
existing top-level declarations for supplied context fields with one
actual-value tag per name, including a declaration such as
`<date>@DATE@</date>`. Tokens inside prompt, system, presentation and custom
text bodies remain unchanged. Optional `<instructions>` wrappers are supported.

AI decodes plain entry values once and preserves raw `open`, `close` and
`board` bodies. Custom names start with a letter or underscore and may also
contain digits, dots, hyphens and colons. Values may span lines and retain
whitespace. An empty entry replaces its token with empty text; when custom
data entries repeat, the last value is used. Use each saved-definition
selector once; the first selector of each kind is read for routing.

Tokens inside an inserted value are not recursively expanded by external
substitution. Unmatched tokens remain unchanged, apart from existing AI
built-ins such as `@MODEL@`, `@PROVIDER@`, `@AGENT@` and `@DATE@` in default
prompts. Supplied external values take precedence over those built-ins.
For a saved system template that needs the date, include `@DATE@` in the
Eval interface text, for example in `<date>@DATE@</date>`, to request the
same explicit date value.

Keep `@BOARD@` in `<open>`/`<close>` for report presentation. Board markup is
not added to model prompts merely because its data tag is supplied; a
prompt or system template that explicitly uses `@BOARD@` receives its value.
Data and presentation bodies are removed before interpreting commands, so
`<select>` or `<email>` inside a data body is not executed.

HTML opening/closing bodies are inserted verbatim, including CSS, scripts
and event handlers, in Complete/Short HTML and the zipped HTML index. They
run in the in-app HTML preview and a browser opening the export. Reports
with either field have an **HTML** tile in **View**. Text-only presentation
keeps Markdown formatting; when supplying HTML, write the whole body as
HTML. The literal `</open>` / `</close>` delimiter ends its body, including
when written inside a JavaScript string.

## Examples

The definition and worker names below are examples: create them in AI first
or substitute names/IDs that already exist. Eval automatically supplies the six
standard context tags and any requested date value; do not paste a fixed
FEN or duplicate context values into an Eval instruction entry.

### 1. Eval position report with all three named selections

Create these definitions in AI:

| Kind / name | Example contents |
|---|---|
| System prompt **Chess coach** | `You are a chess coach. Respond in @language@. Report date: @date@.` |
| Parameters **Careful analysis** | Temperature `0.2`, max tokens `2048` (choose a model supporting them). |
| Default prompt **Analyse a position** | `Analyse @fen@ for @color@. Player: @player@. Explain plans and candidate moves.` |

Save this instruction text in Eval:

```xml
<system>Chess coach</system>
<parameters>Careful analysis</parameters>
<default>Analyse a position</default>
<language>English</language>
<date>@DATE@</date>
<type>Classic</type>
<select>
<next>View</next>
<open>@BOARD@</open>
```

Eval keeps the template tokens unchanged, supplies the date, board, position
and player values in separate data tags, and sends only `title` and `instructions`. AI shows the
expanded default/system prompts on confirmation, then model selection and
report setup. The named default applies even to a directly selected model.
The user starts generation; completion opens the report view.

### 2. Use a Flock's defaults and generate after confirmation

The AI Flock **Chess analysts** must exist and resolve a non-empty default
prompt for every member. Save in Eval:

```xml
<flock>Chess analysts</flock>
<type>Classic</type>
<next>View</next>
<open>@BOARD@</open>
```

There is no `<select>` or `<edit>`, so AI's confirmation button is
**Generate**. No API calls start before the user confirms. Each member uses
its resolved worker default and the received position context.

### 3. Eval player-only report

Create a Default prompt named **Player profile** with this text:

```text
Summarize the playing style of @player@ on @server@. State what cannot be
inferred from the supplied information.
```

Save this instruction entry in Eval:

```xml
<system>Chess coach</system>
<default>Player profile</default>
<language>English</language>
<date>@DATE@</date>
<type>Classic</type>
<select>
```

For a synthetic player `ExamplePlayer` on `lichess.org`, AI resolves the
question to `Summarize the playing style of ExamplePlayer on lichess.org…`.
The position-related fields are empty. No old FEN or board is included.

### 4. Another Android caller: complete named-template request

Create System prompt **Short answers** (`Answer in @language@.`), Parameters
**Concise** (max tokens `512`), and Default prompt **City summary**
(`Describe @topic@ in three sentences.`) in AI. From an Android Activity:

```kotlin
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast

fun Activity.openCityReport() {
    val instructions = """
        <system>Short answers</system>
        <parameters>Concise</parameters>
        <default>City summary</default>
        <topic>Amsterdam</topic>
        <language>Dutch</language>
        <type>Classic</type>
        <select>
    """.trimIndent()
    val request = Intent("com.ai.ACTION_NEW_REPORT")
        .setPackage("com.ai")
        .putExtra("title", "Amsterdam summary")
        .putExtra("instructions", instructions)
    try {
        startActivity(request)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Install the AI app first", Toast.LENGTH_SHORT).show()
    }
}
```

AI previews `Answer in Dutch.` and `Describe Amsterdam in three sentences.`.
The user confirms, selects models and generates. No `prompt`, literal
`system` or `externalInstructions` extra is needed. If the caller adds
`.putExtra("prompt", "Compare Amsterdam and Utrecht.")`, that explicit
question takes precedence over **City summary**.

### 5. Literal system text and placeholder values

```xml
<system>Write about @topic@. Keep @literal@ unchanged.</system>
<parameters>Concise</parameters>
<default>City summary</default>
<topic>Amsterdam &amp; Utrecht</topic>
<language></language>
<literal>@topic@</literal>
<select>
```

If no saved system prompt matches that content, AI uses it directly and
previews `Write about Amsterdam & Utrecht. Keep @topic@ unchanged.`.
`@language@` becomes empty in system/default templates. Substitution runs
once, so the inserted `@topic@` stays literal; an unmatched `@unknown@` also
stays unchanged. An unresolved `<prompt>` supplies literal system text in
the same way, while `<default>City summary</default>` supplies the question.
<!-- END SHARED AI INTENT CONTRACT -->

## AI implementation

- [`MainActivity.kt`](../ai/src/main/java/com/ai/MainActivity.kt) stages
  `externalTitle`, `externalSystem`, `externalPrompt` and
  `externalInstructions`. Missing `prompt` becomes an empty string when
  the intent has an `instructions` extra.
- [`ExternalAppCommandParser.kt`](../ai/src/main/java/com/ai/ui/share/ExternalAppCommandParser.kt)
  separates paired data/presentation blocks from control tags and builds
  `PendingExternalReport`.
- [`ExternalReportContext.kt`](../ai/src/main/java/com/ai/ui/share/ExternalReportContext.kt)
  resolves named definitions, decodes values and expands templates.
- [`ExternalIntentConfirmScreen.kt`](../ai/src/main/java/com/ai/ui/share/ExternalIntentConfirmScreen.kt)
  previews the request and prevents continuing with unresolved definitions.
- [`AppNavHost.kt`](../ai/src/main/java/com/ai/ui/navigation/AppNavHost.kt)
  stages confirmation and routes to editing or model selection after consent.
- [`ReportLaunchPlan.kt`](../ai/src/main/java/com/ai/viewmodel/ReportLaunchPlan.kt)
  resolves effective default prompts and captures execution configurations.
- [`ReportViewModel.kt`](../ai/src/main/java/com/ai/viewmodel/ReportViewModel.kt)
  applies report-level system/parameter selections and runs generation.
- [`RuntimeState.kt`](../ai/src/main/java/com/ai/ui/report/manage/RuntimeState.kt)
  handles external worker/model selections and completion actions.
- [`AndroidManifest.xml`](../ai/src/main/AndroidManifest.xml) registers the
  exported custom action on `MainActivity`.

The activity processes its launch intent only when `savedInstanceState`
is null, and clears the staged source extras after navigation consumes them.
Rotation does not replay a request the user already handled. This is
separate from [share-target.md](share-target.md). See also
[default-prompts.md](default-prompts.md), [system-prompts.md](system-prompts.md)
and [parameters.md](parameters.md) for normal selection precedence.
