# On-device runtime — Local LLM + LiteRT embedder

Two MediaPipe Tasks runtimes that run entirely on-device, with no API
key and no HTTP: an **LLM** (`LocalLlm`, `.task` bundles) and a **text
embedder** (`LocalEmbedder`, `.tflite` models). Both surface a single
synthetic provider into the normal app and are gated behind the
**Experimental features** master toggle (see
[experimental.md](experimental.md)).

All four files live under
[`data/local/`](../ai/src/main/java/com/ai/data/local/): `LocalLlm.kt`,
`LocalEmbedder.kt`, `LlmRuntime.kt` (the native `.so` loader), and
`LocalRuntime.kt` (a read-only dashboard snapshot).

## The synthetic `AppService.LOCAL` provider

`LOCAL` is a sentinel `AppService` defined in the `AppService`
companion object
([`data/AppService.kt:226`](../ai/src/main/java/com/ai/data/AppService.kt)):

```kotlin
val LOCAL = AppService(id = "Local", baseUrl = "local://", adminUrl = "", defaultModel = "")
```

It is **not registered in `ProviderRegistry`** — it never shows up in
the registry-driven provider lists — and is reachable only because
`AppService.findById` special-cases its id **before** delegating to the
registry
([`AppService.kt:233`](../ai/src/main/java/com/ai/data/AppService.kt)):

```kotlin
fun findById(id: String): AppService? = if (id == LOCAL.id) LOCAL else ProviderRegistry.findById(id)
```

`AppServiceAdapter` (the Gson serializer at `AppService.kt:242`)
serializes an `AppService` as its id string and deserializes through
`findById`, so a persisted `ChatSession` whose provider was Local
round-trips after a restart even though the registry has never heard of
it. Pickers add the Local provider (and its installed `.task` models)
explicitly when models exist, so it surfaces as a normal "Local"
provider in chat / report flows — gated by the Experimental toggle.

> **Casing trap.** The chat/report LLM sentinel id is `"Local"`
> (capital L only). The *KB embedder* / local-rerank / semantic-search
> provider key is the literal all-caps `"LOCAL"`. They are different
> strings used in different layers — see
> [knowledge.md](knowledge.md). Don't conflate them.

### Dispatch forks (caller-side, not `ApiDispatch`)

There is **no `LOCAL` branch inside `ApiDispatch`** and no `LOCAL`
value on `ApiFormat`. `AppService.LOCAL.apiFormat` is the default
`OPENAI_COMPATIBLE` and is never used for a network call. Instead each
caller checks `provider.id == AppService.LOCAL.id` and routes to the
on-device object, bypassing Retrofit / API key / retry / throttle
entirely:

| Flow | Site | Routes to |
|---|---|---|
| Report / agent generation | `analyzeWithAgent`, [`AnalysisRepository.kt:260`](../ai/src/main/java/com/ai/data/AnalysisRepository.kt) | `LocalLlm.generate(context, agent.model, finalPrompt)` |
| Chat | `sendLocalLlmStream`, [`ChatViewModel.kt:143`](../ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt) | `LocalLlm.generate` (response emitted as a single chunk) |
| Rerank | `runLocalRerank`, [`SecondaryRunManager.kt:145`](../ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt) | `LocalEmbedder.embed` + cosine-to-prompt |

On the report fork, success returns an `AnalysisResponse` with
`httpStatusCode = 200`; a `null` result becomes a 500 whose message
points the user at *Housekeeping → Local LLMs*. The local-rerank
placeholder/cost rows are saved with `providerId = "LOCAL"`.

**RAG still works for a Local agent.** The dispatch fork sits *after*
the KB-context build: `analyzeWithAgent` retrieves hits via
`KnowledgeService.retrieve` (wrapped in `runCatching` so an embedder
hiccup falls back to the bare prompt), then `withRagPrefix` prepends
the `<context>…</context>` block to the prompt before
`LocalLlm.generate`.

## The two runtimes

| | LLM | Embedder |
|---|---|---|
| Object | [`data/local/LocalLlm.kt`](../ai/src/main/java/com/ai/data/local/LocalLlm.kt) | [`data/local/LocalEmbedder.kt`](../ai/src/main/java/com/ai/data/local/LocalEmbedder.kt) |
| MediaPipe type | `LlmInference` (Tasks GenAI) | `TextEmbedder` (Tasks Text) |
| File extension | `.task` | `.tflite` |
| Install dir | `<filesDir>/local_llms/` | `<filesDir>/local_models/` |
| Dir const | `LOCAL_LLMS_DIR = "local_llms"` | `LOCAL_MODELS_DIR = "local_models"` |
| Acquisition | SAF "Add LLM from file" (Kaggle / HuggingFace hand-off links) | In-app download of two MediaPipe models, or SAF import |
| Output | response `String?` (null on failure) | `List<List<Double>>?` (L2-normalized; null on failure, `emptyList` on empty input) |

