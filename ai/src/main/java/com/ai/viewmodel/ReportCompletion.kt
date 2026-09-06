package com.ai.viewmodel

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Once a provider has returned, preserve both its usage and its answer even
 * when Stop races the IO hop. A failed accounting write has its own recovery
 * entry and must not prevent the received answer from reaching its save path. */
internal suspend fun <T> persistReportCompletion(recordUsage: suspend () -> Unit, saveAnswer: () -> T): T =
    withContext(NonCancellable) {
        var accountingFailure: Exception? = null
        try { recordUsage() } catch (e: Exception) { accountingFailure = e }
        val saved = saveAnswer()
        accountingFailure?.let { throw it }
        saved
    }
