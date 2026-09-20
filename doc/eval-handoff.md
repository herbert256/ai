# Eval → AI report handoff

Eval stores named instruction entries (`id`, `name`, `instructions`). Both position and player reports require the user to choose an entry. Prompts and system prompts are created and stored in the AI app.

Eval sends `com.ai.ACTION_NEW_REPORT`, restricted to package `com.ai`, with `title` and `instructions` extras. There are no `prompt` or `system` extras.

Eval leaves placeholders in the selected instruction text unchanged and sends the eight standard context tags below, even when a value is unavailable. Repeated placeholders share one data field. When the interface uses a date placeholder, Eval also sends one `date` tag with the actual current local date. Existing top-level declarations of these supplied fields are deduplicated.

```xml
<fen>r4rk1/1b2bppp/ppq1p3/2ppB2n/5P2/1P1BP3/P1PPQ1PP/R4RK1 w - - 0 15</fen>
<color>White</color>
<server></server>
<player>White</player>
<pgn>[FEN "r4rk1/1b2bppp/ppq1p3/2ppB2n/5P2/1P1BP3/P1PPQ1PP/R4RK1 w - - 0 15"]

*</pgn>
<board>Generated board HTML and JavaScript</board>
<moves>All legal moves, each with its Stockfish evaluation</moves>
<engine>The best N Stockfish continuations with scores and search depth</engine>
```

- `fen`: the current position, including an explored variation.
- `color`: `White` or `Black`, read from that FEN.
- `server`: `lichess.org` or `chess.com` when known. Local FEN positions have no server.
- `player`: for position reports, the side-to-move player's name; for profile reports, the selected player.
- `pgn`: the available full game PGN. The separate FEN is authoritative for the current position.
- `engine`: the best N Stockfish lines, ranked for the side to move, each with its continuation in SAN and UCI, White-perspective evaluation, and search depth.
- `moves`: every legal move at the captured FEN, including all promotions, with SAN, UCI, Stockfish evaluation and search depth. Scores use White's perspective: positive favors White; negative favors Black; +M/-M marks mate for White/Black.
- `board`: generated chessboard HTML/JavaScript, intended for report presentation.

A player-only report has empty FEN, color, PGN, board, moves and engine tags. It does not inherit the last opened game.

Plain values use XML escaping (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`). The receiver decodes those values once. Board markup is raw inside its enclosing tag. All eight context tags and `<open>`/`<close>` bodies must be removed before interpreting control tags, so markup and PGN are never interpreted as commands.

Instructions may use `@FEN@`, `@COLOR@`, `@SERVER@`, `@PLAYER@`, `@PGN@`, `@BOARD@` and `@DATE@`. For example:

```xml
<select><next>View</next>
<open>@BOARD@</open>
```

The AI receiver resolves placeholders in the selected prompt, system prompt and opening/closing report content. Eval never expands those templates. Standard context remains available to templates saved only in AI. A prompt or system template explicitly using the board placeholder receives its supplied value; merely sending board data does not include it in model requests. See the shared custom-intent contract for selection and substitution details.

Optional references can select AI-owned templates by stable ID or unique name:

```xml
<prompt>Chess position analysis</prompt>
<system>Chess coach</system>
<select>
<open>@BOARD@</open>
```

Matching templates are selected from the AI app. If a `<prompt>` value does not resolve to a saved definition (including an ambiguous name), its content becomes the normal report prompt with placeholders expanded; AI skips the saved-prompt picker. An unresolved `<system>` value becomes literal system-prompt text independently. Older callers that supply a prompt extra remain supported by the AI app.

## Existing Eval settings

Settings schema v3 uses `aiInstructions` and the preference key `ai_instructions_list`. The upgrade retains old entry IDs, names and instruction text; a legacy email field becomes an `<email>` instruction. Old prompt, system-prompt and category fields are not retained in active Eval storage. Schema v2 exports and legacy preference-map exports can still be imported. New exports contain only instruction entries.

## Moves list for AI

Eval includes `<moves>` with every position handoff so `@MOVES@` works in prompts and system prompts saved only in AI. The fourth Settings → Stockfish card in Eval controls time per move, threads, hash memory and NNUE independently of board analysis. Defaults are 0.25 seconds per move, one thread, 32 MB and NNUE on. Preparation is cancellable; search failures do not send a partial list. Terminal positions send “No legal moves in this position.” Player-only reports have an empty moves field. AI uses its existing named-value substitution; Eval keeps template tokens unchanged.

## Engine moves for AI

Eval includes `<engine>` with every position handoff so `@ENGINE@` works in prompts and system prompts saved only in AI. The fifth Settings → Stockfish card in Eval controls number of lines (1–32), seconds per position (shared across the lines), threads, hash memory and NNUE. Defaults are three lines, two seconds, one thread, 32 MB and NNUE on. The last complete MultiPV iteration at one depth supplies the ranked continuations and scores. If fewer legal moves exist, Eval supplies the available lines. Player-only requests have an empty engine field; terminal positions report no legal moves. Preparation is cancellable. Both engine data sets use the same captured position, and AI performs all template substitution.
