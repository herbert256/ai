package com.ai.data

import android.content.Context

/**
 * Tiny persistent record of the most recently opened report and the
 * mode (Manage / View) the user opened it in. Backing store: its own
 * SharedPreferences file. Same `object` + `init(context)` shape as
 * [TranslationModeStore] / [ModelCooldownStore].
 *
 * Read by the home Hub's big AI logo: tapping it restores this report
 * in the recorded mode so the user lands back where they were rather
 * than the absolute-latest-by-timestamp default.
 *
 * Written from the navigation layer (AppNavHost) at every site that
 * opens a report — search hits, all-reports browser, hub list rows,
 * etc. Each call passes whether the navigation was to the Manage
 * route or the View route so the recall is fully fidelity-preserving.
 */
object LastReportTracker {
    private const val PREFS = "last_report_tracker"
    private const val KEY_REPORT_ID = "report_id"
    private const val KEY_VIEW = "view_mode"

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist [reportId] as the most-recently-opened, plus whether
     *  it was opened in View ([view] == true) or Manage mode. */
    fun record(reportId: String, view: Boolean) {
        prefs()?.edit()
            ?.putString(KEY_REPORT_ID, reportId)
            ?.putBoolean(KEY_VIEW, view)
            ?.apply()
    }

    /** Most-recently-opened report, paired with its mode. Returns null
     *  when nothing has been recorded yet (fresh install). */
    fun read(): Pair<String, Boolean>? {
        val p = prefs() ?: return null
        val id = p.getString(KEY_REPORT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val view = p.getBoolean(KEY_VIEW, false)
        return id to view
    }

    /** Drop the saved entry — called when the recorded report has been
     *  deleted so the Hub logo doesn't try to restore something that no
     *  longer exists. */
    fun clear() {
        prefs()?.edit()?.clear()?.apply()
    }
}
