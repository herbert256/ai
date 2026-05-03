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
Embeddings are `List<Double>` — half the size benefit of `Float`
isn't worth the precision loss for cosine similarity over the short
text spans we deal with.

## Source types — ten extractors

`KnowledgeExtractors.extract(context, type, origin)` dispatches to
one of:

| Type | Extractor | Notes |
|---|---|---|
| `TEXT` | `readUriText` | UTF-8 read straight, normalised newlines |
| `MARKDOWN` | `readUriText` | Same as TEXT — chunker preserves paragraph breaks |
| `PDF` | `readUriPdf` | PDFBox-Android `PDFTextStripper.getText`. **OCR fallback** when the result is blank — `PdfRenderer` rasterises each page at 200 DPI (capped 2400 px long side) and runs MediaPipe Latin text recognition |
| `DOCX` | `readUriDocx` | JDK `ZipInputStream` + `XmlPullParser` over `word/document.xml`; `<w:p>` → paragraph, `<w:t>` → text, `<w:tab>` → tab |
| `ODT` | `readUriOdt` | Same idea over `content.xml` with `<text:p>` / `<text:h>` paragraphs and direct text |
| `XLSX` | `readUriXlsx` | Two-pass: shared strings table first, then walk each `xl/worksheets/sheet*.xml` resolving `t="s"` cells. Output is `[sheet N]` headers + tab-separated rows |
| `ODS` | `readUriOds` | Walks `content.xml` `<table:table-cell>` with `<text:p>` text, joining multiple `<text:p>` per cell with a space; same `[sheet N]` layout as XLSX |
| `CSV` | `readUriCsv` | RFC-4180-ish parser with quoted-field + `""` escape handling. Auto-detects `,` vs `;` delimiter from the first KB. Emits the header row at the top of every 10-row block so retrieval chunks always carry column context |
| `IMAGE` | `readUriImage` | BitmapFactory bounds-decode + downsample to 2400 px max long side, then MediaPipe Latin OCR |
| `URL` | `fetchUrlAsText` | Jsoup fetch + visible-text extraction (drops `script`, `style`, `noscript`, `nav`, `footer`, `aside`, `header`) |

The chunker, embedder, and retriever are type-agnostic. Once an
extractor returns a string, every downstream stage works unchanged.

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

- **Local**: `embedderProviderId == "LOCAL"`. Calls
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

## Retrieval

`KnowledgeService.retrieve(context, attached: List<KnowledgeBase>,
query: String, topK: Int)`:

1. Embed the query once (using the embedder shared across all
   attached KBs — they must match).
2. Sweep every `KnowledgeChunk` in every attached KB, compute cosine
   similarity against the query embedding.
3. Sort descending, take top-K.
4. Format the result via `formatContextBlock(...)` into a
   `[KNOWLEDGE]…[/KNOWLEDGE]` text block including each chunk's
   origin label.

The dispatch layer prepends the context block:
- For **reports**: `AnalysisRepository.analyzeWithAgent` reads
  `report.knowledgeBaseIds`, retrieves once per agent call, and
  prepends to that agent's prompt.
- For **chats**: each user turn re-retrieves against the current
  message text and prepends the block to the system message before
  the dispatch.

The "embedder must match" rule is enforced by the retrieval path:
attaching two KBs with different embedders to the same chat will
silently use only the first one's results (same vector dimension is
required for cosine).

## On-disk layout

```
<filesDir>/knowledge/<kbId>/
  manifest.json          KnowledgeBase + sources
  chunks/
    <sourceId>.json      JSON array of KnowledgeChunk
```

One JSON file per source keeps add / remove / re-index cheap (no
full-KB rewrite for a single-source change), while loading a whole
KB for retrieval still scans only one directory.

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
  KB directory.
- A full reset (Housekeeping → Full reset) wipes
  `<filesDir>/knowledge/` and the `embeddings/` cache.
- Embedder model files (`.tflite`) and Local LLM bundles (`.task`)
  persist across full resets — they have their own per-row Remove
  on the matching Housekeeping cards.
