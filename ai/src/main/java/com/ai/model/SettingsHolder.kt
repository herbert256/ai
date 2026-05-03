package com.ai.model

/**
 * Volatile holder for the most-recently-published [Settings] snapshot.
 *
 * AppViewModel writes here on every uiState update so static dispatch
 * helpers — which can't easily thread Settings through the call stack —
 * can still consult capability lookups like
 * [Settings.isReasoningCapable]. The reference may legitimately be null
 * during early process startup or in unit-test contexts; callers should
 * treat that as "no Settings available, fall back to catalog +
 * heuristic" rather than as an error.
 */
object SettingsHolder {
    @Volatile var current: Settings? = null
}
