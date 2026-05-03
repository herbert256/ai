# On-Device Runtime

Two singletons wrap MediaPipe Tasks for fully-offline AI:

- **`LocalLlm`** — chat / report generation via MediaPipe Tasks GenAI
  (LiteRT-based `.task` bundles).
- **`LocalEmbedder`** — text embedding via MediaPipe Tasks
  TextEmbedder (`.tflite` models).

Both surface to the rest of the app through the synthetic
`AppService.LOCAL` provider, so they show up in every model picker
as a normal "Local" provider — no bespoke buttons, no separate
flows. The dispatch layer routes by `provider.id == "LOCAL"`.

Both write **synthetic API trace** entries (hostname `local`, url
like `local://generate/<modelFile>` or `local://embed/<modelFile>`)
so on-device traffic shows up on the Trace screen alongside HTTP
calls. Tracing respects the same global flag every other call site
uses.

## LocalLlm

`com.ai.data.LocalLlm` (file: `LocalLlm.kt`).

### Storage

Models live as user-supplied `.task` bundles under:

```
<filesDir>/local_llms/<name>.task
```

`availableLlms(context)` lists the file stems (no extension). The
chat / report flow uses these stems as model ids.

### Inference

`generate(context, modelName, prompt, maxTokens)` returns the full
response string. The MediaPipe Kotlin binding in this version
doesn't expose a partial-token callback, so chat sessions show the
full reply when the call returns — not as a stream.

Each `LlmInference` instance is cached in a `ConcurrentHashMap`
keyed by `.task` filename so successive calls reuse the same
runtime without re-initialisation.

### Recommended models — the "gating dance"

Most worthwhile `.task` LLMs (Gemma, Phi, Llama …) require the user
to accept the model card terms in a browser before download. The
**Housekeeping → Local LLMs** card therefore leads with hand-off
links rather than direct downloads:

```kotlin
val recommendedLinks: List<RecommendedLlm> = listOf(
    RecommendedLlm(name = "Gemma 3 1B (Kaggle)", url = "…", sizeHint = "~530 MB", description = "…"),
    RecommendedLlm(name = "Gemma 3 (HuggingFace community)", url = "…", sizeHint = "~530 MB - 2.5 GB", description = "…"),
    RecommendedLlm(name = "Gemma 2 2B (Kaggle)", url = "…", sizeHint = "~1.5 GB", description = "…"),
    …
)
```

The user opens one of these in a browser, accepts the terms,
downloads the file, and then comes back to import via SAF.

### Importing

The SAF picker on the same Housekeeping card accepts:

- Bare `.task` files
- `.zip`, `.tar.gz`, `.tgz`, `.tar` archives — Apache Commons
  Compress unpacks them and copies any `.task` file found inside
  into `local_llms/`.

Per-row **Remove** deletes the file and evicts the cached
`LlmInference` so the next list refresh shows it gone.

### Provider listing integration

When `LocalLlm.availableLlms()` is non-empty:
- The synthetic `LOCAL` provider becomes selectable in every model
  picker (Reports, Chats, Dual Chat, Agents, Swarms).
- The AI Chat hub shows a one-tap "Chat with a local LLM" card.
- The model picker on the Reports flow's `+Provider` shows Local
  alongside the cloud providers.

## LocalEmbedder

`com.ai.data.LocalEmbedder` (file: `LocalEmbedder.kt`).

### Storage

`.tflite` models live under:

```
<filesDir>/local_models/<name>.tflite
```

Each model file must have proper MediaPipe Tasks metadata baked in
or the runtime will refuse to load it.

### Inference

`embed(context, modelName, text)` returns a `List<Double>` vector.
Each `TextEmbedder` instance is cached in a `ConcurrentHashMap`
keyed by `.tflite` filename. Output dimension depends on the model
(USE Lite is 100; Average Word Embedder is 100 too).

### Curated downloads

`LocalEmbedder.downloadable` is a hardcoded list of two
`DownloadableModel` entries — the only MediaPipe-published text
embedders with the metadata the runtime accepts:

| Name | Display name | Size | Notes |
|---|---|---|---|
| `universal_sentence_encoder_lite` | Universal Sentence Encoder Lite | ~25 MB | Multilingual general-purpose. Default. |
| `average_word_embedder` | Average Word Embedder | ~5 MB | Tiny + fast English. Lower quality. |

`download(context, spec, onProgress)` streams the model into
`local_models/` and writes a synthetic ApiTrace entry so the
download surfaces on the Trace screen. The default model is
downloaded **lazily** the first time the user opens the Local
Semantic Search screen or creates a Local-embedder Knowledge base.

### Importing

The SAF picker on **Housekeeping → Local Models** accepts any
`.tflite` file with stamped MediaPipe Tasks metadata. The user can
stamp metadata via MediaPipe Model Maker for arbitrary HuggingFace
sentence-transformer models.

### Use sites

- **Local Semantic Search** screen (`ui/search/LocalSemanticSearchScreen.kt`)
  — embed report prompts + responses, embed the query, rank by cosine.
- **Knowledge bases** with `embedderProviderId == "LOCAL"` — see
  [knowledge.md](knowledge.md).

## Adding a new download

`LocalEmbedder.downloadable` is just a list literal — append a new
`DownloadableModel(name, displayName, url, sizeMbHint, description)`
and the curated buttons on Housekeeping pick it up.

`LocalLlm.recommendedLinks` is the equivalent for `.task` LLMs but
ships **hand-off links only** (no in-app downloads); users are
expected to navigate to the linked URL in a browser, accept the
terms, and import the result.

## Tracing

Both runtimes emit synthetic `ApiTrace` rows so on-device work
shows up next to HTTP calls. The trace bodies look like:

```json
{
  "timestamp": 1730000000000,
  "hostname": "local",
  "request": { "url": "local://generate/gemma-3-1b", "method": "POST", "headers": {}, "body": "<prompt text>" },
  "response": { "statusCode": 200, "headers": {}, "body": "<generated text>" }
}
```

Tracing respects the same global on/off flag every cloud call site
uses; debug builds keep it on by default.
