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
| `KnowledgeBase` | id, name, `embedderProviderId` + `embedderModel` (fixed at creation), `embeddingDim`, `createdAt`, `sources`; computed `totalChunks` / `totalChars` |
| `KnowledgeSource` | id, `type` (a `KnowledgeSourceType`), display `name`, `origin` (SAF Uri or URL, kept so re-index can refetch), `addedAt`, `chunkCount`, `charCount`, optional `errorMessage` |
| `KnowledgeChunk` | id, `sourceId`, `ordinal`, `text`, `embedding: FloatArray` |

The embedder identity is **fixed per KB** — query and chunk vectors
must live in the same space, so changing the embedder means deleting
and recreating the KB.

### The `"LOCAL"` embedder sentinel (mind the case)

`embedderProviderId == "LOCAL"` (all-caps) routes to the on-device
[`LocalEmbedder`](local-runtime.md) — `KnowledgeService` short-circuits
straight to `LocalEmbedder.embed` and **never** calls `AppService.findById`.
A KB created with the local embedder is stamped with `providerKey = "LOCAL"`
in `LocalSemanticSearchScreen`. Any other id is treated as a provider id,
resolved with `AppService.findById(embedderProviderId)` and embedded over
`/v1/embeddings` (or Gemini `batchEmbedContents`) via `AnalysisRepository.embed`.

> **Casing trap:** the KB local-embedder sentinel is the literal
> all-caps string `"LOCAL"`. The chat/report *LLM* sentinel
> `AppService.LOCAL.id` is `"Local"` (capital-L only). The two are
> distinct strings used by distinct dispatch forks — do not conflate
> them.

### Why `embedding` is a `FloatArray`

`KnowledgeChunk.embedding` is a primitive **`FloatArray`**, not
`List<Double>` — roughly a 6× heap reduction for thousand-dim vectors
(4 bytes/dim vs ~24 bytes boxed), and float precision is
indistinguishable from double for cosine ranking. It feeds the
primitive
[`EmbeddingsStore.cosine(FloatArray, FloatArray)`](../ai/src/main/java/com/ai/data/EmbeddingsStore.kt)
retrieval hot path
([`EmbeddingsStore.kt:105`](../ai/src/main/java/com/ai/data/EmbeddingsStore.kt)),
which iterates primitives but accumulates `dot`/`normA`/`normB` in
`Double` internally to avoid float-accumulator drift.

Gson reads any numeric JSON array into a `FloatArray`, so on-disk
storage is unchanged (existing full-double chunk files keep working,
truncated to ~7 significant digits on read). `KnowledgeChunk`
overrides `equals`/`hashCode` to use `contentEquals`/`contentHashCode`
on the array — the auto-generated `data class` versions would compare
the array by reference identity and silently never match.

The boxed `cosine(List<Double>, List<Double>)` in the same file is a
**separate** path used by report semantic search via `EmbeddingsStore`'s
per-document cache, not by KB retrieval. Both overloads return `0.0`
when either vector is empty, and **log a warning and return `0.0` on a
dim mismatch** (the previous silent-zero made a "wrong embedder"
mistake look like "no relevant hits"). KB retrieval uses **only** the
`FloatArray` overload.

## The 9 source extractors

`KnowledgeSourceType` has exactly nine values, in this order
([`Knowledge.kt:11`](../ai/src/main/java/com/ai/data/Knowledge.kt)):
`TEXT, MARKDOWN, PDF, DOCX, ODT, XLSX, ODS, CSV, URL`.
`KnowledgeExtractors.extract(context, type, origin)` dispatches all
nine in a single `when` block
([`KnowledgeExtractors.kt:38`](../ai/src/main/java/com/ai/data/KnowledgeExtractors.kt));
each maps to one private function in the single `KnowledgeExtractors`
object — there are **no** per-type extractor classes (the object's own
KDoc naming `TextExtractor`/`MarkdownExtractor`/`PdfExtractor`/`HtmlExtractor`
is stale and lists only four):

