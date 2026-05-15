package com.ai.ui.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ai.data.ModelCooldownStore
import com.ai.model.Settings

/**
 * Snapshot of the three advisory states a (provider, model) pair can
 * carry: a >1h-429 cooldown, a user-blocked entry (incl. the auto-add
 * from the Test all models sweep), or a tier-gated Inaccessible entry
 * (e.g. Together non-serverless). Each state is independent — a model
 * can be in zero, one, two, or all three.
 *
 * The dim treatment is identical for all three: alpha 0.4 on the row,
 * a leading badge (⏳ / 🚫 / 🔒) next to the model name, and a one-line
 * caption below explaining the reason. Rows stay clickable so the
 * user can still pick the model deliberately.
 */
data class ModelAdvisoryState(
    val benchedUntil: Long? = null,
    val blockReason: String? = null,
    val inaccessibleReason: String? = null
) {
    val isDimmed: Boolean
        get() = benchedUntil != null || blockReason != null || inaccessibleReason != null
    val rowAlpha: Float get() = if (isDimmed) 0.4f else 1f
}

/** Per-screen snapshot of the three lookup tables. Hoisted once via
 *  [rememberModelAdvisoryLookup] so each row only pays an O(1) map
 *  lookup, not a re-derive. */
class ModelAdvisoryLookup internal constructor(
    private val cooldowns: Map<String, Long>,
    private val blockedReasons: Map<String, String>,
    private val inaccessibleReasons: Map<String, String>
) {
    fun stateFor(providerId: String, model: String): ModelAdvisoryState {
        val key = "$providerId:$model"
        return ModelAdvisoryState(
            benchedUntil = cooldowns[key]?.takeIf { it > System.currentTimeMillis() },
            blockReason = blockedReasons[key],
            inaccessibleReason = inaccessibleReasons[key]
        )
    }
}

/** Collect [ModelCooldownStore.cooldowns] + derive
 *  [Settings.blockedReasonByKey] + [Settings.inaccessibleReasonByKey]
 *  once, package them into a [ModelAdvisoryLookup] for cheap per-row
 *  access. Recomposition-safe — re-derives when [aiSettings] or the
 *  cooldowns flow change. */
@Composable
fun rememberModelAdvisoryLookup(aiSettings: Settings): ModelAdvisoryLookup {
    val cooldowns by ModelCooldownStore.cooldowns.collectAsState()
    val blockedReasons = remember(aiSettings) { aiSettings.blockedReasonByKey }
    val inaccessibleReasons = remember(aiSettings) { aiSettings.inaccessibleReasonByKey }
    return remember(cooldowns, blockedReasons, inaccessibleReasons) {
        ModelAdvisoryLookup(cooldowns, blockedReasons, inaccessibleReasons)
    }
}

/** Render the three leading badges (⏳ / 🚫 / 🔒) for whichever
 *  advisory states are active. Drop into a Row next to the existing
 *  VisionBadge / WebSearchBadge / ReasoningBadge sequence. */
@Composable
fun ModelAdvisoryBadges(state: ModelAdvisoryState) {
    CooldownBadge(state.benchedUntil != null)
    BlockedBadge(state.blockReason != null)
    InaccessibleBadge(state.inaccessibleReason != null)
}

/** Render the 0..3 caption lines beneath the model name — orange for
 *  cooldown, red for blocked, tertiary for inaccessible. Compose
 *  them sequentially in a Column; this function emits the Text
 *  composables directly, no wrapping. */
@Composable
fun ModelAdvisoryCaptions(
    state: ModelAdvisoryState,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp
) {
    if (state.benchedUntil != null) {
        Text(
            ModelCooldownStore.cooldownCaption(state.benchedUntil),
            fontSize = fontSize, color = AppColors.Orange, maxLines = 1
        )
    }
    if (state.blockReason != null) {
        Text(
            if (state.blockReason.isBlank()) "🚫 Blocked" else "🚫 Blocked: ${state.blockReason}",
            fontSize = fontSize, color = AppColors.Red,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    if (state.inaccessibleReason != null) {
        Text(
            if (state.inaccessibleReason.isBlank()) "🔒 Inaccessible" else "🔒 Inaccessible: ${state.inaccessibleReason}",
            fontSize = fontSize, color = AppColors.TextTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

