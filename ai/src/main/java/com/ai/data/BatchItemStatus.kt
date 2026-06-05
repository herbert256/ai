package com.ai.data

/** Canonical four-state lifecycle for a single batch item — a fan-out
 *  pair, tournament match, judge cell, compare cell, or translation
 *  item. The runner moves an item through these states in order; the
 *  UI's classifier reads from it directly.
 *
 *  Every batch kind used to declare its own identical enum
 *  (`PairStatus`, `MatchStatus`, `JudgeCellStatus`, `CompareCellStatus`,
 *  `TranslationStatus`). They now alias this single type so each domain
 *  keeps a readable name while sharing one type — which is what lets
 *  generic helpers (count derivation, etc.) work across every batch
 *  kind. */
enum class BatchItemStatus {
    /** Placeholder written to disk, waiting for the runner's throttle
     *  permit. UI: 🕓 queued. */
    PENDING,

    /** Permit acquired, HTTP in flight. UI: ⏳ running. */
    RUNNING,

    /** Result written to disk (content non-blank, or empty body but
     *  durationMs stamped). UI: ✅ done. */
    DONE,

    /** Error stamped on disk. UI: ❌ errored. */
    ERROR
}
