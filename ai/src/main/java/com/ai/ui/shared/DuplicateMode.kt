package com.ai.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Captured state for the "duplicate this entity" flow shared across every
 * CRUD edit screen. Each screen owns local field state seeded from
 * `entity`; flipping into [isAddMode] retargets the save path from
 * update→insert without touching the seeded field values. Original entity
 * is left untouched on disk.
 */
data class DuplicateMode(
    /** True when the screen should behave as Add (fresh insert, "Create"
     *  CTA, "Add X" title). Stays true for the rest of the screen's
     *  lifetime once [copyTrigger] is invoked. */
    val isAddMode: Boolean,
    /** Hand directly to `TitleBar(onCopyReport = ...)`. Null on a fresh
     *  Add screen (no original to copy from) and after the user has
     *  already triggered the flip — so the icon is a one-shot per entry,
     *  can't accidentally re-fire. */
    val copyTrigger: (() -> Unit)?
)

/**
 * State + trigger for the 👯 copy-icon on CRUD edit screens. Call from
 * inside the edit Composable. [onDuplicate] runs once when the user taps
 * the icon — mutate the screen's `name` state inside it
 * (e.g. `name = "$name-copy"`), or leave empty for entities keyed by a
 * composite (model id / etc.) where the user picks a fresh key before
 * saving. The helper flips its internal flag and the returned
 * [DuplicateMode] then reports `isAddMode = true` for the rest of the
 * screen's life.
 */
@Composable
fun rememberDuplicateMode(
    isEditingExisting: Boolean,
    onDuplicate: () -> Unit = {}
): DuplicateMode {
    var duplicating by rememberSaveable { mutableStateOf(false) }
    return DuplicateMode(
        isAddMode = !isEditingExisting || duplicating,
        copyTrigger = if (isEditingExisting && !duplicating) {
            { duplicating = true; onDuplicate() }
        } else null
    )
}
