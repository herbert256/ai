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
 */
object ModelCooldownStore {
    /** Retry hints longer than this (1 hour) bench the model. */
    const val LONG_RETRY_THRESHOLD_MS = 60L * 60L * 1000L

    private const val PREFS = "model_cooldowns"
    private const val KEY_MAP = "map"

    private val gson = createAppGson()
    private val mapType = object : TypeToken<Map<String, Long>>() {}.type

    /** Key → epoch-ms when the model becomes available again. */
    private val cooldownMap = ConcurrentHashMap<String, Long>()

    private val _cooldowns = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Snapshot for Compose pickers. Entries may be expired —
     *  callers compare the value against `System.currentTimeMillis()`. */
    val cooldowns: StateFlow<Map<String, Long>> = _cooldowns

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        cooldownMap.clear()
        runCatching {
            val raw = prefs()?.getString(KEY_MAP, null) ?: return@runCatching
            val loaded: Map<String, Long> = gson.fromJson(raw, mapType) ?: emptyMap()
            cooldownMap.putAll(loaded)
        }
        pruneExpired()
        publish()
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markUnavailable(providerId: String, model: String, availableAtMs: Long) {
        cooldownMap[key(providerId, model)] = availableAtMs
        AppLog.w(
            "ModelCooldown",
            "$providerId/$model benched until ${java.util.Date(availableAtMs)}"
        )
        persist()
        publish()
    }

    /** True when the pair is benched and the cooldown hasn't
     *  expired yet. Expired entries are dropped lazily here. */
    fun isUnavailable(providerId: String, model: String): Boolean {
        val k = key(providerId, model)
        val until = cooldownMap[k] ?: return false
        if (until <= System.currentTimeMillis()) {
            cooldownMap.remove(k)
            persist()
            publish()
            return false
        }
        return true
    }

    fun availableAt(providerId: String, model: String): Long? =
        cooldownMap[key(providerId, model)]?.takeIf { it > System.currentTimeMillis() }

    /** Picker caption for a benched model, e.g. "rate-limited ·
     *  back 14:30" (today) or "rate-limited · back May 15 14:30". */
    fun cooldownCaption(untilMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = untilMs
        val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == today
        val fmt = if (sameDay) "HH:mm" else "MMM d HH:mm"
        val when_ = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(untilMs))
        return "rate-limited · back $when_"
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val expired = cooldownMap.filterValues { it <= now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { cooldownMap.remove(it) }
            persist()
        }
    }

    private fun persist() {
        runCatching {
            prefs()?.edit()?.putString(KEY_MAP, gson.toJson(cooldownMap.toMap()))?.apply()
        }
    }

    private fun publish() {
        _cooldowns.value = cooldownMap.toMap()
    }
}
