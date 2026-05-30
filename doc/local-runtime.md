# On-device runtime — Local LLM + LiteRT embedder

Two MediaPipe Tasks runtimes that run entirely on-device, no API key
and no HTTP: an **LLM** (`LocalLlm`, `.task` bundles) and a **text
embedder** (`LocalEmbedder`, `.tflite` models). Both surface a single
synthetic provider into the normal app and are gated behind the
**Experimental features** master toggle (see
[experimental.md](experimental.md)).

## The synthetic `AppService.LOCAL` provider

`LOCAL` is a sentinel `AppService` (id `"Local"`, baseUrl `local://`)
defined in
[`data/AppService.kt:226`](../ai/src/main/java/com/ai/data/AppService.kt).
It is **not registered in `ProviderRegistry`** — it never shows up in
provider lists / settings — and is reachable only via
`AppService.findById("Local")`
([`AppService.kt:233`](../ai/src/main/java/com/ai/data/AppService.kt)),
so a persisted `ChatSession` whose provider was Local can be reloaded
after restart. Pickers add it explicitly when local models exist, so
it surfaces as a normal "Local" provider in chat / report flows.

**Dispatch fork** — in
[`data/AnalysisRepository.kt:222`](../ai/src/main/java/com/ai/data/AnalysisRepository.kt),
when `agent.provider.id == AppService.LOCAL.id` the call bypasses
Retrofit / API key / retry entirely and routes to
`LocalLlm.generate`. Chat does the same via
[`viewmodel/ChatViewModel.kt:170`](../ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt).
The embedder fork lives in
[`viewmodel/SecondaryRunManager.kt:144`](../ai/src/main/java/com/ai/viewmodel/SecondaryRunManager.kt).
RAG attached to a Local agent still runs: the KB context block is
retrieved and prepended to the prompt before `LocalLlm.generate`.

## The two runtimes

| | LLM | Embedder |
|---|---|---|
| Object | [`data/local/LocalLlm.kt`](../ai/src/main/java/com/ai/data/local/LocalLlm.kt) | [`data/local/LocalEmbedder.kt`](../ai/src/main/java/com/ai/data/local/LocalEmbedder.kt) |
| MediaPipe type | `LlmInference` (Tasks GenAI) | `TextEmbedder` (Tasks Text) |
| File extension | `.task` | `.tflite` |
| Install dir | `<filesDir>/local_llms/` | `<filesDir>/local_models/` |
| Acquisition | SAF "Add LLM from file" (Kaggle / HuggingFace hand-off links) | In-app download of two MediaPipe models, or SAF import |
| Output | response string | `List<List<Double>>` (L2-normalized) |

Both objects cache native handles in a `ConcurrentHashMap` keyed by
model name (built atomically via `computeIfAbsent` so a losing thread
can't leak a multi-hundred-MB native instance), serialise each call
under `synchronized(handle)` because the native handles are not
thread-safe, and expose `release(name)` / `releaseAll()` / `clearAll()`.

Each `generate` / `embed` / `download` call writes a **synthetic
`ApiTrace`** (hostname `"local"`, url `local://generate/<model>` or
`local://embed/<model>`) so on-device traffic appears on the Trace
screen alongside HTTP. Tracing respects the same global flag
(`ApiTracer.isTracingEnabled`).

### LLM native runtime (`LlmRuntime`)

The MediaPipe LLM inference `.so`
(`libllm_inference_engine_jni.so`, ~26 MB) is **not shipped in the
APK** — it would inflate the install for everyone, including users who
never run a local model. Instead
[`data/local/LlmRuntime.kt`](../ai/src/main/java/com/ai/data/local/LlmRuntime.kt)
streams the MediaPipe Tasks GenAI AAR (version `0.10.35`) from Google
Maven, extracts the `arm64-v8a` entry into `<filesDir>/native/`, and
`System.load`s it on demand. `ensureLoaded` is idempotent;
`System.load` can't be undone, so `delete` removes the on-disk file
but the in-process copy stays mapped until the next app start.

`LocalLlm.availableLlms` returns `.task` files only when the runtime
is installed, so a picker entry is never handed out that would explode
on first `generate()`. `installedTaskFiles` lists everything on disk
regardless, for the management screen.

### Embedder downloads

`LocalEmbedder.downloadable` lists the two text embedders MediaPipe
publishes with the metadata the runtime needs — **Universal Sentence
Encoder Lite** (default, ~25 MB, multilingual) and **Average Word
Embedder** (~5 MB, English, near-instant). Anything more exotic must
arrive via SAF import with metadata stamped by MediaPipe Model Maker.
Downloads stream to a `.part` temp then atomic-rename into place.

## Setup UI

The setup screens live in
[`ui/settings/LocalRuntimeScreens.kt`](../ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt):

- **`LocalLlmsScreen`** (AI Setup → Local LLMs) — download / delete the
  LLM native runtime, hand-off download links, and SAF import of
  `.task` / `.zip` / `.tar.gz` into `local_llms/`, with per-row Remove.
- **`LocalLiteRtModelsScreen`** (AI Setup → Local LiteRT models) —
  download the published embedders or SAF-import a `.tflite` into
  `local_models/`, with per-row Remove.

Both entry points are hidden when **Experimental features** is off; the
gate sites are enumerated in [experimental.md](experimental.md).

## Local semantic search tie-in

[`ui/search/LocalSemanticSearchScreen.kt`](../ai/src/main/java/com/ai/ui/search/LocalSemanticSearchScreen.kt)
runs meaning search over saved reports with no cloud round-trip. It
picks an installed `.tflite`, embeds the query and each report's
title+prompt+first-response via `LocalEmbedder.embed`, caches vectors
in `EmbeddingsStore` (provider key `"LOCAL"`), and ranks by
`EmbeddingsStore.cosine`. This is one of three RAG consumers of the
local embedder — see [knowledge.md](knowledge.md) for KB embedding /
retrieval, which uses the same `LocalEmbedder` when a KB's embedder
model is a local `.tflite`.

## Backup

`local_llms/`, `local_models/`, and `native/` are in
`FILES_DIR_BACKUP_EXCLUDES`
([`data/BackupManager.kt:125`](../ai/src/main/java/com/ai/data/BackupManager.kt)) —
multi-GB model files are excluded from the backup zip **and** preserved
through `clearFilesDirForRestore`, so a restore never wipes them. Full
design in [backup-restore.md](backup-restore.md).

## Related docs

- [experimental.md](experimental.md) — the master gate that hides the
  whole subsystem.
- [knowledge.md](knowledge.md) — RAG; KB embedding can use the local
  embedder.
- [backup-restore.md](backup-restore.md) — the exclude / preserve list
  for the model dirs.
- [datastructures.md](datastructures.md) — `AppService`, `ApiTrace`.
- [persistent.md](persistent.md) — the `<filesDir>` layout
  (`local_llms/`, `local_models/`, `native/`).