Both objects are Kotlin `object` singletons that cache native handles
in a `ConcurrentHashMap<String, …>` keyed by model name, built
atomically via `computeIfAbsent` (not `getOrPut`, whose lambda can run
on multiple threads and leak the losing multi-hundred-MB native
instance). Each call serialises under `synchronized(handle)` because
the native handles are **not** thread-safe — two parallel report
agents pointing at the same model would otherwise corrupt the runtime
state. Both expose `release(name)` / `releaseAll()` and a
`clearAll(context): Int` that closes every engine and deletes every
model file, returning the count removed (used by the housekeeping
"clear all configuration" flow).

The LLM engine is built with `setMaxTokens(2048)`
([`LocalLlm.kt:131`](../ai/src/main/java/com/ai/data/local/LocalLlm.kt)) —
a conservative cap that keeps memory in check on non-flagship phones.
The embedder is built with `setL2Normalize(true)`, and
`LocalEmbedder.embed` reads `embedding.floatEmbedding()` per input and
maps each `Float` to `Double`.

### Synthetic traces

Every `LocalLlm.generate`, `LocalEmbedder.embed`,
`LocalEmbedder.download`, and `LlmRuntime.download` call writes a
**synthetic `ApiTrace`** — but only when `ApiTracer.isTracingEnabled`.
Generate/embed traces use hostname `"local"` and url
`local://generate/<model>` / `local://embed/<model>`; the two download
traces use the real download host. Bodies carry truncated previews
(prompt `take(500)`, each embed input `take(120)`), durations, output
dims, and any error, with `statusCode` 200 on success / 500 on
failure. These rows appear on the Trace screen alongside HTTP traffic
and roll into backups.

### LLM native runtime (`LlmRuntime`)

The MediaPipe LLM inference `.so`
(`libllm_inference_engine_jni.so`, ~26 MB for arm64-v8a) is
**deliberately not shipped in the APK** — it would inflate the install
for everyone, including users who never run a local model.
[`data/local/LlmRuntime.kt`](../ai/src/main/java/com/ai/data/local/LlmRuntime.kt)
downloads it on demand:

- **Source.** The MediaPipe Tasks GenAI AAR, `AAR_VERSION = "0.10.35"`,
  from Google Maven (`dl.google.com/dl/android/maven2/…/tasks-genai-0.10.35.aar`).
  `download` streams the *whole* AAR (a zip) — `DOWNLOAD_SIZE_MB_HINT = 40`
  drives the button label — plucks the entry
  `jni/arm64-v8a/libllm_inference_engine_jni.so` into `<filesDir>/native/`
  (`NATIVE_DIR = "native"`), and atomic-renames the `.part` temp into
  place (falling back to a non-atomic move when `ATOMIC_MOVE` isn't
  supported).
- **Load.** `ensureLoaded` is idempotent (a volatile `loaded` flag) and
  calls `System.load` on the on-disk file. It is invoked from
  `LocalLlm.getEngine` **before** any MediaPipe type is touched,
  because `LlmInference.LlmInferenceOptions`'s static init calls
  `System.loadLibrary("llm_inference_engine_jni")`, which only succeeds
  once `LlmRuntime` has already mapped the `.so` via `System.load`. If
  the runtime isn't installed, `getEngine` throws
  `IllegalStateException("LLM runtime not installed — visit Setup → Local LLMs…")`.
- **Delete.** `System.load` can't be undone, so `delete` removes the
  on-disk file but the in-process copy stays mapped until the next app
  start — callers remind the user to restart.
- **Version coupling.** `AAR_VERSION` must match the `tasks-genai`
  Gradle dependency; a mismatch surfaces as a "JNI method not found"
  error at first `generate()`.

`LocalLlm.availableLlms` returns `.task` files **only when the runtime
is installed**, so a picker never hands out a model that would explode
on first `generate()`. `installedTaskFiles` lists every `.task` on
disk regardless, for the management screen.

### Embedder downloads

