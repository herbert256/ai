# Eval → AI report handoff

Eval stores named instruction entries (`id`, `name`, `instructions`). Both position and player reports require the user to choose an entry. Prompts and system prompts are created and stored in the AI app.

Eval sends `com.ai.ACTION_NEW_REPORT`, restricted to package `com.ai`, with `title` and `instructions` extras. There are no `prompt` or `system` extras.

The selected instruction text is followed by these six tags, in this order, even when a value is unavailable:

```xml
<fen>r4rk1/1b2bppp/ppq1p3/2ppB2n/5P2/1P1BP3/P1PPQ1PP/R4RK1 w - - 0 15</fen>
<color>White</color>
<server></server>
<player>White</player>
<pgn>[FEN "r4rk1/1b2bppp/ppq1p3/2ppB2n/5P2/1P1BP3/P1PPQ1PP/R4RK1 w - - 0 15"]

*</pgn>
<board>Generated board HTML and JavaScript</board>
```

- `fen`: the current position, including an explored variation.
- `color`: `White` or `Black`, read from that FEN.
- `server`: `lichess.org` or `chess.com` when known. Local FEN positions have no server.
- `player`: for position reports, the side-to-move player's name; for profile reports, the selected player.
- `pgn`: the available full game PGN. The separate FEN is authoritative for the current position.
- `board`: generated chessboard HTML/JavaScript. It belongs in report presentation, not model request bodies.

A player-only report has empty FEN, color, PGN and board tags. It does not inherit the last opened game.

Plain values use XML escaping (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`). The receiver decodes those values once. Board markup is raw inside its enclosing tag. All six context tags and `<open>`/`<close>` bodies must be removed before interpreting control tags, so markup and PGN are never interpreted as commands.

Instructions may use `@FEN@`, `@COLOR@`, `@SERVER@`, `@PLAYER@`, `@PGN@`, `@BOARD@` and `@DATE@`. For example:

```xml
<type>Classic</type><select><next>View</next>
<open>@BOARD@</open>
```

The AI receiver opens its saved-prompt picker for an instruction-only request. The user then chooses a system prompt, or retains the configured AI system prompts, and reviews the existing external-request confirmation. Context placeholders in the selected normal and system templates are resolved in the AI app. `@BOARD@` is only expanded for report presentation.

Optional references can select AI-owned templates by stable ID or unique name:

```xml
<prompt>Chess position analysis</prompt>
<systemprompt>Chess coach</systemprompt>
<type>Classic</type><select>
<open>@BOARD@</open>
```

The referenced templates must already exist in the AI app. A missing or ambiguous reference returns to the picker. Older callers that supply a prompt extra remain supported by the AI app.

## Existing Eval settings

Settings schema v3 uses `aiInstructions` and the preference key `ai_instructions_list`. The upgrade retains old entry IDs, names and instruction text; a legacy email field becomes an `<email>` instruction. Old prompt, system-prompt and category fields are not retained in active Eval storage. Schema v2 exports and legacy preference-map exports can still be imported. New exports contain only instruction entries.
