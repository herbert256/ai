# Data Bugs

### Bug 1 - Severity: High - Category: Rerank JSON parsing
**Location:** `ai/src/main/java/com/ai/data/RerankModerationApi.kt:261-288` (`extractTopRankedIds`)

**Symptom:** A malformed rerank response can crash the rerank/fan-in path
instead of returning `null`.

**Root cause:** The function catches only the top-level JSON parse.
Per-entry extraction then calls `.asInt` on `id` and `rank`; Gson throws
for primitives such as `"id":"x"` or `"rank":"first"`.

**Reproduction:** Call `extractTopRankedIds("""[{"id":"x","rank":1}]""", 1)`.
The entry extraction can throw even though the doc comment promises `null`
for non-deserializable payloads.

**Proposed fix:** Add safe primitive helpers (`asIntOrNull`) and wrap
entry extraction per row, or parse into a nullable DTO with exception
containment around the whole map step.

**Status:** Fixed — 2280e9a5 (Rerank: don't let a non-numeric id/rank escape the parser)

### Bug 2 - Severity: High - Category: Throttle permit leak
**Location:** `ai/src/main/java/com/ai/viewmodel/ThrottledBatch.kt:232-263` (`PermitHold.yieldFor`)

**Symptom:** A cancelled or interrupted throttled call can leak permits and
deadlock later batch work.

**Root cause:** `yieldFor` releases all permits, sleeps, then reacquires
`subCap`, `global`, and the host permit. Interruptions during the reacquire
loops are not wrapped in a `finally` that releases already reacquired
permits.

**Reproduction:** Interrupt the coroutine/thread while it is between
`subCap.tryAcquire()` success and `ProviderThrottle.acquire(host)`.
`dispose()` sees `held = false`, so it does not release the reacquired
permit.

**Proposed fix:** Track each reacquired permit and undo partial acquisition
in `catch/finally`, or replace blocking loops with an interrupt-safe helper
that returns all partially acquired resources.

**Status:** Fixed — 58dfa590 (release partially re-acquired permits on interrupt in yieldFor)

### Bug 3 - Severity: High - Category: Secondary storage path safety
**Location:** `ai/src/main/java/com/ai/data/SecondaryResult.kt:231-237`, `ai/src/main/java/com/ai/data/SecondaryResult.kt:372-377`, `ai/src/main/java/com/ai/data/SecondaryResult.kt:1021-1030`

**Symptom:** Direct `get`, `exists`, and `delete` calls can operate on an
unsafe result id path.

**Root cause:** Safer write/update helpers validate result ids, but these
three helpers build `File(dir, "$resultId.json")` without rejecting `/`,
`\`, `.`, or `..`.

**Reproduction:** Call `SecondaryResultStorage.get(context, safeReportId,
"../other")` from a future import/debug path. The helper constructs a path
outside the flat result-id namespace.

**Proposed fix:** Share the same `isSafeResultId` guard across all
read/write/delete helpers and add a canonical-child check before touching
the file.

**Status:** Fixed — 58dfa590 (share isSafeResultId across get/exists/delete)

### Bug 4 - Severity: High - Category: Report bundle import resource limits
**Location:** `ai/src/main/java/com/ai/data/ReportBundle.kt:118-123` (`importReportBundle`)

**Symptom:** Importing a crafted or very large bundle can exhaust memory.

**Root cause:** The importer reads every non-directory zip entry fully into
a `MutableMap<String, ByteArray>` before validating type, count, or total
size.

**Reproduction:** Import a zip with many large entries or a compressed entry
that expands far beyond expected report-bundle size. The app materializes
all bytes before it can reject the bundle.

**Proposed fix:** Stream-validate entries, enforce per-entry and total
expanded-size caps, and reject unknown top-level paths before reading the
full body.

**Status:** Fixed — 4a3c3cca (ReportBundle: cap import entry/total size + count)

### Bug 5 - Severity: Medium - Category: Report bundle parsing
**Location:** `ai/src/main/java/com/ai/data/ReportBundle.kt:127-132` (`meta.json`)

**Symptom:** A malformed `meta.json` can throw a low-level JSON exception
instead of returning a controlled import error.

**Root cause:** `JsonParser.parseString(...).asJsonObject` and
`meta.get("exportVersion")?.asInt` are not guarded against non-object roots
or non-integer primitives.

**Reproduction:** Import a bundle whose `meta.json` is `[]` or
`{"exportVersion":"abc"}`.

**Proposed fix:** Wrap meta parsing in a checked import error path and use
safe primitive extraction before the version range check.

**Status:** Fixed — 58dfa590 (route meta.json parse through the controlled import error)

### Bug 6 - Severity: Medium - Category: Report bundle partial corruption
**Location:** `ai/src/main/java/com/ai/data/ReportBundle.kt:154-158`, `ai/src/main/java/com/ai/data/ReportBundle.kt:179-185`

**Symptom:** A single malformed secondary or trace JSON entry can abort the
entire report import.

**Root cause:** `gson.fromJson` is called in `mapNotNull`/`forEach` without
per-entry exception containment.

**Reproduction:** Add one invalid JSON file under `secondary/` or
`traces/` in an otherwise valid bundle. The importer can throw before
importing valid entries.

**Proposed fix:** Catch per-entry parse exceptions, count skipped entries,
and surface a clear warning while importing the valid report body.

**Status:** Fixed — 99f19086 (ReportBundle: contain per-entry parse failures on import)

### Bug 7 - Severity: High - Category: Prompt translation path safety
**Location:** `ai/src/main/java/com/ai/data/PromptTranslationStore.kt:23-37`, `ai/src/main/java/com/ai/data/PromptTranslationStore.kt:52-60`

**Symptom:** Generated prompt translations can read, write, count, or delete
outside the intended `prompt-translations` directory if language/category/name
contains path separators.

**Root cause:** `language`, `category`, and `name` are used directly in
`File(...)` path construction.

**Reproduction:** Call `put(context, "../x", "cat", "name", text)` or pass
a category/name containing `/`. The resulting path is not constrained to the
translation root.

**Proposed fix:** Sanitize every path segment to a safe flat name and verify
canonical descendants under the root before read/write/delete.

**Status:** Fixed — c4f90621 (PromptTranslationStore: sanitize path segments)

### Bug 8 - Severity: Low - Category: Prompt cache path safety
**Location:** `ai/src/main/java/com/ai/data/PromptCache.kt:55-68`, `ai/src/main/java/com/ai/data/PromptCache.kt:85-93`

**Symptom:** Public PromptCache helpers can read or write outside the cache
namespace if a caller supplies an arbitrary key.

**Root cause:** The cache has a safe `keyFor` hash generator, but `get`,
`getRaw`, and `put` accept raw keys and build `"$key.json"` directly.

**Reproduction:** Call `PromptCache.put("../x", "body")` from a future
debug/import path.

**Proposed fix:** Validate that keys match the expected SHA-256 hex pattern,
or make raw-key helpers private and expose only `keyFor`-derived APIs.

**Status:** Fixed — 58dfa590 (require a 64-hex key in the raw PromptCache helpers)

### Bug 9 - Severity: Medium - Category: App log path safety
**Location:** `ai/src/main/java/com/ai/data/AppLog.kt:200-211` (`readLogFile`, `deleteLog`)

**Symptom:** App log read/delete helpers can target arbitrary child paths
under or near the log directory when given an unchecked filename.

**Root cause:** The helpers construct `File(dir, filename)` without
validating that `filename` matches the generated log filename pattern.

**Reproduction:** Call `AppLog.readLogFile("../prefs.xml")` or
`deleteLog("../x")` from a future route that passes a user-controlled
filename.

**Proposed fix:** Validate filenames with the same prefix/suffix/date
pattern used by `getLogFiles`, and add canonical-child checks.

**Status:** Fixed — 58dfa590 (validate the applog filename in readLogFile/deleteLog)

### Bug 10 - Severity: Medium - Category: External pricing parsing
**Location:** `ai/src/main/java/com/ai/data/PricingParsers.kt:139-164`, `ai/src/main/java/com/ai/data/PricingParsers.kt:188-194`

**Symptom:** A malformed pricing catalog can abort parsing for a provider
instead of skipping the bad entry.

**Root cause:** Several external JSON fields are read with `.asDouble` or
`.asInt` after only checking `isJsonPrimitive`. String primitives such as
`"free"` or `"unknown"` still throw.

**Reproduction:** Feed a pricing blob with `"input":"free"` or
`"context":"unknown"` to the parser.

**Proposed fix:** Add safe numeric helpers and skip/record malformed rows
without throwing out the entire catalog refresh.

**Status:** Fixed — 58dfa590 (skip non-numeric pricing rows via numOrNull/intOrNull)

### Bug 11 - Severity: Medium - Category: Local LLM path safety
**Location:** `ai/src/main/java/com/ai/data/local/LocalLlm.kt:65-68` (`llmFile`)

**Symptom:** A crafted persisted Local-provider model name can resolve a
path outside `local_llms`.

**Root cause:** `llmFile` builds `File(localLlmsDir(context),
"$modelName.task")` without validating that `modelName` is one of
`installedTaskFiles()` or a safe flat filename stem.

**Reproduction:** Import or restore data that references Local model name
`../native/libllm_inference_engine_jni`. A generate attempt asks `llmFile`
for that path.

**Proposed fix:** Reject model names containing separators or dots before
building the file, and canonical-check the result under `local_llms`.

**Status:** Fixed — fea9c1c7 (LocalLlm: reject path-escaping model names in llmFile)

### Bug 12 - Severity: Medium - Category: Local embedder path safety
**Location:** `ai/src/main/java/com/ai/data/local/LocalEmbedder.kt:194-199` (`modelFile`)

**Symptom:** A crafted local embedder model name can resolve outside
`local_models`.

**Root cause:** `modelFile` builds a child path from raw `modelName` and
does not enforce a safe flat filename stem.

**Reproduction:** Restore a knowledge-base config with embedder model
`../x`; a local embedding run resolves that path.

**Proposed fix:** Validate model names against installed model stems or a
strict filename regex, then canonical-check under `local_models`.

**Status:** Fixed — 568775ad (LocalEmbedder: reject path-escaping model names in modelFile)

### Bug 13 - Severity: Medium - Category: TransRank parser tolerance
**Location:** `ai/src/main/java/com/ai/data/TranslatorRankModel.kt:137-142`

**Symptom:** JSON-form TransRank replies with non-string `reason` or
structured `score` values are treated as parser failures even when a usable
score exists.

**Root cause:** The JSON path assumes `score` and `reason` can be read via
`.asString`; objects/arrays throw and the code falls through to the
plaintext parser, which may parse the wrong number.

**Reproduction:** Feed `{"score":{"value":82},"reason":["ok"]}` or
`{"score":82,"reason":["clear"]}`.

**Proposed fix:** Use explicit primitive checks and return a clear
missing-field result instead of falling through to whole-body number search.

**Status:** Fixed — 58dfa590 (type-check the JSON score instead of falling through)

### Bug 14 - Severity: Low - Category: Secondary storage cache coherence
**Location:** `ai/src/main/java/com/ai/data/SecondaryResult.kt:231-237` (`get`)

**Symptom:** Direct `get` reads bypass the per-report parsed-row cache, so
heavy screens that call `get` repeatedly can reparse the same row JSON.

**Root cause:** `listForReport` maintains a fingerprint cache, but `get`
always reads and parses the file from disk.

**Reproduction:** Open screens that repeatedly call `SecondaryResultStorage.get`
for many rows, such as detail/trace-linked views during live updates.

**Proposed fix:** Share the cache lookup for `get` when the file is present
in `listCache`, or add a small per-id read cache with the same invalidation
rules.

**Status:** Fixed — 51bad4ff (SecondaryResult: get() reuses the parsed-row cache)

### Bug 15 - Severity: Low - Category: Local embedder partial sweep
**Location:** `ai/src/main/java/com/ai/data/local/LocalEmbedder.kt:174-191` (`availableModels`)

**Symptom:** Merely listing local embedder models performs filesystem
mutation by deleting stale `.part` files.

**Root cause:** `availableModels` calls `sweepStalePartials` on every read.
That makes a query method unexpectedly destructive and can run on UI paths.

**Reproduction:** Open any picker/screen that calls `availableModels` while
a slow import/download leaves a `.part` file older than the cutoff.

**Proposed fix:** Move sweeping into explicit maintenance/download startup
paths, or expose a separate background cleanup method.

**Status:** Fixed — 3b639676 (LocalEmbedder: don't delete .part files from a query)

### Bug 16 - Severity: Medium - Category: App log toast threading
**Location:** `ai/src/main/java/com/ai/data/AppLog.kt:164-174` (`maybeShowToast`)

**Symptom:** Error bursts can suppress important later user-visible
warnings across unrelated subsystems for ten seconds.

**Root cause:** Toast coalescing is global (`lastToastMs`) and ignores
level/tag/message identity.

**Reproduction:** Trigger a benign warning, then within ten seconds trigger
a critical user-action error. The second toast is suppressed.

**Proposed fix:** Coalesce per `(level, tag)` or per normalized message, and
allow ERROR to bypass a prior WARN suppression window.

**Status:** Fixed — 58dfa590 (coalesce toasts per (level, tag) so a WARN can't muffle an ERROR)