`LocalEmbedder.downloadable` lists exactly the two text embedders
MediaPipe publishes with the Tasks metadata the runtime requires —
**Universal Sentence Encoder Lite** (`universal_sentence_encoder_lite`,
the `DEFAULT_MODEL_NAME`, ~25 MB, multilingual) and **Average Word
Embedder** (`average_word_embedder`, ~5 MB, English, near-instant),
both from `storage.googleapis.com/mediapipe-models`. Anything more
exotic must arrive via SAF import with metadata stamped by MediaPipe
Model Maker. `download` streams to a `.part` temp then atomic-renames
into place.

## Dashboard snapshot (`LocalRuntime`)

[`data/local/LocalRuntime.kt`](../ai/src/main/java/com/ai/data/local/LocalRuntime.kt)
is a read-only object that backs the Live Dashboard's Local-runtime
card. `snapshot()` pulls `LocalLlm.loadedModelNames()` /
`currentlyGenerating` and `LocalEmbedder.loadedModelNames()` /
`currentlyEmbedding` into a `Snapshot(llmLoaded, llmGenerating,
embedderLoaded, embedding)`; `Snapshot.active` is true when anything is
loaded or running. The `generating` / `embedding` fields are `@Volatile`
strings set for the duration of each call by `LocalLlm.generate` /
`LocalEmbedder.embed`.

## Setup UI

The setup screens live in
[`ui/settings/LocalRuntimeScreens.kt`](../ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt):

- **`LocalLlmsScreen`** (AI Setup → Local LLMs) — download / delete the
  LLM native runtime, hand-off download links, and SAF import of
  `.task` / `.zip` / `.tar.gz` / `.tgz` / `.tar` into `local_llms/`
  (the first `.task` entry inside an archive is extracted
  automatically), with per-row Remove.
- **`LocalLiteRtModelsScreen`** (AI Setup → Local LiteRT models) —
  download the two published embedders or SAF-import a `.tflite` into
  `local_models/`, with per-row Remove.

Both entry points are hidden when **Experimental features** is off (the
gate sits in `SetupScreens.kt`); the full set of gate sites is
enumerated in [experimental.md](experimental.md). Nothing is deleted
when the toggle flips off — installed files stay and already-attached
KBs keep sending context.

## Local semantic search tie-in

[`ui/search/LocalSemanticSearchScreen.kt`](../ai/src/main/java/com/ai/ui/search/LocalSemanticSearchScreen.kt)
runs meaning search over saved reports with no cloud round-trip. It
picks an installed `.tflite`, embeds the query and each report's
title+prompt+first-response via `LocalEmbedder.embed`, caches the
vectors in `EmbeddingsStore` under provider key `"LOCAL"`, and ranks by
`EmbeddingsStore.cosine(List<Double>, List<Double>)` (the cached
vectors are kept as `List<Double>`, so this uses the `List<Double>`
overload — *not* the `FloatArray` hot path that KB retrieval uses).
This is one of the local embedder's RAG consumers; see
[knowledge.md](knowledge.md) for KB embedding / retrieval, which uses
the same `LocalEmbedder` when a KB's `embedderProviderId == "LOCAL"`
(again, all-caps — distinct from `AppService.LOCAL.id`).

## Backup

`local_llms/`, `local_models/`, `native/`, **and** `applog/` are the
four entries in `FILES_DIR_BACKUP_EXCLUDES`
([`data/BackupManager.kt:125`](../ai/src/main/java/com/ai/data/BackupManager.kt)):

```kotlin
internal val FILES_DIR_BACKUP_EXCLUDES = setOf("local_llms", "local_models", "native", "applog")
```

The multi-GB `.task` bundles, the hundreds-of-MB `.tflite` embedders,
and the device-ABI-tied native `.so` are excluded from the backup zip
**and** preserved through `clearFilesDirForRestore`, so a restore never
wipes them. (`applog/`, the in-app file logger output, is the fourth
excluded dir — easy to overlook.) Full design in
[backup-restore.md](backup-restore.md).

## Related docs

- [experimental.md](experimental.md) — the master gate that hides the
  whole subsystem.
- [knowledge.md](knowledge.md) — RAG; KB embedding can use the local
  embedder (`embedderProviderId == "LOCAL"`).
- [backup-restore.md](backup-restore.md) — the exclude / preserve list
  for the model dirs.
- [datastructures.md](datastructures.md) — `AppService`, `ApiTrace`.
- [persistent.md](persistent.md) — the `<filesDir>` layout
  (`local_llms/`, `local_models/`, `native/`).
