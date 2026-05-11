# Knowledge (RAG)

The Knowledge subsystem turns user-provided documents into embedded
chunks that get retrieved and prepended to chat messages and report
prompts. Code lives under `com.ai.data.Knowledge*` and
`com.ai.ui.knowledge.*`.

## Concepts

```
KnowledgeBase ──┬── KnowledgeSource (file or URL)  ──── manifest.json
                ├── KnowledgeSource (…)                  │
                └── KnowledgeSource (…)                  │
                                                         │
KnowledgeChunk[]  ←  one JSON file per source  ──────────┘
                     in chunks/<sourceId>.json
```

A **Knowledge base** is a named collection of indexed documents
sharing one embedder model. The embedder is fixed at creation time —
chunks aren't compatible with a different model — so changing the
embedder requires a new KB.

A **Knowledge source** is one document inside a KB: a file the user
picked via SAF, or a URL the user pasted. Each source extracts to
plain text and chunks into a sibling `chunks/<sourceId>.json`.

A **Knowledge chunk** is `(id, sourceId, ordinal, text, embedding)`.
Embeddings are a primitive `FloatArray` — for a typical 1024-dim
vector that's a ~6× heap reduction vs a boxed `List<Double>` (4
bytes per dim vs 24 including the Double object header and array
reference), paid back across the entire KB during retrieval. Most
embedders (sentence-transformers, OpenAI, Cohere) emit float32
anyway, so the precision shed is negligible. The cosine math
itself is still done in double internally to avoid float-accumulator
drift on long vectors. JSON storage on disk is unchanged: Gson
serialises `FloatArray` and `List<Double>` to the same array of
JSON numbers, so existing chunk files written before the type
change keep working. `KnowledgeChunk` overrides `equals` /
`hashCode` so the FloatArray field compares by content rather
than identity.

## Source types — ten extractors

`KnowledgeExtractors.extract(context, type, origin)` dispatches to
one of:

| Type | Extractor | Notes |
|---|---|---|
| `TEXT` | `readUriText` | UTF-8 read, normalised newlines, streamed via `BufferedReader` instead of `readText` to avoid loading huge text files into a single String |
| `MARKDOWN` | `readUriText` | Same as TEXT — chunker preserves paragraph breaks |
| `PDF` | `readUriPdf` | PDFBox-Android `PDFTextStripper.getText`. **OCR fallback** when the result is blank — `PdfRenderer` rasterises each page at 200 DPI (capped 2400 px long side) and runs ML Kit Latin text recognition |
| `DOCX` | `readUriDocx` | JDK `ZipInputStream` + `XmlPullParser` over `word/document.xml`; `<w:p>` → paragraph, `<w:t>` → text, `<w:tab>` → tab |
| `ODT` | `readUriOdt` | Same idea over `content.xml` with `<text:p>` / `<text:h>` paragraphs and direct text — streamed instead of slurped |
| `XLSX` | `readUriXlsx` | Single-pass: walk the zip; `xl/sharedStrings.xml` populates the shared-string table, each `xl/worksheets/sheet*.xml` is **spooled** to a temp file as encountered (a multi-sheet workbook never holds two sheets in memory at once). Cells with `t="s"` resolve to a shared-string index, `t="inlineStr"` reads the literal text under `<is><t>`, anything else takes `<v>` as-is. Output is `[sheet N]` headers + tab-separated rows in zip-encounter order. |
| `ODS` | `readUriOds` | Streams `content.xml` via the spreadsheet zip without an intermediate `readText` slurp; same `[sheet N]` layout as XLSX |
| `CSV` | `readUriCsv` | Streaming RFC-4180-ish parser: `BufferedReader.mark` + 1 KB sample sniffs `,` vs `;`, then `parseCsvStream` walks the rest via a `PushbackReader` (1-char lookahead for `""` escape and CRLF) and yields rows through a callback. Quoted-field + `""` handling unchanged. CR / LF / CRLF all terminate a row — bare CR catches legacy Mac CSV exports that previously collapsed into one row. Emits the header row at the top of every 10-row block so retrieval chunks always carry column context |
| `IMAGE` | `readUriImage` | BitmapFactory bounds-decode + downsample to 2400 px max long side, then ML Kit Latin OCR. The recogniser's async `Task<Text>` is bridged to a synchronous String via `com.google.android.gms.tasks.Tasks.await` |
| `URL` | `fetchUrlAsText` | Jsoup fetch + visible-text extraction (drops `script`, `style`, `noscript`, `nav`, `footer`, `aside`, `header`) |

The chunker, embedder, and retriever are type-agnostic. Once an
extractor returns a string, every downstream stage works unchanged.

JSON loaders across the codebase (provider catalog, prompts, etc.)
also stream via `JsonReader` rather than `readText` to keep peak
heap down.

## Chunking

`KnowledgeChunker.chunk(text, maxCharsPerChunk = 2048, overlapChars = 200)`
is a paragraph-greedy chunker with overlap:

1. Split source on blank lines (paragraphs).
2. Greedily merge until the running window approaches
   `maxCharsPerChunk` (~4 chars ≈ 1 token; 2048 chars ≈ 512 tokens).
3. Carry the last `overlapChars` of each emitted chunk into the start
   of the next one so context survives a paragraph cut.
4. Single oversize paragraphs get hard-cut by char count with the same
   overlap, so a giant table-collapsed-onto-one-line still chunks.

## Embedding

Two paths, picked at KB creation:

- **Local**: `embedderProviderId == "Local"`. Calls
  `LocalEmbedder.embed(modelName, text)` which holds a per-model
  `TextEmbedder` cache. No network. Default model is
  `universal_sentence_encoder_lite` (~25 MB), downloaded lazily on
  first use of the Local Semantic Search screen or the first time a
  Local KB embeds.