| Type | Extractor (private fn) |
|---|---|
| `TEXT` | `readUriText` — read straight, forced UTF-8, normalise newlines |
| `MARKDOWN` | `readUriText` — identical path to `TEXT`; paragraph boundaries preserved for the chunker |
| `PDF` | `readUriPdf` — PDFBox-Android `PDFTextStripper` (`sortByPosition = true`) |
| `DOCX` | `readUriDocx` — streaming `XmlPullParser` over `word/document.xml` (`<w:p>`→¶, `<w:t>` text, `<w:tab>`→`\t`) |
| `ODT` | `readUriOdt` — same approach over `content.xml` (`<text:p>`/`<text:h>`→¶, `<text:tab>`→`\t`) |
| `XLSX` | `readUriXlsx` — single-pass zip walk of `xl/sharedStrings.xml` + `xl/worksheets/sheet*.xml`, tab-separated rows, `[sheet N]` headers (spools pre-sharedStrings sheets to a cacheDir temp file) |
| `ODS` | `readUriOds` — `content.xml` `<table:table>`/`<table:table-row>`/`<table:table-cell>`, tab-separated, `[sheet N]` headers |
| `CSV` | `readUriCsv` — RFC-4180-ish `PushbackReader` tokenizer; comma-vs-semicolon delimiter auto-sniffed from a 1 KB sample; detected header repeated atop each 10-row block |
| `URL` | `fetchUrlAsText` — Jsoup fetch (UA `Mozilla/5.0 (compatible; AI-Reports-RAG/1.0)`, 20 s timeout), strips `script/style/noscript/nav/footer/aside/header`, returns `body().text()` |

Every extractor returns `String.normalised()`: CRLF/CR→LF, runs of
3+ newlines collapsed to exactly 2 (`Regex("\n{3,}") → "\n\n"`),
then `trim()`.

## Pipeline: ingest → chunk → embed → retrieve

Driven by
[`data/KnowledgeService.kt`](../ai/src/main/java/com/ai/data/KnowledgeService.kt).

**Ingest** — `indexFile` / `indexUrl` / `reindexSource`. For files the
bytes are first copied into `knowledge/<kbId>/files/` by
`persistSourceLocally` (sibling `.tmp` + `fd.sync()` + `Files.move`
`ATOMIC_MOVE`, falling back to a non-atomic move when unsupported) so
re-index survives a relaunch without re-asking for the SAF permission;
it returns a `file://` Uri. The stored name is
`<millis>_<uuid8>_<sanitizedDisplayName>` to avoid same-millisecond
collisions. Then `KnowledgeExtractors.extract` produces the text.
Extraction yielding no text saves a zero-chunk source row carrying
`errorMessage = "No text extracted from source"` so the failure is
visible in the list rather than vanishing.

**Chunk** — `KnowledgeChunker.chunk(text, maxCharsPerChunk = 2048,
overlapChars = 200)`: split on `Regex("\n{2,}")`, greedily merge
paragraphs until the next would push past 2048 chars (~512 tokens at
~4 chars/token), carrying the last 200 chars (`takeLast`) of each
emitted chunk into the next; a single paragraph longer than 2048 is
hard-split by char count. Returns chunk-text strings only;
`id`/`sourceId`/`ordinal` are attached later by `KnowledgeService`.

**Embed** — batched: `batchSize = 1` for `"LOCAL"` (one MediaPipe
`TextEmbedder` call per text), else `32` per remote `/v1/embeddings`
call. Vectors come back `List<Double>` and are converted per chunk to
`FloatArray`. `embeddingDim` is taken from the first vector; a
**zero-dim** batch errors `"Embedder returned empty vectors"`, and any
**per-chunk dim mismatch** errors too — rather than persist
silently-empty chunks that would score `0.0` on every future retrieval.
Chunks + the updated source row are written by
`KnowledgeStore.saveSource`, which adopts the first source's dim and
**warns loudly** if a later re-index produces a different dim (the
manifest dim is retained; the user almost certainly swapped embedders
mid-life and should re-create the KB).

**Remote embed dispatch** — `repository.embed` / `embedWithStatus` are
extension functions on `AnalysisRepository` that live in
[`ApiDispatch.kt`](../ai/src/main/java/com/ai/data/ApiDispatch.kt) (not
in `AnalysisRepository.kt`). They support **only** `OPENAI_COMPATIBLE`
(`/v1/embeddings` via `OpenAiEmbeddingRequest`) and `GOOGLE`
(`batchEmbedContents`). `ANTHROPIC` and every other format are
rejected with `"Embed dispatch only supports OpenAI-compatible and
Google providers"` — so a KB whose embedder is an Anthropic model can
never index.

**Retrieve** — `retrieve(kbIds, query, topK = 8, maxContextChars =
8000)`:

1. Embed the query with the **first** KB's embedder (a warning is
   logged if the other attached KBs declare a different
   `embedderProviderId`/`embedderModel` — the first's is used anyway).
