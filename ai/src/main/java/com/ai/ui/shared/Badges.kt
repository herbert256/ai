package com.ai.ui.shared

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tiny per-capability badges for model-list rows. Each renders nothing
 * when its flag is false so a long tail of text-only models stays
 * compact. Extracted from SharedComponents.kt.
 */

/** "Vision-capable" badge. */
@Composable
fun VisionBadge(isVisionCapable: Boolean) {
    if (!isVisionCapable) return
    Text(text = "👁", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}

/** "Web-search-tool-capable" badge (Settings.isWebSearchCapable). */
@Composable
fun WebSearchBadge(isWebSearchCapable: Boolean) {
    if (!isWebSearchCapable) return
    Text(text = "🌐", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}

/** "Thinking / reasoning_effort capable" badge (Settings.isReasoningCapable). */
@Composable
fun ReasoningBadge(isReasoningCapable: Boolean) {
    if (!isReasoningCapable) return
    Text(text = "🧠", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}

/** "On a >1h-429 cooldown" badge. */
@Composable
fun CooldownBadge(onCooldown: Boolean) {
    if (!onCooldown) return
    Text(text = "⏳", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}

/** "User-blocked" badge. */
@Composable
fun BlockedBadge(isBlocked: Boolean) {
    if (!isBlocked) return
    Text(text = "🚫", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}

/** "Tier-gated / inaccessible" badge — selecting will usually fail
 *  unless the user has dedicated capacity on that provider. */
@Composable
fun InaccessibleBadge(isInaccessible: Boolean) {
    if (!isInaccessible) return
    Text(text = "🔒", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}

/** "Not a chat model" badge (image / TTS / STT / moderation / classify /
 *  OCR). Selecting one for a chat flow fails at runtime. */
@Composable
fun NonTestableBadge(isNonTestable: Boolean) {
    if (!isNonTestable) return
    Text(text = "🖼️", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
}
