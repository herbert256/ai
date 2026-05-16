package com.ai.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.SecondaryKind
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.HardcodedSubjectRow
import com.ai.ui.shared.TitleBar

/**
 * First-version "View AI Report" page — the home for every "look at
 * this report" affordance that used to live under the cramped
 * Row 1 "View" CompactButton. Reached from the bottom-bar ℹ️ icon
 * on the result screen.
 *
 * Layout: two tile sections rendered side-by-side via
 * [LazyVerticalGrid] (adaptive cells, ~150 dp min). The Documents
 * section always renders the always-on views (Prompt / Costs /
 * Reports / HTML / Log + Icons when the per-model icon chain is
 * enabled). The Computed section renders one tile per conditional
 * kind that has at least one row, with a small count badge.
 *
 * Tap behaviour:
 *  - A Documents tile fires its handler then pops the screen.
 *  - A Computed tile with exactly one item opens that item directly.
 *  - A Computed tile with ≥ 2 items toggles an inline list below
 *    the grid; tapping an item opens its detail.
 *
 * All destinations are the existing full-screen Composables wired
 * to the same handlers the old "View" Row 2 buttons fired — the
 * tile grid is purely a launcher. Reverting to the old UI is a
 * one-line re-add of the [CompactButton] in [GenerationPhase].
 */
