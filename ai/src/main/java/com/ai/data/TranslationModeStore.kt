package com.ai.data

import android.content.Context
import com.ai.viewmodel.ReportViewModel.TranslationMode

/**
 * Per-runId persistence for the translation runner's cost-vs-speed
 * mode toggle on the L1 screen. Backed by its own SharedPreferences
 * file so the entry sticks across app restarts and the resumed run
 * lands in the same mode the user picked before the kill.
 *
 * Same `object` + `init(context)` shape as [ModelCooldownStore] /
 * [PromptCache] — read & write on every toggle flip, no
 * StateFlow needed since the in-memory copy lives on
 * `_translationRuns[runId].mode` and updates reactively through
 * the ViewModel's existing flow.
 *
 * COST is the default — a missing entry resolves to it via
 * `get(...) ?: TranslationMode.COST` at every caller, so writing
 * COST cleans the entry instead of bloating the file.
 */
object TranslationModeStore {
    private const val PREFS = "translation_modes"

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns the user's saved mode for [runId] or null when no
     *  override was ever written (caller defaults to
     *  [TranslationMode.COST]). Tolerant of legacy / unrecognised
     *  values — returns null on any decode hiccup so the run lands
     *  in the COST baseline. */
    fun get(runId: String): TranslationMode? {
        val raw = prefs()?.getString(runId, null) ?: return null
        return runCatching { TranslationMode.valueOf(raw) }.getOrNull()
    }

    /** Persist [mode] for [runId]. Storing the default ([COST])
     *  removes the entry instead of writing it, keeping the prefs
     *  file slim for the common case where most runs stay on the
     *  default. */
    fun set(runId: String, mode: TranslationMode) {
        val p = prefs() ?: return
        p.edit().run {
            if (mode == TranslationMode.COST) remove(runId) else putString(runId, mode.name)
            apply()
        }
    }

    /** Drop the saved mode for [runId]. Called from
     *  `deleteTranslationRun` so a deleted run doesn't leave its
     *  entry behind. */
    fun remove(runId: String) {
        prefs()?.edit()?.remove(runId)?.apply()
    }
}
