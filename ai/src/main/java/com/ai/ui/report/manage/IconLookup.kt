package com.ai.ui.report.manage
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.copyToClipboard
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelLabel
import com.ai.ui.shared.shareText

/** Single unified detail screen for every icon scope — main report
 *  icon, per-agent icon (3-tier + alt), meta-prompt icon, translation
 *  icon, and the report's detected-language icon. Replaces five
 *  hand-rolled per-scope detail screens. Built around [IconLookupContext]
 *  so each scope's caller passes a small adapter-built record and the
 *  layout / icon-bar / Find-alt button are all shared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconLookupScreen(ctx: IconLookupContext) {
    BackHandler { ctx.onBack() }
    val context = LocalContext.current
    var showManualEdit by remember { mutableStateOf(false) }
    var showSelectIcon by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = ctx.helpTopic,
            title = "Icon lookup",
            subject = ctx.subject,
            onBackClick = ctx.onBack,
            onInfo = ctx.onNavigateToModelInfo,
            onCopy = if (ctx.apiInteraction.isNotBlank())
                ({ copyToClipboard(context, ctx.apiInteraction, "Icon API interaction") })
            else null,
            onShare = if (ctx.apiInteraction.isNotBlank())
                ({ shareText(context, ctx.apiInteraction, ctx.subject) })
            else null,
            onTrace = ctx.traceFile?.let { tf -> { ctx.onNavigateToTraceFile(tf) } }
        )
        // Green subject row — the bundled prompt name (with `_alt`
        // suffix after a Find-alt pick). TitleBar's `subject` param
        // is currently unused; the dedicated HardcodedSubjectRow is
        // what actually paints the green line under the title bar.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // When no dynamic icon has been generated yet — no model
            // recorded AND no API interaction captured — the Model
            // and API interaction cards would both render empty
            // placeholders. Replace them with a single explanatory
            // line so the screen stays useful (Find alternative icons
            // button still works to kick off a generation).
            val noDynamicIconYet = ctx.model.isBlank() && ctx.apiInteraction.isBlank()
            if (noDynamicIconYet) {
                Text(
                    text = "No dynamic icon returned yet",
                    fontSize = 14.sp,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            } else {
                // Model card — provider / model / pricing tier / cumulative cost.
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Model", fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontWeight = FontWeight.Bold)
                        val label = if (ctx.model.isNotBlank())
                            modelLabel(ctx.provider.id, ctx.model)
                        else "(pending)"
                        Text(label, fontSize = 14.sp, color = Color.White)
                        if (ctx.pricingTier.isNotBlank()) {
                            Text(
                                "Pricing tier: ${ctx.pricingTier}",
                                fontSize = 11.sp, color = AppColors.TextTertiary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (ctx.cost > 0.0) {
                            Text(
                                "Cost: ${formatCents(ctx.cost)} ¢",
                                fontSize = 11.sp, color = AppColors.TextTertiary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // API interaction card — plain monospace text, NO markdown.
                // The returned emoji appears small + inline as part of
                // the `[assistant]` turn so the user sees exactly what
                // came back, byte for byte.
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("API interaction", fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp))
                        Text(
                            ctx.apiInteraction.ifBlank { "(no interaction recorded)" },
                            fontSize = 13.sp, color = Color.White, lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Resolved emoji — centred, large. Falls back to ⏳ while
            // running and ❌ on error.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val glyph = when {
                    !ctx.errorMessage.isNullOrBlank() -> "❌"
                    ctx.emoji.isNullOrBlank() -> "⏳"
                    else -> ctx.emoji
                }
                Text(glyph, fontSize = 64.sp, color = Color.White)
            }
            if (!ctx.errorMessage.isNullOrBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Error", fontSize = 11.sp, color = AppColors.Red,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp))
                        Text(ctx.errorMessage, fontSize = 12.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = ctx.onFindAlternativeIcons,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) {
                Text(
                    if (ctx.hasActiveFanOut) "View alternative icons"
                    else "Find alternative icons",
                    maxLines = 1, softWrap = false
                )
            }
            // Manual edit + Select icon — set the icon directly (no fan-out).
            if (ctx.onApplyIcon != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { manualText = ctx.emoji ?: ""; showManualEdit = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Manual edit icon", maxLines = 1, softWrap = false) }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showSelectIcon = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Select icon", maxLines = 1, softWrap = false) }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // "Manual edit icon" — type/paste a glyph directly.
    val apply = ctx.onApplyIcon
    if (apply != null && showManualEdit) {
        AlertDialog(
            onDismissRequest = { showManualEdit = false },
            title = { Text("Manual edit icon") },
            text = {
                OutlinedTextField(
                    value = manualText, onValueChange = { manualText = it },
                    label = { Text("Icon glyph") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = manualText.trim()
                    showManualEdit = false
                    if (t.isNotEmpty()) apply(t)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showManualEdit = false }) { Text("Cancel") }
            }
        )
    }
    // "Select icon" — the same emoji picker as Settings → Default icons.
    if (apply != null && showSelectIcon) {
        ModalBottomSheet(onDismissRequest = { showSelectIcon = false }) {
            AndroidView(
                factory = { c ->
                    androidx.emoji2.emojipicker.EmojiPickerView(c).apply {
                        setOnEmojiPickedListener { picked ->
                            showSelectIcon = false
                            apply(picked.emoji)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(360.dp)
            )
        }
    }
}

/** Self-contained record carrying everything [IconLookupScreen]
 *  needs. Built per-scope by a small adapter (see [ReportScreen.kt]
 *  for the five entry points). All fields are read-only — the screen
 *  is purely a renderer + callback dispatcher. */