- **Remote**: any active provider with at least one embedding-capable
  model. Calls `AnalysisRepository.embed(provider, model, text)` —
  the same Retrofit + tracing path the chat dispatch uses.

Either way, every embedded chunk is keyed in `EmbeddingsStore` (cache
under `<filesDir>/embeddings/<sha256>.json`) so re-indexing the same
content with the same model is a hashmap hit, not a re-embed.
`Knowledge.saveSource` refuses chunks with empty embeddings or a
dim mismatch and logs a warning, so the cosine path can never
silently rank a malformed chunk as 0.

## Retrieval

`KnowledgeService.retrieve(context, attached: List<KnowledgeBase>,
query: String, topK: Int = 8, maxContextChars: Int = 8000)`:

1. Embed the query once via the embedder shared across all attached
   KBs (they must match — see below) and convert the result from
   `List<Double>` to `FloatArray` for the hot loop.
2. Stream every `KnowledgeChunk` in every embedder-matched KB
   through `KnowledgeStore.forEachChunk`, which parses one source
   file at a time and lets the array go out of scope between files.
   Score each chunk's `FloatArray` embedding via
   `EmbeddingsStore.cosine(FloatArray, FloatArray)` (a primitive
   variant alongside the original `List<Double>` overload).
3. Maintain a bounded min-heap of size `topK * 2`
   (`java.util.PriorityQueue<Scored>`). Each chunk either enters
   the heap (room available, or score beats the current minimum)
   or is dropped immediately. Peak heap is the heap itself plus
   one chunk mid-iteration, regardless of total KB size.
4. Sort the survivors descending, then walk taking chunks until
   the cumulative `text.length` would exceed `maxContextChars`.
   Stops at `topK` matches or the budget, whichever comes first,
   and skips zero-score chunks.
5. Format the result via `formatContextBlock(...)` into a
   `<context>…</context>` block including each chunk's KB and
   source name.

The dispatch layer prepends the context block:
- For **reports**: `AnalysisRepository.analyzeWithAgent` reads
  `report.knowledgeBaseIds`, retrieves once per agent call, and
  prepends to that agent's prompt.
- For **chats**: each user turn re-retrieves against the current
  message text via `ChatViewModel.messagesWithRag` (a `suspend`
  function called from inside `flow { … }.flowOn(Dispatchers.IO)`)
  and merges the block into the system message — or prepends a
  fresh system message if none was set. Retrieval failures log a
  warning instead of silently falling back to the raw prompt.

The "embedder must match" rule is enforced by the retrieval path:
KBs whose embedder differs from the first attached KB's are
skipped entirely (with a warning log) so a mismatched-dimension
cosine never silently mis-ranks. Mismatched-dimension chunks
encountered mid-stream surface as a warning rather than scoring 0.

## On-disk layout

```
<filesDir>/knowledge/<kbId>/
  manifest.json          KnowledgeBase + sources
  chunks/
    <sourceId>.json      JSON array of KnowledgeChunk
  files/
    <localCopy>          locally-persisted source file (atomic)
```

One JSON file per source keeps add / remove / re-index cheap (no
full-KB rewrite for a single-source change), while loading a whole
KB for retrieval still scans only one directory. `KnowledgeStore`
takes a single lock around the manifest-update + chunk-write pair
so a crash mid-write can't leave the manifest pointing at half a
chunk file. Source files are persisted via tmp + fsync + atomic
rename.

The store enforces a **canonical-containment guard** around every
path join that uses `kbId` (and `saveSource` / `deleteSource`
validate `source.id` with the same `isSafeFlatId` rule the
storage writers use) — a corrupt id with a `..` segment, a `/`
inside, or a symlinked target can't escape
`<filesDir>/knowledge/`.

## UI

- `ui/knowledge/KnowledgeListScreen.kt` — list of KBs + create button +
  share-target ingest banner ("N shared items ready to import" when
  the share-target chooser routed files into the queue).
- `ui/knowledge/NewKnowledgeBaseScreen.kt` — name + embedder picker
  (lists local `.tflite` files plus every (provider, model) tagged
  `EMBEDDING`).
- `ui/knowledge/KnowledgeDetailScreen.kt` — per-KB sources + add
  file / add URL / re-index / delete + status line. Auto-consumes the
  share-target queue when entered with a non-empty `pendingUris`.
  The auto-ingest `LaunchedEffect` re-keys whenever the KB loads so
  late-arriving share intents still drain.

## Picker MIME filter

The detail screen's SAF picker accepts:

```
text/*, text/csv, text/tab-separated-values,
application/pdf,
application/vnd.openxmlformats-officedocument.wordprocessingml.document,
application/vnd.oasis.opendocument.text,
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,
application/vnd.oasis.opendocument.spreadsheet,
image/jpeg, image/png,
*/*
```

The same MIME types appear in the share-target intent filters in
`AndroidManifest.xml` (see [share-target.md](share-target.md)).

## Cleanup

- `KnowledgeStore.deleteSource(kbId, sourceId)` removes the chunk
  file and the manifest entry.
- `KnowledgeStore.deleteKnowledgeBase(kbId)` `deleteRecursively`s the
  KB directory; also takes the store lock during `clearAll` so a
  full-reset and an in-flight ingest can't race.
- A full reset (Housekeeping → Reset → Clear all runtime data) wipes
  `<filesDir>/knowledge/`, the `embeddings/` cache, and the local-
  semantic-search cache.
- Embedder model files (`.tflite`) and Local LLM bundles (`.task`)
  persist across full resets — they have their own per-row Remove
  on **AI Setup → Local Models**. They're also excluded from the
  backup zip and preserved through the restore wipe; see
  [backup-restore.md](backup-restore.md).
