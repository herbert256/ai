# Bug audit — executive summary (audit-3)

Fresh, independent deep code review of the current codebase
(2026-06-06). Produced from scratch against the live source — the
earlier `audit/2026-05-08` and `audit/2026-05-24` were used only as a
format reference, not as input. Four domain reviewers ran in parallel;
findings cite real `file:line`.

## Severity breakdown

| Severity | Reports | Chat | Settings | Data | **Total** |
|---|---|---|---|---|---|
| CRITICAL | 1 | 0 | 0 | 0 | **1** |
| HIGH | 1 | 3 | 1 | 1 | **6** |
| MEDIUM | 13 | 11 | 7 | 13 | **44** |
| LOW | 50 | 62 | 48 | 70 | **230** |
| **Total** | **65** | **76** | **56** | **84** | **281** |

All entries are **Open** — this is a fresh discovery pass; nothing here
has been re-verified-as-fixed or triaged against prior work.

## Critical-class bugs (crash / data loss / feature broken)

### 1. User-notes screen crashes on duplicate group labels
`ui/report/manage/UserNotes.kt:297,319-333` (`ReportNotesListScreen`).
Groups are built per distinct `(targetKind, targetId)`, but the
LazyColumn header uses `item(key = "h:${group.label}")` while
`noteTargetLabel` returns **non-unique** labels for distinct targets —
two reranks both → "Rerank", two deleted targets both → "Deleted item".
Two same-labelled groups produce duplicate LazyColumn keys, which
Compose rejects with `IllegalArgumentException: Key "h:…" was already
used` — the 📒 User-notes screen crashes on open. Very reachable: note
two rerank rows, or annotate two secondaries then delete both targets.
(bugs_reports Bug 59.)
*Fix:* key the header by group identity, not label —
`key = "h:${group.targetKind}:${group.targetId}"` (carry the keying
pair on `Group`), or append the group index.

## HIGH-band, ranked by likely user impact

1. **Dual-chat conversation discarded on rotation / process death** —
   `ui/chat/DualChatScreen.kt:374-396`. `config` is read from `uiState`
   via a plain `remember`, then a `LaunchedEffect(Unit)` clears
   `uiState.dualChatConfig` to null; on activity recreation the plain
   `remember` re-reads the now-null config and immediately
   `onNavigateBack()`s — discarding the entire never-disk-persisted
   conversation, even though `messages` has a careful `rememberSaveable`
   Saver. The manifest has no `android:configChanges`, so rotation
   recreates the Activity. (bugs_chat Bug 23.)
2. **Single-chat turns lost on rotation, then overwritten on disk** —
   `ui/chat/ChatScreens.kt:284`. `messages` is a plain `remember`, so
   rotation resets the on-screen conversation to the stale
   `initialMessages`; the next `saveSession(messages)` then overwrites
   the on-disk session with the truncated set — permanent loss of the
   intervening turns. (bugs_chat Bug 6.)
3. **New-chat session id regenerated on rotation → orphaned session** —
   `ui/chat/ChatScreens.kt:282`. `currentSessionId` uses `remember`
   (not `rememberSaveable`), so a configure-on-the-fly chat mints a new
   UUID on recreation: the prior save is orphaned under the old id, the
   screen becomes a blank new chat, and per-bubble 🐞 trace tagging
   loses continuity. (bugs_chat Bug 7.)
4. **Gson `UnsafeAllocator` nullability — NPE far from a corrupt/legacy
   read** — `data/ApiModels.kt:46-82` (`NullSafeFieldAdapterFactory`).
   Gson constructs via `UnsafeAllocator`, bypassing the primary
   constructor and its defaults; the safety net only coerces
   `Collection`-typed fields, deliberately skipping `String` and
   structurally skipping primitive arrays (`KnowledgeChunk.embedding:
   FloatArray`). Any genuinely-absent non-null non-collection field stays
   `null` inside a type the app trusts as non-null, NPE-ing later past
   the loaders' catch blocks. (bugs_data Bug 1.)
5. **Value view shows every cost 100× too large** —
   `ui/report/view/ValueView.kt:155,262`. `ValuePoint.costCents`
   is already in cents, but it's passed into the shared `formatCents`,
   which expects dollars and multiplies by 100 internally
   (`UiFormatting.kt:28`) — the ×100 is double-applied. A 1.2 ¢ call
   reads "120". (bugs_reports Bug 36.)
6. **Parameters preset silently drops comma-decimal input (nl-NL)** —
   `ui/settings/ParametersScreen.kt:72-81,114-122`. Temperature / Top P /
   penalties parse with `toFloatOrNull()` (locale-independent, `.`-only)
   while the fields use `KeyboardType.Decimal`, which on the user's
   nl-NL device surfaces a comma key. Typing `0,7` returns null and the
   preset persists with the field cleared. (bugs_settings Bug 1.)

## Themes worth fixing in batches

- **Compose state loss on recreation (data loss).** The whole chat HIGH
  cluster (chat 6/7/23) plus related MEDIUMs: conversation/session/config
  held in plain `remember` instead of `rememberSaveable` (or re-seeded
  from disk), and the app declares no `android:configChanges`, so every
  rotation recreates the Activity. One pattern fix (Savers + disk
  re-seed keyed on the session id) covers the class.
- **Gson reflection nullability.** `NullSafeFieldAdapterFactory` coerces
  only collections, not `String`/arrays (data 1, and the cascade in the
  per-entity loaders). A single codec/factory change — or a kotlinx /
  moshi-kotlin codec that honours Kotlin non-null + defaults — fixes the
  whole class of "absent field → NPE far from the read".
- **Cost display & accounting.** Cents-vs-dollars double-multiply
  (reports 36) — audit every `formatCents` call site for whether it's
  fed dollars or cents; AnswerMatrix already uses a cents-native
  formatter, the Value view does not.
- **Locale comma-decimal parsing.** `toFloatOrNull` / `toIntOrNull` on
  user-entered numbers without `,`→`.` normalization (settings 1 and
  other numeric-entry fields) drop or crash on nl-NL. Normalize once in
  a shared parse helper.
- **LazyColumn duplicate keys.** The CRITICAL (reports 59) is a
  label-derived key; sweep other lists whose `key =` is built from a
  display string rather than a stable identity.
- **Restore / persistence safety.** A structurally-valid-but-empty
  backup wipes `filesDir` and restores nothing (data 64); regenerate-
  batch cancel/restart use blind saves that can resurrect a cancelled
  job against the orchestrator's atomic RMW (data 77); other non-atomic
  load→mutate→save paths race a concurrent cancel.

## Method & caveats

- Static review only; no runtime reproduction. Severities are the
  reviewers' estimates of impact, not measured.
- Line numbers are accurate as of 2026-06-06 and will drift with edits.
- LOW is the largest bucket by design — it captures suspicious /
  unconfirmed / cosmetic items worth a second look, not confirmed
  defects.
- Produced from scratch against current code; findings are **not**
  cross-checked against `audit/2026-05-08` or `audit/2026-05-24`. Where
  the three agree, confidence is higher; divergence mostly reflects the
  intervening refactors (new `ui/report/manage/**` + `view/**` split,
  the Dependencies screen, the home-bar work, the JDK/SDK bumps) and
  fixes landed since the prior passes.
