# Backup & Restore

`BackupManager` round-trips the entire app — settings, chat
history, reports, knowledge bases, traces, pricing snapshots, and
all the bookkeeping that makes a restored install pick up exactly
where the source install left off — into a single `.zip` file.

The user opens it from **Settings → Housekeeping → Backup & Restore
→ Backup** (write to a SAF-picked location) and **Restore** (read
from a SAF-picked location). One Activity, one `BackupManager`
object, no service.

## Zip layout

```
ai-backup-YYYYMMDD-HHMMSS.zip
├── manifest.json
├── prefs/
│   ├── eval_prefs.json
│   ├── provider_registry.json
│   ├── provider_field_timestamps.json
│   ├── pricing_cache.json
│   ├── dual_chat_prefs.json
│   └── huggingface_cache.json
├── files/
│   ├── reports/<reportId>.json
│   ├── secondary/<reportId>/<resultId>.json
│   ├── chat-history.json
│   ├── trace/<hostname>_<ts>_<seq>.json
│   ├── applog/applog_<yyyyMMdd>.log
│   ├── knowledge/<kbId>/manifest.json
│   ├── knowledge/<kbId>/files/<localCopy>
│   ├── knowledge/<kbId>/chunks/<sourceId>.json
│   ├── embeddings/<sha256>.json
│   ├── model_lists/<providerId>.json
│   ├── pricing/<key>.json
│   ├── prompt-history.json
│   ├── prompt_cache/...
│   └── usage-stats.json
└── cache/
    └── (exports, shared traces, camera captures — except
         in-flight temp files matching CACHE_TOPLEVEL_SKIP_PREFIXES)
```

Files are written verbatim. Prefs are encoded as a list of
`{k, t, v}` objects — `t` is a one-letter type discriminator
(`s` String, `b` Boolean, `i` Int, `l` Long, `f` Float,
`ss` String set) so values round-trip through Gson without an
Int silently coming back as a Double. Unknown type tags log a
warning instead of silently dropping the entry.

Symlinks under `filesDir` are skipped on backup so a maliciously
linked external file can't end up in the zip. The skip is
applied by canonical-path comparison against the parent dir, so
a directory whose child happens to be a regular file is not
itself mistakenly dropped (an earlier bug).

Restore caps both per-entry and total uncompressed bytes — a
malformed zip can't blow up `filesDir` or `cacheDir` by claiming
a 200 GB entry.

## What's included

### Prefs (`PREFS_TO_BACKUP`)

