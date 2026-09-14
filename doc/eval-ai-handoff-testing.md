# Eval → AI handoff test results

Tested on 2026-09-14 using `emulator-5554`, with Eval `26.257.614`
and AI `26.249.607`. Eval was rebuilt from its current working tree.
Both installed builds have matching local and cloud APK copies.

**Result: the request contract works, but position handoff exposes a
reproducible Eval ANR.** AI completed all three test reports. Eval was
killed during both position handoffs; the player handoff returned normally.
No application source was changed during this test session.

Contract references: [AI custom intent](custom-intent.md) and
[Eval caller documentation](../../eval/CALL_AI.md). Their shared contract
blocks still match exactly.

## Reproduced failure

1. In Eval, open the saved DrNykterstein–yoseph2013 game and a position.
2. Choose Share / Export → Generate AI Reports → an AI instruction.
3. Eval launches `com.ai.ACTION_NEW_REPORT` and closes its instruction picker.
4. AI shows the correct external-request confirmation, but Eval fails to
   process its loss-of-focus event within five seconds. Android kills Eval
   in the background. Returning to Eval reloads the saved position.

Observed twice, at **10:18:47** and **10:22:38 Europe/Amsterdam**:

```text
ANR in com.eval (com.eval/.MainActivity)
Input dispatching timed out
Waited 5000ms / 5001ms for FocusEvent(hasFocus=false)
```

`dumpsys activity exit-info com.eval` confirms both exits as
`ANR / INPUT DISPATCHING TIMEOUT ANR`, for PIDs 7428 and 8455.

The second trace catches the main thread in:

```text
Bitmap.createScaledBitmap
DrawableKt.toBitmap
ChessBoardViewKt.ChessBoardView
GameContentKt.GameContent
GameScreenKt.GameScreenContent
```

The first trace catches Compose applying changes. The source explains the
transition: `launchSelectedAiInstruction` dismisses `pendingAiReport` after
launching AI, causing the full-screen instruction picker to leave composition
and the board to be rebuilt. `ChessBoardView` loads twelve piece drawables
with `toBitmap()` inside a composition-local `remember`. That work happens
again when the board re-enters composition. The bundled PNGs are 480 × 480
and are in the density-scaled `drawable` directory.

This makes main-thread piece-image loading/scaling the concrete suspect;
the emulator also showed CPU and memory pressure. A fix should retain the
piece-image cache across overlay changes, avoid unnecessary density scaling,
and move decoding away from the main thread. It needs another real position
handoff test under load before the ANR can be considered resolved.

Source locations:

- [ChessBoardView.kt](../../eval/app/src/main/java/com/eval/ui/ChessBoardView.kt), image loading around line 81.
- [GameViewModel.kt](../../eval/app/src/main/java/com/eval/ui/GameViewModel.kt), `launchSelectedAiInstruction` around line 860.
- [GameScreen.kt](../../eval/app/src/main/java/com/eval/ui/GameScreen.kt), instruction overlay around line 159.

## Checks that passed

Five focused Eval instrumentation tests passed. They cover White/Black
side-to-move fields, empty position fields for player requests, XML escaping,
single-pass sender token expansion, migration of saved instructions, and
settings import/round-trip behavior. These tests intercept the outgoing
intent; the following checks also exercised the actual installed apps.

| Real UI case | Verified behavior |
| --- | --- |
| Cold AI launch, White position, `<select>` | Correct confirmation, model selection and setup; named system/default/parameters resolved; manually started report completed. Eval ANR occurred. |
| Warm AI launch, next half-move, automatic generation | Player changed to `yoseph2013`, color to Black, FEN and board orientation updated; report completed. Eval ANR occurred. |
| Player profile after position reports, `<return>` | Player/server retained; color, FEN, PGN and board were empty; report completed and returned to the Eval player screen. |
| Missing named parameters | Clear missing-preset error; Generate's clickable ancestor disabled; tapping it created no request. |
| Cancel in Eval instruction picker | Returned to the player profile without launching AI. |
| Cancel a valid request in AI confirmation | No additional report or HTTP request. |

All three generated reports used a temporary loopback HTTP responder via
ADB reverse, with one synthetic agent. Metadata and automatic secondary
work were temporarily disabled. No cloud model was called.

Captured HTTP requests and persisted execution configurations agree on:

- Named system/default prompt selection and system-over-preset precedence.
- Temperature **0.23** and maximum output tokens **128**.
- Case-insensitive context placeholders in both prompt types.
- XML decoding: `Castling &amp; safety` became `Castling & safety`.
- Literal inserted value `@player@` remained literal; unknown `@missing@`
  remained unchanged.
- Full PGN and the correct board HTML/orientation for position requests.
- No stale position data in the player request.

Exactly **3 HTTP requests**, **3 successful saved reports**, and **1 primary
call per report** were observed. All had HTTP 200, a completed timestamp,
and the expected responder answer. Invalid/cancelled cases added no calls.

## Evidence and cleanup

Detailed evidence is in `/private/tmp/eval-ai-handoff-20260914/`:
`handoff-logcat.txt`, `anr-logcat.txt`, `eval-exit-info.txt`,
`anr-trace-5.txt`, `anr-trace-6.txt`, `requests.jsonl`,
`verification.json`, UI hierarchy captures, and hydrated report JSON files.

Temporary instructions, agent, endpoint, prompt definitions and parameter
preset were removed. Original configuration catalog hashes match, and the
three original runtime flags were restored. The three synthetic reports,
their traces and their estimated usage were removed; unrelated usage rows
were preserved. The loopback responder and ADB reverse mapping were stopped.

APK SHA-256:

```text
AI:   261ba70fe74f85bfa7b2f6ea6732eb4a932b99bf00098dfd45241141f453c670
Eval: 1b8c1403d7f2ab5d0fb4761e6424496abab617df52eac66804914f465366c15b
```

This session validates request transport, resolution and primary-report
persistence. It does not validate live cloud-model quality, email delivery,
all export formats, or a fix for the Eval ANR.