2. Convert the query vector to `FloatArray`, then stream every chunk
   of every embedder-matching KB via `KnowledgeStore.forEachChunk`
   through a bounded min-heap of size `topK * 2` (= 16) ordered by
   cosine score. KBs/chunks whose stored dim ≠ the query dim are
   **skipped and logged**, never silently zeroed.
3. Sort survivors descending; walk them taking chunks until
   `maxContextChars` is reached, **stopping at score ≤ 0.0**. Bug-42
   special case: if the single top-ranked chunk alone overflows the
   empty budget it is truncated with `take(remaining)` rather than
   dropped.

Returns `List<Hit>(kbId, kbName, sourceName, text, score)`.
`formatContextBlock` wraps the hits in a `<context>…</context>` block,
with per-hit `[i] kbName / sourceName` headers and an instruction to
cite the source name, returning `""` for an empty hit list so callers
can unconditionally concatenate.

**Injection at API time** — a chat or report stores its attached KBs
as `knowledgeBaseIds` (on `ChatSession`, `Report`). The retrieve +
format step runs per call, wrapped in `runCatching` so an embedder
hiccup falls back to the bare prompt instead of killing the call:

- **Chat**: `ChatViewModel.messagesWithRag`
  ([`viewmodel/ChatViewModel.kt`](../ai/src/main/java/com/ai/viewmodel/ChatViewModel.kt))
  retrieves against the **last user message**, then either **merges**
  the context block into the existing system message (preserving the
  user's own system prompt) or inserts a new system message at the
  head. The whole flow runs inside `flow { … }.flowOn(Dispatchers.IO)`
  so the embedder + cosine sweep never block the collector (usually
  the main) thread.
- **Report**: `AnalysisRepository.analyze` builds a `ragPrefix` when
  `knowledgeBaseIds` is non-empty and a `context` + `aiSettings` are
  present, then prepends it to the built prompt via `withRagPrefix`
  ([`data/AnalysisRepository.kt` — the `ragPrefix` block just before
  the `agent.provider.id == AppService.LOCAL.id`
  fork](../ai/src/main/java/com/ai/data/AnalysisRepository.kt)).

Because injection keys off the stored `knowledgeBaseIds`, an
already-attached KB keeps feeding context even when the
Experimental-features toggle has hidden every attach button.
(Per-report export bundles strip `knowledgeBaseIds` to empty on
export — the KB blobs are not packed; see
[backup-restore.md](backup-restore.md).)

## On disk

Under `<filesDir>/` (see [persistent.md](persistent.md)):

```
knowledge/<kbId>/
  manifest.json          — KnowledgeBase + its sources
  chunks/<sourceId>.json — JSON array of KnowledgeChunk
  files/<unique>         — locally-cached copy of a file source
```

One chunk file per source keeps add/remove/re-index cheap (no full-KB
rewrite). `forEachChunk` parses one source file at a time and lets the
decoded array go out of scope between files, so peak heap during
retrieval is bounded by the largest single source's chunks plus the
top-K heap — never the whole KB.

`KnowledgeStore` is an `object` whose mutators serialise their manifest
read-modify-write under a shared `ReentrantLock`. Both `kbId` and
`sourceId` pass `isSafeKbId`/`isSafeSourceId` (reject blank, `.`, `..`,
`/`, `\`; `kbId` also rejects spaces) **and** a canonical-containment
check (the resolved dir's canonical path must start with the root's
canonical path + separator) before any file op, so a malformed or
restored manifest cannot write outside the `knowledge/` root.
`resolveKbDir` is the public validated-root accessor used by
`persistSourceLocally`.

The separate `<filesDir>/embeddings/` directory belongs to
`EmbeddingsStore`'s per-document report-embedding cache — **not** KB
chunks, which live under `knowledge/`. (Note: the
`local_llms`/`local_models`/`native`/`applog` dirs are excluded from
backups, but `knowledge/` is **included**.)

## Related docs

- [experimental.md](experimental.md) — the master toggle that gates
  the Knowledge UI surfaces (cards, attach chips, share-target entry).
- [local-runtime.md](local-runtime.md) — the on-device `LocalEmbedder`
  used when `embedderProviderId == "LOCAL"`.
- [datastructures.md](datastructures.md) — full field tables for the
  data classes.
- [persistent.md](persistent.md) — the on-disk layout under
  `<filesDir>`.
- [api-formats.md](api-formats.md) — the embed dispatch and the
  OpenAI-compatible / Google split.
- [share-target.md](share-target.md) — `ACTION_SEND` ingest that
  pre-stages a file/URL into a KB.