| Pref file | What it carries |
|---|---|
| `eval_prefs` | All user-curated settings: API keys, per-provider model + endpoint config, agents / flocks / swarms / parameters / system prompts / internal prompts (the legacy `ai_meta_prompts` key, kept for compat) / example prompts, and the various per-screen recents (last selections for Reports, the report title/prompt, the secondary-pickers per-(report, prompt) state) |
| `provider_registry` | Custom provider definitions added or imported by the user — keyed by provider id, merged with `assets/providers.json` at runtime |
| `provider_field_timestamps` | Per-provider per-field "user-touched-at" timestamps so the every-start `providers.json` sync knows which fields the user has edited. Loss here turns user edits into "asset values overwritten on next boot", so this prefs file is in the backup set |
| `pricing_cache` | Timestamps for each pricing tier + the user's manual price overrides. The bulk pricing JSON itself lives in `files/pricing/` (see below) |
| `dual_chat_prefs` | Last-used Dual Chat configuration plus the recent-subjects / recent-prompts ring buffers |
| `huggingface_cache` | 7-day-TTL HuggingFace model-info lookups (positive **and** negative — a cached miss avoids a re-fetch storm on a model HF doesn't have). Concurrent load-modify-save is serialised to prevent torn writes |

### Files (under `<filesDir>`)

Everything in `<filesDir>` except the two top-level entries listed
in `FILES_DIR_BACKUP_EXCLUDES` (see next section). Notable contents:

- `reports/`, `secondary/`, `chat-history.json`,
  `prompt-history.json` — the user's content
- `trace/` — captured API traces (subject to whatever cutoff the
  user set). Auth headers redacted at write time
- `applog/applog_<yyyyMMdd>.log` — daily-rotating app log
  produced by `com.ai.data.AppLog`. Sensitive headers + raw API
  keys are redacted inline before write so the backed-up logs
  never carry plain secrets
- `knowledge/<kbId>/` — KB manifests, chunk JSONs (with
  `FloatArray` embeddings serialised as numeric arrays), and
  the locally-persisted source files
- `embeddings/` — the per-document embedding cache that backs
  Local / Remote semantic search
- `pricing/` — the LiteLLM, models.dev, OpenRouter, Together,
  Helicone, llm-prices and Artificial Analysis tier blobs
- `model_lists/` — the most recent `/models` raw JSON per
  provider, used by the Model Info screen
- `model_pricing.json`, `model_supported_parameters.json` —
  supplementary catalogs (atomic writes)
- `prompt_cache/` — `PromptCache` entries (Model Info "model
  info" prompt and similar TTL-cached responses)
- `usage-stats.json` — per-(provider, model, kind) cumulative
  costs that drive the AI Usage screen

### Cache (under `<cacheDir>`)

The backup also mirrors `cacheDir` (exports, shared-trace handoffs,
camera captures, bulk-export staging) but **skips** in-flight temp
files whose names match `CACHE_TOPLEVEL_SKIP_PREFIXES`:

- `ai-restore-` — the temp zip the current restore is reading
- `reset_keys_` — API keys in plaintext written by the reset
  orchestrator (would leak keys if archived)
- `ai-backup-` — defensive — should the backup ever stage a
  temp file, exclude it.

## What's excluded

### `FILES_DIR_BACKUP_EXCLUDES`

```kotlin
internal val FILES_DIR_BACKUP_EXCLUDES = setOf("local_llms", "local_models")
```

Both directories hold user-supplied on-device model bundles:

- `local_llms/<name>.task` — Gemma / Phi / Llama and similar
  bundles for `LocalLlm`. Hundreds of MB to several GB each.
  Sourced via SAF after the user accepts the model card terms in
  a browser — see [local-runtime.md](local-runtime.md).
- `local_models/<name>.tflite` — MediaPipe Tasks TextEmbedder
  models for `LocalEmbedder`. Tens to hundreds of MB each.

Excluding them from backup keeps the zip small (a settings
backup measured in MB instead of GB) and avoids redistributing
weights the user provisioned through the original hand-off
flow. They're also **preserved** on restore (see below) so the
user doesn't lose them when restoring a backup that didn't
ship with them.

### Other things deliberately not in the zip

- `WebViewChromiumPrefs` — Chromium cookies and web-process
  state. Doesn't make sense across devices.
- Any SharedPreferences file not in `PREFS_TO_BACKUP`. New prefs
  files added to the app must be added explicitly.

## Restore: validate-then-write

`restore(context, input)` is two-pass:

1. **Validate** — `readAllEntriesValidated` copies the SAF stream
   into a temp file in `cacheDir`, then walks every zip entry,
   decompresses it into memory, and stages it in a
   `LinkedHashMap<String, ByteArray>`. Any read or truncation
   failure throws here, **before** the destructive wipe step.
   Path-traversal guard: every `files/<rel>` entry has its
   resolved canonical path checked against the canonical
   `filesDir` root; entries that escape are dropped with a
   `Log.w`.
2. **Apply prefs** — `applyPrefsOnly` commits every
   `prefs/<name>.json` entry into SharedPreferences. Prefs go
   first so a process death between writes leaves prefs valid +
   filesDir empty (re-restorable), rather than the inverse where
   filesDir is partially written and prefs still point at the
   pre-restore state.
3. **Apply files** — `clearFilesDirForRestore` wipes filesDir
   (preserving `FILES_DIR_BACKUP_EXCLUDES`) plus the staged cache
   entries, then writes the staged map to disk. Each restored
   file is **fsync'd** before the caller is allowed to kill the
   process.
4. **Merge providers** — `mergeMissingProvidersFromSetup` reads
   `assets/providers.json` and grafts in any provider id the
   running build has but the restored prefs don't (handles "old
   backup, new app version, new provider").

The manifest's `version` field is **read first** — a missing or
unparseable manifest fails fast (sentinel −1) so a random zip
can't accidentally trigger the destructive wipe. Reject also fires
if the manifest version exceeds the running build's
`MANIFEST_VERSION` (currently `1`).

Memory cost: full uncompressed payload during the staging
pass. Acceptable because backups are typically 10–50 MB, and
the SAF copy already held that much in cacheDir.

## Preservation across the restore wipe

`clearFilesDirForRestore` skips the same `FILES_DIR_BACKUP_EXCLUDES`
set when wiping:

```kotlin
filesDir.listFiles()?.forEach {
    if (it.name !in FILES_DIR_BACKUP_EXCLUDES) it.deleteRecursively()
}
```

Effect: a backup taken on Device A (with no local LLMs) restored
to Device B (with a multi-GB Gemma 3 install in `local_llms/`)
keeps Device B's local models in place. Without this guard the
restore would silently destroy them — the backup didn't include
them, the wipe took them out, and the user would have to re-import
the multi-GB bundle.

A unit test
(`BackupManagerRestoreTest.clearFilesDirForRestore_preserves_local_model_dirs`)
seeds both directories, runs the wipe, and asserts both are still
there.

## Provider catalog merge

`mergeMissingProvidersFromSetup` runs after the prefs and files
have been written. It reads the bundled `assets/providers.json`,
parses every provider definition, and adds any whose `id` isn't
already in the restored `provider_registry` prefs. The number of
new providers is returned in `RestoreSummary.newProviders` and
shown to the user.

This handles the otherwise-confusing "old backup, new app
version" case: backup was taken when only N providers shipped,
the new app version added Cloudflare / DeepInfra / Nebius / etc.,
the restore would otherwise leave them invisible until a manual
prefs wipe.

## Manifest version

```kotlin
private const val MANIFEST_VERSION = 1
```

`restore` reads the manifest's `version` field. Bumps:

- `version > MANIFEST_VERSION` → `IllegalStateException` ("Backup
  is from a newer app version (N). Please update the app.")
- `version < 1` (missing or unparseable) →
  `IllegalStateException` ("Backup is missing a recognizable
  manifest.json — refusing to restore.") — defensive guard so a
  random zip doesn't trigger the destructive wipe.

Bump `MANIFEST_VERSION` only when the format changes in a way an
old restore can't read.

## Singleton staleness after restore

Restoring writes everything to disk, but the running process
holds in-memory copies that are now out of sync:

- `ProviderRegistry` — provider list cached at startup
- `ApiTracer` — `cachedTraceFiles`, `currentReportId`,
  `currentCategory`
- `PromptCache` — entries map
- `ReportStorage`, `ChatHistoryManager` — file caches
- `PricingCache` — every tier map
- `AppViewModel.UiState` — agents, flocks, swarms, parameters

Rather than reload each one in place (lots of moving parts, lots
of recompositions to coordinate), the restore flow asks the user
to restart the app. The restart gate that the Refresh-all flow
uses applies here too:

```kotlin
val launch = context.packageManager.getLaunchIntentForPackage(...)
launch?.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK
context.startActivity(launch)
Runtime.getRuntime().exit(0)
```

The next launch re-reads everything from disk and ends up
exactly where the source install left off (modulo running coroutines
and StateFlow subscribers).

## Code & tests

Implementation: `data/BackupManager.kt`.
Entry points: Housekeeping screen's Backup & Restore card →
Backup / Restore buttons.

Tests:

- `ai/src/test/java/com/ai/data/BackupManagerRestoreTest.kt` —
  unit tests for `clearFilesDirForRestore` (wipes content,
  creates missing dir, **preserves** `local_llms/` and
  `local_models/`).
- `ai/src/androidTest/java/com/ai/data/ApiTracerInstrumentedTest.kt`
  — full-cycle `saveTrace` / `getTraceFiles` /
  `clearTraces` / `deleteTracesOlderThan` (relevant because
  trace files round-trip through the backup zip and the
  in-memory cache is exercised by these tests).

When changing what's in the backup, update both
`PREFS_TO_BACKUP` (if a prefs file is added) and the
[persistent.md](persistent.md) "What's NOT in the backup zip"
section.
