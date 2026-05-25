package com.ai.data

import android.content.Context
import com.ai.ui.hub.reportHasProblems
import com.ai.ui.hub.reportIsRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One immutable snapshot for the Stress Test Dashboard, scoped to the set
 *  of report IDs the stress run(s) launched this session. Report-progress
 *  fields come from [ReportStorage]; the trace rollups come from the subset
 *  of [ApiTracer] traces whose `reportId` is in the tracked set. */
internal data class StressDashboardData(
    // ----- report progress (scoped to tracked set) -----
    val tracked: Int,
    val resolved: Int,        // tracked reports actually found on disk
    val done: Int,            // not running, no problems
    val running: Int,         // reportIsRunning == true
    val withProblems: Int,    // not running && reportHasProblems == true
    val totalCost: Double,    // Σ Report.totalCost over tracked reports
    // ----- trace rollups (traces whose reportId ∈ tracked set) -----
    val tracingEnabled: Boolean,
    val traceTotal: Int,
    val ok2xx: Int, val rate429: Int, val client4xx: Int, val server5xx: Int, val failed0: Int,
    val partial: Int,
    val byProvider: List<Pair<String, Int>>,   // hostname -> count, desc
    val providerErrors: Map<String, Int>,       // hostname -> non-2xx count
    val byCategory: List<Pair<String, Int>>,    // category -> count, desc
    val byModel: List<Pair<String, Int>>,       // model -> count, desc
    val recentErrors: List<TraceFileInfo>,      // non-2xx, newest first
    val callsLastMin: Int,                       // traces within trailing 60 s
    // ----- run timing (passed through for throughput in the UI) -----
    val runCount: Int,
    val firstStartedAt: Long,
    val lastStartedAt: Long,
)

private fun <T> sortedDescLocal(counts: Map<T, Int>): List<Pair<T, Int>> =
    counts.entries.sortedByDescending { it.value }.map { it.key to it.value }

/** One pass over storage + the cached trace list, scoped to [trackedIds].
 *  Off the main thread; cheap once the trace cache is warm. */
internal suspend fun computeStressDashboard(
    context: Context,
    trackedIds: Set<String>,
    runCount: Int,
    firstStartedAt: Long,
    lastStartedAt: Long,
): StressDashboardData = withContext(Dispatchers.IO) {
    // ----- report progress -----
    var resolved = 0; var done = 0; var running = 0; var withProblems = 0
    var totalCost = 0.0
    for (id in trackedIds) {
        val report = ReportStorage.getReport(context, id) ?: continue
        resolved++
        totalCost += report.totalCost
        val isRunning = reportIsRunning(report, emptySet())
        if (isRunning) { running++; continue }
        val secs = SecondaryResultStorage.listForReport(context, id)
        if (reportHasProblems(report, secs)) withProblems++ else done++
    }

    // ----- trace rollups, scoped to the tracked reports -----
    val traces = ApiTracer.getTraceFiles().filter { it.reportId in trackedIds }
    val now = System.currentTimeMillis()
    val cut = now - 60_000L
    val hosts = HashMap<String, Int>()
    val hostErrors = HashMap<String, Int>()
    val cats = HashMap<String, Int>()
    val models = HashMap<String, Int>()
    var ok2xx = 0; var r429 = 0; var c4xx = 0; var s5xx = 0; var f0 = 0
    var partial = 0; var callsLastMin = 0
    for (t in traces) {
        val isErr = t.statusCode !in 200..299
        when {
            t.statusCode in 200..299 -> ok2xx++
            t.statusCode == 429 -> r429++
            t.statusCode in 400..499 -> c4xx++
            t.statusCode in 500..599 -> s5xx++
            t.statusCode == 0 -> f0++
        }
        if (t.partial) partial++
        if (t.timestamp >= cut) callsLastMin++
        if (t.hostname.isNotBlank()) {
            hosts[t.hostname] = (hosts[t.hostname] ?: 0) + 1
            if (isErr) hostErrors[t.hostname] = (hostErrors[t.hostname] ?: 0) + 1
        }
        val cat = t.category?.takeIf { it.isNotBlank() } ?: "(uncategorised)"
        cats[cat] = (cats[cat] ?: 0) + 1
        t.model?.takeIf { it.isNotBlank() }?.let { models[it] = (models[it] ?: 0) + 1 }
    }
    val recentErrors = traces.filter { it.statusCode !in 200..299 }
        .sortedByDescending { it.timestamp }.take(8)

    StressDashboardData(
        tracked = trackedIds.size,
        resolved = resolved, done = done, running = running, withProblems = withProblems,
        totalCost = totalCost,
        tracingEnabled = ApiTracer.isTracingEnabled,
        traceTotal = traces.size,
        ok2xx = ok2xx, rate429 = r429, client4xx = c4xx, server5xx = s5xx, failed0 = f0,
        partial = partial,
        byProvider = sortedDescLocal(hosts),
        providerErrors = hostErrors,
        byCategory = sortedDescLocal(cats),
        byModel = sortedDescLocal(models),
        recentErrors = recentErrors,
        callsLastMin = callsLastMin,
        runCount = runCount,
        firstStartedAt = firstStartedAt,
        lastStartedAt = lastStartedAt,
    )
}
