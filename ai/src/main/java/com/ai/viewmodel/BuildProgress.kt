package com.ai.viewmodel

/** Progress of a batch's **build stage** — the pre-dispatch phase that
 *  enumerates every work item and writes its placeholder row before any
 *  API call fires. Surfaced as a blocking "Preparing N / M…" popup so the
 *  user lands on a fully-built batch screen instead of a half-built one.
 *
 *  Lives in [AppViewModel.batchBuildProgress], keyed by a UUID the UI
 *  mints before launching the batch (the runId isn't known until the
 *  launch call returns, so the UI owns the key). [done] flips true once
 *  every placeholder is persisted; the UI then dismisses the popup and
 *  runs its deferred navigation. */
data class BuildProgress(
    val built: Int,
    val total: Int,
    val done: Boolean = false,
    val label: String = ""
)
