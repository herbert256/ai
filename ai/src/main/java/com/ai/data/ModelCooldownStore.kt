package com.ai.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks provider/model pairs that are temporarily unavailable
 * because the provider answered a 429 with a retry hint longer
 * than [LONG_RETRY_THRESHOLD_MS] (Google's exhausted-quota case).
 *
 * Plain `object` singleton — both the OkHttp interceptor (no
 * Context) and Compose model pickers read it. Cooldowns can be
 * hours long, so the map is persisted to its own
 * SharedPreferences and reloaded on [init].
 *
 * Key shape is `"${providerId}:${model}"` — the same form as
 * [ReportModel.deduplicationKey].
 *
 * A second [traceMap] holds the filename of the API trace whose
 * 429 produced each cooldown. It's device-local (trace files
 * don't travel), so it's persisted alongside but **not** part of
 * the export bundle / the public [cooldowns] StateFlow.
 */
object ModelCooldownStore {
    /** Retry hints longer than this (1 hour) bench the model. */
    const val LONG_RETRY_THRESHOLD_MS = 60L * 60L * 1000L

    private const val PREFS = "model_cooldowns"
    private const val KEY_MAP = "map"
    private const val KEY_TRACES = "traces"

    private val gson = createAppGson()
    private val mapType = object : TypeToken<Map<String, Long>>() {}.type
    private val traceType = object : TypeToken<Map<String, String>>() {}.type

    /** Key → epoch-ms when the model becomes available again. */
    private val cooldownMap = ConcurrentHashMap<String, Long>()
    /** Key → filename of the API trace whose 429 caused the bench. */
    private val traceMap = ConcurrentHashMap<String, String>()

    private val _cooldowns = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Snapshot for Compose pickers. Entries may be expired —
     *  callers compare the value against `System.currentTimeMillis()`. */
    val cooldowns: StateFlow<Map<String, Long>> = _cooldowns

    // ---- Short-bench tier (type-A fixed-model batch 429/529 backoff) ----
    //
    // A SEPARATE, short-lived, session-only map. When a fixed-model batch
    // (Fan Out, Judge the judges) gets a 429/529, the model is parked here
    // for the response's Retry-After hint (or [typeABenchBaseMs] when there's
    // none); the batch loop gates same-model items on it and re-queues them
    // when it expires. Kept apart from [cooldownMap] so the model pickers —
    // which read [cooldowns] and render "rate-limited · back HH:mm" — don't
    // flicker for a 10 s blip, and so it isn't persisted (a transient bench
    // shouldn't survive a restart).

    /** Enable the short-bench-and-requeue behaviour for fixed-model
     *  (type-A) batches. Mirrored from GeneralSettings. */
    @Volatile var typeABenchEnabled: Boolean = true
    /** Bench duration when a 429/529 carries no Retry-After hint. */
    @Volatile var typeABenchBaseMs: Long = 10_000L
    /** Consecutive benches one item gets before the batch loop gives up
     *  and leaves it errored. */
    @Volatile var typeABenchMaxAttempts: Int = 5

    private val shortBenchMap = ConcurrentHashMap<String, Long>()
    private val _shortBenches = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Snapshot of short-bench expiries, for the live Bench stat. Entries
     *  may be expired — callers compare against `currentTimeMillis()`. */
    val shortBenches: StateFlow<Map<String, Long>> = _shortBenches

    /** Park (provider, model) until [availableAtMs] for the type-A batch
     *  429/529 backoff. Called from the retry interceptors. */
    fun markShortBench(providerId: String, model: String, availableAtMs: Long) {
        val k = key(providerId, model)
        shortBenchMap[k] = availableAtMs
        _shortBenches.value = shortBenchMap.toMap()
        AppLog.d("ModelCooldown", "$providerId/$model short-benched ${availableAtMs - System.currentTimeMillis()}ms")
    }

    /** True when the pair is short-benched and the bench hasn't expired.
     *  Pure timestamp compare (no side effects) so the batch loop / stat
     *  can poll it freely. */
    fun isShortBenched(providerId: String, model: String): Boolean {
        val until = shortBenchMap[key(providerId, model)] ?: return false
        return until > System.currentTimeMillis()
    }

    fun shortBenchUntil(providerId: String, model: String): Long? =
        shortBenchMap[key(providerId, model)]?.takeIf { it > System.currentTimeMillis() }

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        cooldownMap.clear()
        traceMap.clear()
        runCatching {
            val raw = prefs()?.getString(KEY_MAP, null) ?: return@runCatching
            val loaded: Map<String, Long> = gson.fromJson(raw, mapType) ?: emptyMap()
            cooldownMap.putAll(loaded)
        }
        runCatching {
            val raw = prefs()?.getString(KEY_TRACES, null) ?: return@runCatching
            val loaded: Map<String, String> = gson.fromJson(raw, traceType) ?: emptyMap()
            traceMap.putAll(loaded)
        }
        pruneExpired()
        publish()
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markUnavailable(providerId: String, model: String, availableAtMs: Long, traceFile: String? = null) {
        val k = key(providerId, model)
        cooldownMap[k] = availableAtMs
        if (traceFile != null) traceMap[k] = traceFile else traceMap.remove(k)
        AppLog.w(
            "ModelCooldown",
            "$providerId/$model benched until ${java.util.Date(availableAtMs)}" +
                (traceFile?.let { " (trace $it)" } ?: "")
        )
        persist()
        publish()
    }

    /** True when the pair is benched and the cooldown hasn't
     *  expired yet. Pure timestamp compare (Bug 48): model pickers call
     *  this per row, so it must not write SharedPreferences / emit on the
     *  StateFlow as a side effect of a "read". Expired entries are pruned
     *  in [init] and by [pruneExpired] sweeps instead. */
    fun isUnavailable(providerId: String, model: String): Boolean {
        val until = cooldownMap[key(providerId, model)] ?: return false
        return until > System.currentTimeMillis()
    }

    fun availableAt(providerId: String, model: String): Long? =
        cooldownMap[key(providerId, model)]?.takeIf { it > System.currentTimeMillis() }

    /** One benched (provider, model) pair. [traceFile] points at the
     *  API trace whose 429 produced the cooldown, when known. */
    data class CooldownEntry(
        val providerId: String,
        val model: String,
        val availableAtMs: Long,
        val traceFile: String? = null
    )

    /** Every stored cooldown — raw, **not** expiry-pruned, so the
     *  CRUD screen can show and clear stale rows. */
    fun entries(): List<CooldownEntry> = cooldownMap.entries.map { (k, v) ->
        CooldownEntry(k.substringBefore(":"), k.substringAfter(":"), v, traceMap[k])
    }

    /** Drop a single cooldown (manual un-bench). */
    fun remove(providerId: String, model: String) {
        val k = key(providerId, model)
        if (cooldownMap.remove(k) != null) {
            traceMap.remove(k)
            persist()
            publish()
        }
    }

    /** Drop every cooldown. */
    fun clearAll() {
        if (cooldownMap.isEmpty()) return
        cooldownMap.clear()
        traceMap.clear()
        persist()
        publish()
    }

    /** Merge imported cooldowns into the store (import path). Trace
     *  filenames are device-local and aren't carried in the bundle. */
    fun importMerge(incoming: Map<String, Long>) {
        if (incoming.isEmpty()) return
        cooldownMap.putAll(incoming)
        persist()
        publish()
    }

    /** Picker caption for a benched model, e.g. "rate-limited ·
     *  back 14:30" (today) or "rate-limited · back May 15 14:30". */
    fun cooldownCaption(untilMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val thisYear = cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = untilMs
        // Compare year AND day-of-year (Bug 47): a cooldown exactly one year
        // out on the same day-of-year would otherwise render as today's HH:mm
        // with no date.
        val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == today &&
            cal.get(java.util.Calendar.YEAR) == thisYear
        val fmt = if (sameDay) "HH:mm" else "MMM d HH:mm"
        val when_ = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(untilMs))
        return "rate-limited · back $when_"
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val expired = cooldownMap.filterValues { it <= now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { cooldownMap.remove(it); traceMap.remove(it) }
            persist()
        }
    }

    private fun persist() {
        runCatching {
            prefs()?.edit()
                ?.putString(KEY_MAP, gson.toJson(cooldownMap.toMap()))
                ?.putString(KEY_TRACES, gson.toJson(traceMap.toMap()))
                ?.apply()
        }
    }

    private fun publish() {
        _cooldowns.value = cooldownMap.toMap()
    }
}