data class IconLookupContext(
    /** Help topic id wired into the title bar's ladybug — one per
     *  scope so the user lands on the page that explains *their*
     *  flow. One of: `icon_lookup_main`, `icon_lookup_agent`,
     *  `icon_lookup_meta`, `icon_lookup_translation`,
     *  `icon_lookup_language`, `icon_lookup_pair`. */
    val helpTopic: String,
    /** Green subject row under the title — the bundled prompt name
     *  that produced the displayed emoji (e.g. `main`, `report_2`,
     *  `meta_alt`). Falls back to the source-default name when the
     *  underlying storage has null `iconPromptUsed` (legacy data). */
    val subject: String,
    /** Provider + model that ran the call whose emoji is shown. */
    val provider: AppService,
    val model: String,
    /** Pricing-tier source label (e.g. `litellm`, `manual override`,
     *  `default`) — drives the Cost line's transparency for the user. */
    val pricingTier: String,
    /** Cumulative spend on this icon scope (initial gen + every
     *  Find-alt fan-out call ever fired for it). USD. */
    val cost: Double,
    /** Raw `[user] … [assistant] …` transcript built by the per-source
     *  adapter. Plain text (no markdown) so the embedded emoji shows
     *  at normal text size inline. 2-message for one-shot prompts;
     *  4-message for chat-continuation (agent/fan-out tier 1). */
    val apiInteraction: String,
    /** The resolved single-glyph emoji. Null while the call is
     *  in flight or when it errored — the screen substitutes
     *  ⏳ / ❌. */
    val emoji: String?,
    /** Failure reason when the call errored. Null on success. */
    val errorMessage: String?,
    /** Trace filename for 🐞 deep-link. Null when tracing was off
     *  at call time or no trace was captured. */
    val traceFile: String?,
    /** Drives the bottom button's label flip: "Find alternative icons"
     *  vs. "View alternative icons". True when a per-scope fan-out is
     *  in flight or has results sitting on the live list. */
    val hasActiveFanOut: Boolean,
    val onFindAlternativeIcons: () -> Unit,
    /** Apply a chosen emoji directly to this icon scope — drives the
     *  "Manual edit icon" popup and the "Select icon" emoji picker. Each
     *  adapter wires it to its scope's emoji-direct persist (which bumps
     *  iconRefreshTick so the displayed glyph refreshes). Null hides both
     *  buttons. */
    val onApplyIcon: ((emoji: String) -> Unit)? = null,
    val onNavigateToModelInfo: () -> Unit,
    val onNavigateToTraceFile: (String) -> Unit,
    val onBack: () -> Unit
)

/** Compose a plain `[user] …\n[assistant] …` 2-turn transcript.
 *  Every icon/title/language transcript is one-shot now (the worker
 *  prompts are single-call), so this is the only shape the detail
 *  overlays reconstruct. */
fun buildOneShotApiInteraction(prompt: String, response: String?): String =
    "[user]\n${prompt.trim()}\n\n[assistant]\n${(response ?: "").trim().ifBlank { "(pending)" }}"

