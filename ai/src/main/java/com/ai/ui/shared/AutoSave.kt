package com.ai.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Drop-in replacement for a CRUD edit screen's "Save" button: persists
 * [current] automatically while editing instead of on an explicit tap.
 *
 * Renders nothing — it's purely the two effects:
 *  - a [debounceMs]-debounced auto-save that fires [onSave] [debounceMs]
 *    after the user stops changing the form, and
 *  - a final flush in `onDispose`, so the latest state is written when the
 *    user leaves the screen by ANY means — Android back, or tapping a
 *    top-/bottom-bar icon or the title (all of which dispose this composable).
 *
 * [current] is the built entity, or `null` when the form is invalid (a null
 * [current] never saves, so half-filled / invalid edits aren't persisted).
 * The baseline is the first [current] seen, so an unedited form never writes,
 * and an entity is never written twice in a row with identical content.
 *
 * Intended for EDIT mode only; adding a new entity keeps an explicit Create
 * button (the caller renders the button when adding and this when editing).
 */
@Composable
fun <T : Any> AutoSaveOnChange(
    current: T?,
    onSave: (T) -> Unit,
    debounceMs: Long = 350
) {
    val onSaveRef = rememberUpdatedState(onSave)
    val currentRef = rememberUpdatedState(current)
    // Baseline = the first emitted value, so opening and leaving an unedited
    // form writes nothing; updated to each value actually persisted.
    var lastSaved by remember { mutableStateOf(current) }

    LaunchedEffect(current) {
        val c = current
        if (c != null && c != lastSaved) {
            kotlinx.coroutines.delay(debounceMs)
            onSaveRef.value(c)
            lastSaved = c
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val c = currentRef.value
            if (c != null && c != lastSaved) onSaveRef.value(c)
        }
    }
}
