# AI Knowledge — RAG knowledge bases

> Retrieval-augmented generation. A **knowledge base** (KB) holds
> ingested documents, chunked and embedded; a KB attached to a chat
> or report retrieves the chunks most similar to the current prompt
> and injects them as a `<context>` block before each API call.
>
> The whole subsystem's UI is gated behind the **Experimental
> features** master toggle (see [experimental.md](experimental.md)).
> When it's off the entry points disappear, but **KBs already
> attached to a chat or report keep injecting context at API time** —
> only the attach/manage UI is hidden, nothing on disk is touched.

## Data model

In [`data/Knowledge.kt`](../ai/src/main/java/com/ai/data/Knowledge.kt):

| Type | Holds |
|---|---|
| `KnowledgeBase` | id, name, `embedderProviderId` + `embedderModel` (fixed at creation), `embeddingDim`, `sources` |
| `KnowledgeSource` | id, `type` (a `KnowledgeSourceType`), display `name`, `origin` (SAF Uri or URL, kept so re-index can refetch), `chunkCount`, `charCount`, optional `errorMessage` |
| `KnowledgeChunk` | id, `sourceId`, `ordinal`, `text`, `embedding: FloatArray` |

The embedder identity is **fixed per KB** — query and chunk vectors
must live in the same space, so changing the embedder means deleting
and recreating the KB. `embedderProviderId == "LOCAL"` routes to the
on-device [`LocalEmbedder`](local-runtime.md); any other id is an
`AppService` resolved via `findById`, embedded over `/v1/embeddings`
through `AnalysisRepository.embed`.

`KnowledgeChunk.embedding` is a primitive **`FloatArray`**, not
`List<Double>` — roughly a 6× heap reduction for thousand-dim vectors,
and it feeds the primitive
[`EmbeddingsStore.cosine(FloatArray, FloatArray)`](../ai/src/main/java/com/ai/data/EmbeddingsStore.kt)
retrieval hot path
([`EmbeddingsStore.kt:105`](../ai/src/main/java/com/ai/data/EmbeddingsStore.kt)).
Gson reads any numeric JSON array into a `FloatArray`, so on-disk
storage is unchanged. `KnowledgeChunk` overrides `equals`/`hashCode`
to use `contentEquals`/`contentHashCode` on the array.

(The boxed `cosine(List<Double>, List<Double>)` in the same file is a
separate path used by report semantic search via `EmbeddingsStore`'s
per-document cache, not by KB retrieval.)

## The 9 source extractors

`KnowledgeSourceType` has exactly nine values
([`Knowledge.kt:11`](../ai/src/main/java/com/ai/data/Knowledge.kt));
each maps to one extractor in
[`KnowledgeExtractors.kt`](../ai/src/main/java/com/ai/data/KnowledgeExtractors.kt):

| Type | Extractor |
|---|---|
| `TEXT` | Read straight, forced UTF-8, normalise newlines |
| `MARKDOWN` | Same as text; paragraph boundaries preserved for the chunker |
| `PDF` | PDFBox-Android `PDFTextStripper`, page-by-page, double-newline joined |
| `DOCX` | Streaming `XmlPullParser` over `word/document.xml` (`<w:p>`→¶, `<w:t>` text, `<w:tab>`→`\t`) |
| `ODT` | Same parser over `content.xml` (`<text:p>`/`<text:h>`→¶) |
| `XLSX` | Single-pass zip walk: `sharedStrings.xml` + `xl/worksheets/sheet*.xml`, tab-separated rows, `[sheet N]` headers |
| `ODS` | `content.xml` `<table:table>`/`<table:table-row>`/`<table:table-cell>`, tab-separated, `[sheet N]` headers |
| `CSV` | RFC-4180-ish tokenizer, comma/semicolon auto-detect; a detected header is repeated atop each 10-row block |
| `URL` | Jsoup fetch + visible-body text; strips `script/style/nav/footer/aside/header` |

Every extractor returns a single normalised string (CRLF→LF, runs of
3+ newlines collapsed to 2, trimmed).

## Pipeline: ingest → chunk → embed → retrieve

Driven by
[`data/KnowledgeService.kt`](../ai/src/main/java/com/ai/data/KnowledgeService.kt).

**Ingest** — `indexFile` / `indexUrl` / `reindexSource`. For files the
bytes are first copied into `knowledge/<kbId>/files/` (atomic
tmp+rename+fsync) so re-index survives a relaunch without re-asking
for the SAF permission. Then `KnowledgeExtractors.extract` produces
the text.

**Chunk** — `KnowledgeChunker.chunk`: greedy paragraph merge up to
`maxCharsPerChunk = 2048` (~512 tokens), carrying `overlapChars = 200`
from each chunk into the next; oversized single paragraphs are
hard-split by char count.

**Embed** — batched (size 1 for `LOCAL`, 32 remote). A zero-dim or
dim-mismatched batch is refused with a surfaced error rather than
saved as silently-empty chunks. Chunks + the updated source row are
written by `KnowledgeStore.saveSource`.

**Retrieve** — `retrieve(kbIds, query, topK = 8, maxContextChars =
8000)` embeds the query with the *first* KB's embedder, then
`KnowledgeStore.forEachChunk` streams every chunk through a bounded
`topK*2` min-heap of cosine scores (KBs whose embedder or chunk dim
disagrees are skipped, logged loud — silent mis-rank is the worst
failure mode). Survivors are sorted descending and taken until the
char budget is hit. `formatContextBlock` wraps the hits in a
`<context>…</context>` block instructing the model to cite the source
name.

**Injection at API time** — a chat or report stores its attached KBs
as `knowledgeBaseIds` (on `ChatSession`, `Report`). The retrieve +
format step runs per call:

- Chat: `ChatViewModel.messagesWithRag`
  ([`viewmodel/ChatViewModel.kt`](../ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt))
  retrieves against the last user message and prepends the context
  block.
- Report: `AnalysisRepository.analyze` builds a `ragPrefix` when
  `knowledgeBaseIds` is non-empty
  ([`data/AnalysisRepository.kt:215`](../ai/src/main/java/com/ai/data/AnalysisRepository.kt)).

Because injection keys off the stored `knowledgeBaseIds`, an
already-attached KB keeps feeding context even when the
Experimental-features toggle has hidden every attach button.
(Report bundles strip `knowledgeBaseIds` on export — the KB blobs
aren't packed.)

## On disk

Under `<filesDir>/` (see [persistent.md](persistent.md)):

```
knowledge/<kbId>/
  manifest.json          — KnowledgeBase + its sources
  chunks/<sourceId>.json — JSON array of KnowledgeChunk
  files/<unique>         — locally-cached copy of a file source
```

One chunk file per source keeps add/remove/re-index cheap (no full-KB
rewrite). `KnowledgeStore` enforces canonical-containment path checks
on both `kbId` and `sourceId` so a malformed/restored manifest can't
write outside the `knowledge/` root.

The separate `<filesDir>/embeddings/` directory belongs to
`EmbeddingsStore`'s per-document report-embedding cache — **not** KB
chunks, which live under `knowledge/`.

## Related docs

- [experimental.md](experimental.md) — the master toggle that gates
  the Knowledge UI surfaces (cards, attach chips, share-target entry).
- [local-runtime.md](local-runtime.md) — the on-device `LocalEmbedder`
  used when `embedderProviderId == "LOCAL"`.
- [datastructures.md](datastructures.md) — full field tables for the
  data classes.
- [persistent.md](persistent.md) — the on-disk layout under
  `<filesDir>`.
- [share-target.md](share-target.md) — `ACTION_SEND` ingest that
  pre-stages a file/URL into a KB.
