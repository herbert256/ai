package com.ai.data

import android.content.Context
import com.ai.model.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Repairs diagnostic data only. Never copies a user's results or states into defaults. */
object ModelTestMigration {
    fun repair(context: Context, settings: Settings): Settings {
        val run = ModelTestRunStore.load(context) ?: return settings
        if (run.policyVersion >= ModelProbePolicy.VERSION) return reconcileBlocks(run, settings)
        val traces = run.runId?.let { ApiTracer.getTraceFilesForRun(it) }.orEmpty()
        val tracesByModel = traces.groupBy { it.model }
        val inaccessible = settings.inaccessibleModels.associateBy { it.key }
        val correctedBlocks = mutableSetOf<String>()
        val ambiguousAccess = mutableSetOf<String>()
        val items = run.items.mapValues { (_, old) ->
            val service = AppService.findById(old.providerId)
            val hosts = service?.let {
                listOfNotNull(it.baseUrl.toHttpUrlOrNull()?.host,
                    it.nativeRerankUrl?.toHttpUrlOrNull()?.host,
                    it.nativeModerationUrl?.toHttpUrlOrNull()?.host)
            }.orEmpty()
            // Legacy records did not save request identity. Only relink a
            // unique run + provider host + model match; ambiguity stays unlinked.
            val matches = tracesByModel[old.model].orEmpty().filter { it.hostname in hosts }
            val trace = if (run.policyVersion > 0) matches.firstOrNull { it.filename == old.traceFilename } ?: matches.singleOrNull()
                else matches.singleOrNull()
            var item = old.copy(traceFilename = trace?.filename, previousErrorMessage = old.errorMessage ?: old.previousErrorMessage)
            if (old.status == TestStatus.PASS && old.responseText?.startsWith("Inaccessible") == true) {
                val reason = trace?.filename?.let { ApiTracer.readTraceFile(it) }?.response?.let {
                    "API error: ${it.statusCode} - ${it.body.orEmpty()}"
                } ?: inaccessible[old.key]?.reason ?: "Legacy probe reported unavailable; original evidence has expired."
                val explicitAccess = ModelProbePolicy.isInaccessibleError(reason) && !ModelProbePolicy.isUnsupportedError(reason)
                item = item.copy(status = if (explicitAccess) TestStatus.INACCESSIBLE else TestStatus.UNSUPPORTED,
                    responseText = null, errorMessage = reason)
                if (!explicitAccess) ambiguousAccess += old.key
            } else if (old.status == TestStatus.FAIL) {
                val unsupported = service?.let { ModelProbePolicy.unsupportedReason(it, old.model, settings.getModelType(it, old.model)) }
                val actualReply = trace?.statusCode in 200..299 && !old.responseText.isNullOrBlank() &&
                    old.errorMessage?.startsWith("Response truncated:") == true
                item = when {
                    actualReply -> item.copy(status = TestStatus.PASS, errorMessage = null)
                    unsupported != null || ModelProbePolicy.isUnsupportedError(old.errorMessage) ->
                        item.copy(status = TestStatus.UNSUPPORTED) // Preserve the original rejection.
                    ModelProbePolicy.isInaccessibleError(old.errorMessage) -> item.copy(status = TestStatus.INACCESSIBLE)
                    else -> item
                }
                if (item.status != TestStatus.FAIL) correctedBlocks += old.key
                if (item.errorMessage?.contains("timed out after 180s") == true && old.durationMs in 60_000L..61_000L) {
                    item = item.copy(errorMessage = "Test timed out after 60s")
                }
            }
            item
        }
        val updated = run.copy(items = items, policyVersion = ModelProbePolicy.VERSION)
        // Persist the corrected evidence before changing any diagnostic lists.
        if (!ModelTestRunStore.save(context, updated)) return settings
        run.runId?.let { ApiTracer.retainModelTestRun(it) }
        val blocked = settings.blockedModels.mapNotNull { block ->
            val old = run.items[block.key]
            val autoReason = old?.errorMessage?.take(300)
            when {
                old == null || block.reason != autoReason -> block // Preserve manual edits.
                block.key in correctedBlocks -> null
                items[block.key]?.errorMessage != old.errorMessage ->
                    block.copy(reason = items[block.key]?.errorMessage.orEmpty().take(300))
                else -> block
            }
        }
        val access = settings.inaccessibleModels.filterNot { entry ->
            entry.key in ambiguousAccess && run.items[entry.key]?.responseText?.startsWith("Inaccessible") == true &&
                (entry.reason.contains("API error: 404") || entry.reason.contains("\"code\":404"))
        }
        val existingAccessKeys = access.map { it.key }.toSet()
        val addedAccess = items.values.filter { it.status == TestStatus.INACCESSIBLE &&
            it.key !in existingAccessKeys && ModelProbePolicy.isInaccessibleError(it.errorMessage) }
            .map { com.ai.model.InaccessibleModel(it.providerId, it.model, it.errorMessage.orEmpty().take(300)) }
        AppLog.i("ModelTest", "Migrated ${items.size} results; removed ${settings.blockedModels.size - blocked.size} false auto-blocks, ${settings.inaccessibleModels.size - access.size} ambiguous auto-access entries")
        return settings.copy(blockedModels = blocked, inaccessibleModels = access + addedAccess)
    }
    private fun reconcileBlocks(run: ModelTestRunState, settings: Settings): Settings {
        val blocked = settings.blockedModels.mapNotNull { block ->
            val item = run.items[block.key]
            if (item?.previousErrorMessage == null || block.reason != item.previousErrorMessage.take(300)) block
            else if (item.status == TestStatus.PASS || item.status == TestStatus.UNSUPPORTED || item.status == TestStatus.INACCESSIBLE) null
            else if (item.status == TestStatus.FAIL) block.copy(reason = item.errorMessage.orEmpty().take(300))
            else block
        }
        return if (blocked == settings.blockedModels) settings else settings.copy(blockedModels = blocked)
    }

}
