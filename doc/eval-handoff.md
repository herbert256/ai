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
- `board`: generated chessboard HTML/JavaScript, intended for report presentation.

A player-only report has empty FEN, color, PGN and board tags. It does not inherit the last opened game.

Plain values use XML escaping (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`). The receiver decodes those values once. Board markup is raw inside its enclosing tag. All six context tags and `<open>`/`<close>` bodies must be removed before interpreting control tags, so markup and PGN are never interpreted as commands.

Instructions may use `@FEN@`, `@COLOR@`, `@SERVER@`, `@PLAYER@`, `@PGN@`, `@BOARD@` and `@DATE@`. For example:

```xml
<type>Classic</type><select><next>View</next>
<open>@BOARD@</open>
```

The AI receiver uses worker defaults for an instruction-only request naming an Agent, Flock or Swarm; otherwise it opens its saved-prompt picker. The user reviews the existing external-request confirmation. Context placeholders in normal, system and default templates are resolved in the AI app. In caller-supplied normal prompts, `@BOARD@` remains presentation-only; a system or worker default template that explicitly includes `@BOARD@` receives the supplied value, like any other named entry. See [custom-intent.md](custom-intent.md#prompt-placeholders).

Optional references can select AI-owned templates by stable ID or unique name:

```xml
<prompt>Chess position analysis</prompt>
<system>Chess coach</system>
<type>Classic</type><select>
<open>@BOARD@</open>
```

Matching templates are selected from the AI app. If a `<prompt>` or `<system>` value does not resolve to a saved definition (including an ambiguous name), its content is used as literal system-prompt text. `<system>` takes priority when both are present. An unresolved `<prompt>` leaves the question to the explicit prompt extra, named default, worker defaults or saved-prompt picker. Older callers that supply a prompt extra remain supported by the AI app.

## Existing Eval settings

Settings schema v3 uses `aiInstructions` and the preference key `ai_instructions_list`. The upgrade retains old entry IDs, names and instruction text; a legacy email field becomes an `<email>` instruction. Old prompt, system-prompt and category fields are not retained in active Eval storage. Schema v2 exports and legacy preference-map exports can still be imported. New exports contain only instruction entries.