@Composable
internal fun ViewAiReportScreen(
    promptTitle: String,
    reportIcon: String?,
    perModelIconGenEnabled: Boolean,
    everyItems: Map<String, List<EveryItem>>,
    /** Internal prompts the report can run — used to resolve a
     *  meta row's [com.ai.model.InternalPrompt] from its name so the
     *  tile can render the cached per-prompt emoji instead of the
     *  static 🧠 fallback. */
    internalPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** Master toggle from GeneralSettings — when off, every meta
     *  tile keeps the static 🧠 even if a cached emoji exists. */
    useInternalPromptsIcons: Boolean = false,
    /** Re-key for the cache lookup — bumped whenever an
     *  internal-prompt icon-gen call lands, so the tile recomposes
     *  with the freshly-cached emoji without a manual subscribe. */
    iconRefreshTick: Int = 0,
    /** Cold-cache trigger — fired when a meta tile's prompt has no
     *  cached emoji yet. Same one-shot generation path the result
     *  list's per-row emoji uses. */
    onMissingPromptIcon: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    /** Cold-cache trigger for the per-language `translation_icon`
     *  emoji. Fired when a meta tile represents a translated meta
     *  row whose [EveryItem.targetLanguage] has no cached emoji.
     *  Same generation path the result-list translation row uses. */
    onMissingTranslationIcon: (String) -> Unit = { _ -> },
    /** True when ANY persisted moderation row on this report has
     *  AT LEAST one fired category. Flips the moderation tile's
     *  accent to red (flag set somewhere) vs the default green
     *  (every run came back clean). The 🚩 emoji is rendered on
     *  both — the tile colour carries the verdict. */
    moderationFlagged: Boolean = false,
    onViewPrompt: () -> Unit,
    onViewCosts: () -> Unit,
    onViewReports: () -> Unit,
    onOpenHtmlPreview: () -> Unit,
    onViewLog: () -> Unit,
    onViewIcons: () -> Unit,
    onBack: () -> Unit
) {
    // Inline expansion target — which Computed kind's items list is
    // open below the grid. Null = nothing expanded. rememberSaveable
    // so a rotation doesn't snap the list shut mid-read.
    var expandedKind by rememberSaveable { mutableStateOf<String?>(null) }

    // Documents tiles — fixed set, Icons only when the per-model
    // icon chain is enabled in Settings. The tile only OPENS the
    // destination; the View screen itself stays in the back-stack
    // underneath so Android-back from the destination falls back
    // to the View grid rather than the report page.
    val docTiles = remember(perModelIconGenEnabled, onViewPrompt, onViewCosts, onViewReports, onOpenHtmlPreview, onViewLog, onViewIcons) {
        buildList {
            add(ViewTile("Prompt", "📝", AppColors.Purple) { onViewPrompt() })
            add(ViewTile("Reports", "📊", AppColors.Blue) { onViewReports() })
            add(ViewTile("Costs", "💰", AppColors.Yellow) { onViewCosts() })
            add(ViewTile("HTML", "🌐", AppColors.Indigo) { onOpenHtmlPreview() })
            add(ViewTile("Log", "📜", AppColors.Brown) { onViewLog() })
            if (perModelIconGenEnabled) {
                add(ViewTile("Icons", "🖼", AppColors.Orange) { onViewIcons() })
            }
        }
    }

    // Meta is special-cased: one tile per persisted Meta row (e.g.
    // a Compare run, a Summary run) so the user can jump straight
    // into a specific result instead of going through an
    // aggregated "Meta (N)" tile and a follow-up picker. Each
    // tile's label = the meta prompt name; tap opens that row's
    // detail directly. The tile's emoji is the cached per-prompt
    // icon (the same one rendered next to the meta name on the
    // result list), falling back to 🧠 while the cache is cold or
    // when the user has turned the master icon toggle off.
    val metaTiles = remember(everyItems, internalPrompts, useInternalPromptsIcons, iconRefreshTick, onBack) {
        everyItems["meta"].orEmpty().map { item ->
            val prompt = item.prompt
            val lang = item.targetLanguage
            val cached = when {
                // Translation pass — use the per-language
                // `translation_icon` emoji instead of the prompt's
                // own. Cold cache kicks off generation via
                // onMissingTranslationIcon (same path the result
                // list's translation row uses).
                useInternalPromptsIcons && !lang.isNullOrBlank() -> {
                    val emoji = com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                    if (emoji == null) onMissingTranslationIcon(lang)
                    emoji
                }
                // Standard meta path — per-prompt emoji.
                useInternalPromptsIcons && prompt != null && prompt.name.isNotBlank() -> {
                    val emoji = com.ai.data.InternalPromptIconCache.get(prompt.name, prompt.title)
                    if (emoji == null) onMissingPromptIcon(prompt)
                    emoji
                }
                else -> null
            }
            ViewTile(item.label, cached ?: "🧠", AppColors.Purple) {
                item.open()
            }
        }
    }

    // Other computed kinds — one tile per kind. Tap with N=1
    // opens the only item; N≥2 flips [expandedKind] which renders
    // an inline list below the tiles. (Meta is excluded; it's
    // handled by [metaTiles] above.)
    data class ComputedTile(val key: String, val tile: ViewTile, val items: List<EveryItem>)
    val computedTiles = remember(everyItems, moderationFlagged) {
        // Moderation accent flips red ↔ green based on whether any
        // moderation row on this report flagged anything; the 🚩
        // emoji is the same either way so the flag motif stays
        // consistent across both states.
        val moderationColor = if (moderationFlagged) AppColors.Red else AppColors.Green
        val specs = listOf(
            ComputedSpec("rerank", "Rerank", "🏆", AppColors.Yellow),
            ComputedSpec("moderation", "Moderation", "🚩", moderationColor),
            ComputedSpec("fan_out", "Fan-out", "🌀", AppColors.Indigo),
            ComputedSpec("fan_in", "Fan-in", "🪢", AppColors.Green),
            ComputedSpec("fan-in-model", "Fan-in-model", "🧩", AppColors.Blue),
            ComputedSpec("translate", "Translate", "🌍", AppColors.Orange)
        )
        specs.mapNotNull { s ->
            val items = everyItems[s.key].orEmpty()
            if (items.isEmpty()) null
            else ComputedTile(
                key = s.key,
                items = items,
                tile = ViewTile(s.label, s.emoji, s.color, count = items.size) {
                    when (items.size) {
                        1 -> items[0].open()
                        else -> { expandedKind = if (expandedKind == s.key) null else s.key }
                    }
                }
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "view_ai_report",
            title = "AI Report - view",
            reportIcon = reportIcon,
            onBackClick = onBack
        )
        HardcodedSubjectRow(promptTitle)

        // Body fills the remaining vertical space between the
        // green subject row and the bottom icons bar — without
        // weight(1f) the body would measure to content height
        // and leave an empty gap below it on tall screens.
        // verticalScroll is here as a safety net for very small
        // displays / accessibility scaling; on a normal phone
        // every tile fits without scrolling.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            // Always-on tiles + per-meta-item tiles + other grouped
            // computed tiles all flow together as one continuous
            // grid. The meta items get an entry each per the user
            // request; the other computed kinds stay aggregated
            // with their count badge.
            TileFlow(docTiles + metaTiles + computedTiles.map { it.tile })

            // Inline expansion — full-width card listing each
            // item for the active non-meta computed kind (rerank /
            // fan_out / fan_in / fan-in-model / translate with
            // N≥2 items). Anchored under the grid so the user
            // keeps the rest of the layout in view.
            val open = expandedKind
            if (open != null) {
                val active = computedTiles.firstOrNull { it.key == open }
                if (active != null && active.items.size >= 2) {
                    ExpandedKindCard(
                        title = active.tile.label,
                        items = active.items,
                        onItemClick = { item ->
                            expandedKind = null
                            item.open()
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Visual descriptor for one launcher tile. */
private data class ViewTile(
    val label: String,
    val emoji: String,
    val accent: Color,
    val count: Int = 0,
    val onClick: () -> Unit
)

/** Constructor descriptor for the Computed section's six possible
 *  kinds. Kept separate from [ViewTile] so the binding step can
 *  attach the `items` list + the lambda once. */
private data class ComputedSpec(
    val key: String,
    val label: String,
    val emoji: String,
    val color: Color
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = AppColors.Blue,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileFlow(tiles: List<ViewTile>) {
    // Every tile is rendered at the SAME fixed width regardless of
    // how many sit in the last row — previously each tile inside
    // FlowRow used weight(1f), which spread the trailing row's
    // tiles across the full container width and made them visibly
    // larger than tiles in fully-packed rows. Compute the per-tile
    // width once from the container's maxWidth + a target column
    // count (2 on a typical phone, 3 on wider screens), then hand
    // that fixed width to every TileCard.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 10.dp
        val cols = 3
        // Subtract a sub-pixel safety margin from the ideal tile
        // width: pixel rounding (Dp → px) on an exact-fit 3-up
        // layout can land 1 px over the container on certain
        // densities, which makes FlowRow wrap to 2 tiles per row
        // even though we asked for 3. 0.5 dp of slack absorbs that
        // rounding without being visible.
        val tileWidth = ((maxWidth - spacing * (cols - 1)) / cols) - 0.5.dp
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = cols
        ) {
            tiles.forEach { tile ->
                Box(modifier = Modifier.width(tileWidth)) { TileCard(tile) }
            }
        }
    }
}

@Composable
private fun TileCard(tile: ViewTile) {
    val accent = tile.accent
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1.05f).clickable(onClick = tile.onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(
                    accent.copy(alpha = 0.55f),
                    accent.copy(alpha = 0.18f)
                )
            )
        )) {
            // Count badge — top-right, only when N ≥ 2. A single-item
            // kind opens that item directly on tap (no chooser
            // expansion), so a "1" badge would tell the user nothing
            // they can't already see from the tile itself.
            if (tile.count >= 2) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tile.count.toString(),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(tile.emoji, fontSize = 36.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    tile.label, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExpandedKindCard(
    title: String,
    items: List<EveryItem>,
    onItemClick: (EveryItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title, color = AppColors.Blue,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            items.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onItemClick(item) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.label, color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("›", color = AppColors.TextTertiary, fontSize = 18.sp)
                }
            }
        }
    }
}
