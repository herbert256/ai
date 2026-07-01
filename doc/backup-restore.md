# Backup & Restore

`BackupManager` (`data/BackupManager.kt`) round-trips the entire
app — settings, chat history, reports, traces, pricing snapshots,
and all the bookkeeping that makes a restored install pick up
exactly where the source install left off — into a single `.zip`
file.

The user reaches it from **Settings → Housekeeping → Backup &
Restore** (`ui/admin/BackupRestoreScreen.kt`, help topic
`backup_restore`):

- **Backup** streams the zip into a temp file and hands it to the
  Android **share sheet** (`shareExport(..., chooserTitle = "Share
  backup")`) — Email, Drive, Files, Slack, anything installed.
  The default name is `ai-backup-<yyyyMMdd-HHmmss>.zip`.
- **Restore** opens a SAF picker (`ActivityResultContracts.OpenDocument`),
  shows a confirm dialog, then runs `BackupManager.restore`.

One object, no service. After a successful restore the screen
shows a sticky `RestartAppBanner` ("Restored *N* prefs + *M*
files") because the running process now holds stale in-memory
state (see [Singleton staleness](#singleton-staleness-after-restore)).

When `restoreOnly` is set (the post-Reset / no-API-key path) the
Backup half of the screen is hidden and only Restore is offered.

## Zip layout

```
ai-backup-YYYYMMDD-HHMMSS.zip
├── manifest.json                       # version, timestamp, appVersion, packageName
├── prefs/                              # one per PREFS_TO_BACKUP entry (8 files)
│   ├── eval_prefs.json
│   ├── provider_registry.json
│   ├── pricing_cache.json
│   ├── dual_chat_prefs.json
│   ├── huggingface_cache.json
│   ├── cloudprice_model_cache.json
│   ├── model_cooldowns.json
│   └── view_screen_prefs.json
├── files/                              # all of filesDir except
│   │                                   # FILES_DIR_BACKUP_EXCLUDES (top level)
│   ├── reports/<reportId>.json
│   ├── secondary/<reportId>/<resultId>.json
│   ├── trace/<hostname>_<ts>_<seq>.json
│   ├── chat-history/<sessionId>.json
│   ├── embeddings/<sha256>.json
│   ├── knowledge/<kbId>/...
│   ├── pricing/<key>.json
│   ├── model_lists/<providerId>.json
│   ├── prompt_cache/...
│   ├── regenerate/<reportId>.json
│   ├── audit/<reportId>.log
│   ├── crash/last-crash.txt
│   ├── model_supported_parameters.json
│   ├── internal_prompt_icons.json
│   ├── prompt-history.json
│   ├── usage-stats.json
│   ├── usage-category-stats.json
│   ├── usage-report-stats.json
│   └── test_run.json
└── cache/                             # mirror of cacheDir, minus
    └── ...                            # CACHE_TOPLEVEL_SKIP_PREFIXES temp files
```

Files are written **verbatim**. Prefs are encoded as a JSON list
of `{k, t, v}` objects — `t` is a one-letter type discriminator
(`s` String, `b` Boolean, `i` Int, `l` Long, `f` Float, `ss`
`Set<String>`) so values round-trip through Gson without an Int
silently coming back as a Double. On apply, an entry whose type
tag isn't one of those six is **logged and skipped**
(`"applyPrefs(<name>): unknown type tag '<t>' for key '<k>' — entry skipped"`).

`manifest.json` carries `version` (= `MANIFEST_VERSION`, currently
`1`), `timestamp` (epoch ms), `appVersion` (best-effort
`versionName`, `"?"` on failure), and `packageName`.

### Symlink & traversal safety

- **Backup** does not follow symlinks. `addDirectoryRecursive`
  canonicalises each child against the *parent's* canonical path
  and skips any child that resolves outside the tree. An earlier
  bug compared `child.canonicalPath != child.absolutePath`, which
  always fired on Android (`/data/user/0` is a symlink to
  `/data/data`), so *every* child was skipped and backups
  contained only the manifest + prefs (the "0 files" bug). The
  fix compares against the parent canonical path so real children
  resolve under it while escaping symlinks don't.
- **Restore** canonical-path-checks every `files/<rel>` and
  `cache/<rel>` entry against the corresponding root before
  staging, dropping (with a `Log.w`) any entry that escapes — a
  `files/../shared_prefs/...` style attack is rejected.

### Zip-bomb / OOM caps

Restore enforces two uncompressed-byte caps during the validated
read, both checked byte-by-byte so they bail *before* the
destructive wipe:

| Cap | Value | Constant |
|---|---|---|
| Per entry | 256 MB | `MAX_RESTORE_ENTRY_BYTES` |
| Cumulative total | 1 GB | `MAX_RESTORE_TOTAL_BYTES` |

Exceeding either throws `IllegalStateException` and leaves
`filesDir` untouched.

## What's included

### Prefs (`PREFS_TO_BACKUP`)

Only **8 of the app's 11 SharedPreferences files** are backed up:

| Pref file | What it carries |
|---|---|
| `eval_prefs` | The main store. All user-curated settings: API keys, per-provider model + endpoint config, agents / flocks / swarms / parameters / system prompts / internal prompts (stored under the legacy `ai_meta_prompts` key) / example prompts, blocked / test-excluded / inaccessible model lists, throttle limits, and per-screen recents (last report title/prompt, last selections, secondary-picker state). |
| `provider_registry` | Custom provider definitions added or imported by the user, keyed by provider id. The 91 bundled providers come from `assets/providers/` (one JSON file per provider) at runtime, not from this file. |
| `pricing_cache` | Per-tier timestamps + the user's **manual** price overrides. The bulk pricing JSON itself lives in `files/pricing/` (below). |
| `dual_chat_prefs` | Last-used Dual Chat configuration plus the recent-subjects / recent-prompts ring buffers. |
| `huggingface_cache` | 7-day-TTL HuggingFace model-info lookups (positive **and** negative — a cached miss avoids a re-fetch storm on a model HF doesn't have). |
| `cloudprice_model_cache` | 7-day-TTL per-model CloudPrice detail lookups (positive and negative), the direct sibling of the HuggingFace cache — backs the lazy CloudPrice call on Model Info. |
| `model_cooldowns` | Models auto-benched after a 429 with a long retry-after, plus the per-model trace filename of the benching 429 (see [model-states.md](model-states.md)). |
| `view_screen_prefs` | The reorderable View-grid tile order — single string key `tile_order` holding a comma-separated list of tile ids. The user explicitly arranged the grid (e.g. "Costs first"), so the order survives a round-trip. |

The **3 prefs files NOT backed up** are all recomputable or
device-local: `provider_field_timestamps` (a null lookup just
means "refresh this field from the asset on next boot"),
`last_report_tracker`, and `update_from_cloud`.
`WebViewChromiumPrefs` (Chromium cookies / web-process state) is
also intentionally excluded, but it's created by the WebView
system rather than app code, so it isn't counted among the 11.
New prefs files added to the app must be added to
`PREFS_TO_BACKUP` explicitly to be archived.

> **`rankingWeights` is captured.** The "Ranking weights" map
> (`GeneralSettings.rankingWeights`, edited under Settings) is
> stored sparsely as JSON under the `ranking_weights` key in
> `eval_prefs` by `SettingsPreferences.saveGeneralSettings`, so —
> because backup serialises `eval_prefs` verbatim — it rides along
> in the backup and survives an app restart. (`showLadybugIcons`,
> under `show_ladybug_icons`, is likewise covered.)

### Files (under `<filesDir>`)

Everything in `<filesDir>` is mirrored into `files/` **except**
the four top-level `FILES_DIR_BACKUP_EXCLUDES` subdirs (below).
Notable contents:

- `reports/`, `secondary/`, `chat-history/`, `prompt-history.json`
  — the user's content.
- `trace/` — captured API traces (subject to whatever cutoff the
  user set). Secrets are redacted at write time, so they never
  reach the zip.
- `knowledge/` — RAG knowledge bases (manifest + per-source chunk
  files + locally-cached source copies). **Embeddings travel
  inside these chunk files**, so a populated KB is the realistic
  worst case for the per-entry cap.
- `embeddings/` — the per-document embedding cache that backs
  remote semantic search (distinct from KB chunks).
- `pricing/` — the LiteLLM, models.dev, OpenRouter, Together,
  Helicone, llm-prices, Artificial Analysis, Requesty, llm-stats,
  genai-prices and TrueFoundry tier blobs, plus CloudPrice's
  capabilities-only catalog blob and the top-level
  `model_supported_parameters.json` catalog.
- `model_lists/` — the most recent `/models` raw JSON per
  provider.
- `prompt_cache/`, `regenerate/`, `audit/`, `crash/`,
  `internal_prompt_icons.json` — bookkeeping the restored install
  re-reads as-is.
- `usage-stats.json`, `usage-category-stats.json`,
  `usage-report-stats.json` — the cumulative cost/usage stores
  that drive the AI Usage screen.
- `test_run.json` — the single most-recent "Test all models" run
  (`ModelTestRunStore`); not excluded, so it round-trips.

### Cache (under `<cacheDir>`)

The backup also mirrors `cacheDir` (exports, shared-trace
handoffs, camera captures, bulk-export staging) but **skips**
top-level temp files whose names match
`CACHE_TOPLEVEL_SKIP_PREFIXES`:

- `ai-restore-` — the temp zip a restore is reading from.
- `reset_keys_` — API keys written in plaintext by the reset
  orchestrator. Archiving these would leak keys, so they're never
  copied into a backup.
- `ai-backup-` — defensive; should a backup ever stage a temp
  file under this prefix, exclude it.

## What's excluded

### `filesDir` subdirs (`FILES_DIR_BACKUP_EXCLUDES`)

**Four** top-level `filesDir` subdirs are never copied into the
zip **and** never deleted during the restore wipe (so a user who
has them installed on the target device doesn't lose them when
restoring an unrelated settings/data backup):

- `local_llms/` — user-supplied on-device LLM `.task` bundles
  (hundreds of MB to several GB each).
- `local_models/` — MediaPipe TextEmbedder `.tflite` files
  (~50–500 MB each).
- `native/` — the MediaPipe LLM inference native runtime
  (`.so`, ~26 MB, tied to the device ABI; re-downloadable from
  Local LLMs setup).
- `applog/` — the daily-rotating in-app file logs (device-local
  diagnostics, regenerated continuously).

The first two are user-installed model weights; the latter two
are large/device-specific or trivially regenerated. The same set
is the preserve list in `clearFilesDirForRestore`, so restoring
never destroys them.

### Other things deliberately not in the zip

- `WebViewChromiumPrefs` — Chromium cookies / web-process state;
  doesn't make sense across devices.
- The 3 non-backed-up SharedPreferences files listed above.

## Restore: validate-then-write

`restore(context, input): RestoreSummary` is deliberately ordered
so that a crash at any point leaves a re-restorable state, never a
half-wiped install. The safety design is **stage everything in
memory first, destroy second**:

1. **Copy** the SAF input stream into a temp file
   `ai-restore-<…>.zip` in `cacheDir`.
2. **Check the manifest** — `readManifestVersion` scans the zip
   for `manifest.json` and reads its `version` field *before*
   anything destructive. A missing or unparseable manifest yields
   the sentinel `-1`; the restore then rejects:
   - `version < 1` → `IllegalStateException` ("Backup is missing a
     recognizable manifest.json — refusing to restore.") — the
     positive proof that this is an AI-app backup, so a random zip
     can't trigger the wipe.
   - `version > MANIFEST_VERSION` → `IllegalStateException`
     ("Backup is from a newer app version (*N*). Please update the
     app.").
3. **Validate** — `readAllEntriesValidated` walks every kept zip
   entry, decompresses it into memory (subject to the per-entry /
   total caps and the path-traversal check), and stages it in a
   `LinkedHashMap<String, ByteArray>`. Any IOException or
   truncation throws **here**, before the destructive wipe.
4. **Sanity floor** — if the staged map contains **no `files/`
   entry at all**, restore throws `IllegalStateException`
   ("Backup contains no data files — refusing to restore; your
   current data is untouched.") *before* any prefs apply or wipe.
   This is the runtime guard against the historical "0 files" /
   symlink-skip regression (see [above](#symlink--traversal-safety)):
   a structurally-valid backup that happens to carry zero data
   files would otherwise wipe the device's reports / chats / KBs
   and write nothing back.
5. **Apply prefs** — `applyPrefsOnly` commits every
   `prefs/<name>.json` entry into its SharedPreferences file via
   `edit().clear()...commit()` (synchronous, atomic per file).
   Prefs go first so a process death between this step and the
   file pass leaves prefs valid + `filesDir` empty (re-restorable),
   rather than the inverse where `filesDir` is partly written but
   prefs still point at the pre-restore state.
6. **Wipe `filesDir`** — `clearFilesDirForRestore` deletes every
   top-level child except the `FILES_DIR_BACKUP_EXCLUDES` preserve
   set.
7. **Wipe `cacheDir`** — `clearCacheDirForRestore(preserve =
   {tempZip.name})` deletes everything except the in-flight
   restore zip.
8. **Apply files** — `applyFilesOnly` writes every staged
   `files/` and `cache/` entry to disk. Each file is
   **fsync'd** (`FileDescriptor.sync()`) before returning, because
   `HousekeepingScreen` kills the process immediately afterward
   and SAF/close doesn't fsync — otherwise a restored file could
   surface partial/empty content on the next launch.

Steps 6–8 are the destructive phase — the wipe has already begun,
so any exception there is caught and rethrown as
`RestoreAfterWipeException` instead of propagating raw. The UI
uses that type to decide its message: a failure in steps 1–5 gets
"existing data left unchanged", while `RestoreAfterWipeException`
gets "your data may be incomplete — re-run restore from the same
backup file", since the wipe already ran.

`restore` returns `RestoreSummary(version, prefsFiles, dataFiles)`
— the version restored plus the count of prefs files and data
files written. The UI renders these as "Restored *N* prefs + *M*
files".

There is **no provider-merge step.** The user's full provider
registry rides along in the `provider_registry` prefs file, so the
registry rebuilds straight from disk on next launch.
`ProviderRegistry.importFromAsset` is the only path that grafts
newly-bundled providers in, and it runs on demand from the
Providers screen, not from restore. It reads every JSON file under
`assets/providers/` (one bare `ProviderDefinition` per file, sorted
by filename for a deterministic merge) and **appends only the ids
not already present** — existing rows are left strictly alone, no
field overwrites — returning the count added (or `-1` on a broken
bundle). The sibling `upsertFromJson` (user-picked
`{ "providers": [...] }` blob from the Providers screen's import
button) instead **replaces by id or appends**; neither is invoked
by restore. (Earlier drafts of this doc described a
`mergeMissingProvidersFromSetup` step and a
`RestoreSummary.newProviders` field — neither exists in the code.)

Memory cost: the full uncompressed payload is held during the
staging pass. Acceptable because backups are typically 10–50 MB
and the SAF copy already held that much in `cacheDir`.

## Manifest version

```kotlin
private const val MANIFEST_VERSION = 1
```

The accepted manifest-version range is effectively exactly `1`:
restore rejects `< 1` (missing/unparseable) and `> 1` (newer
app). Bump `MANIFEST_VERSION` only when the format changes in a
way an old restore can't read.

> This is **separate** from the per-report export bundle's
> `EXPORT_VERSION` (also `1`, in `data/ReportBundle.kt`), which
> governs the single-report share/import path — a different zip
> format entirely (see [persistent.md](persistent.md)).

## Singleton staleness after restore

Restoring writes everything to disk, but the running process holds
in-memory copies that are now out of sync:

- `ProviderRegistry` — provider list cached at startup
- `ApiTracer` — `cachedTraceFiles`, `currentReportId`, `currentCategory`
- `PromptCache` — entries map
- `ReportStorage`, `ChatHistoryManager` — file caches
- `PricingCache` — every tier map
- `AppViewModel.UiState` — agents, flocks, swarms, parameters

Rather than reload each one in place (lots of moving parts, lots
of recompositions to coordinate), the restore flow asks the user
to restart the app. The shared restart helper
(`ui/shared/RestartAppDialog.kt`, also used by Import-all / Reset
/ Refresh-all) relaunches the launcher activity in a fresh task
and kills the process:

```kotlin
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
}
```

The next launch re-reads everything from disk and ends up exactly
where the source install left off (modulo running coroutines and
StateFlow subscribers).

## Code & tests

Implementation: `data/BackupManager.kt`.
UI: `ui/admin/BackupRestoreScreen.kt`, reached from the
Housekeeping screen's Backup & Restore card.

Tests:

- `ai/src/test/java/com/ai/data/BackupManagerRestoreTest.kt` —
  three unit tests for `clearFilesDirForRestore`:
  `clearFilesDirForRestore_removes_existing_files_before_restore`,
  `clearFilesDirForRestore_creates_missing_files_dir`, and
  `clearFilesDirForRestore_preserves_local_model_dirs` (seeds
  `local_llms/` + `local_models/`, runs the wipe, asserts both
  survive while real content is removed).
- `ai/src/androidTest/java/com/ai/data/ApiTracerInstrumentedTest.kt`
  — full-cycle `saveTrace` / `getTraceFiles` / `clearTraces` /
  `deleteTracesOlderThan` (relevant because trace files round-trip
  through the backup zip and exercise the in-memory cache).

When changing what's in the backup, update both `PREFS_TO_BACKUP`
(if a prefs file is added) and the [persistent.md](persistent.md)
"What's NOT in the backup zip" section.
