package com.ai.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Context
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
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
    reportId: String,
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
    /** Receives the View screen's currently-selected language so the
     *  opened sub-screen can lock itself to that language. null = no
     *  force; "" = force Original; non-empty = displayName. */
    onViewPrompt: (String?) -> Unit,
    onViewCosts: () -> Unit,
    onViewReports: (String?) -> Unit,
    onOpenHtmlPreview: () -> Unit,
    onViewLog: () -> Unit,
    onViewIcons: () -> Unit,
    /** Open the API trace list filtered to this report — mirrors
     *  the 🐞 icon on the result-page title bar. */
    onViewTrace: () -> Unit,
    onBack: () -> Unit
) {
    // Inline expansion target — which Computed kind's items list is
    // open below the grid. Null = nothing expanded. rememberSaveable
    // so a rotation doesn't snap the list shut mid-read.
    var expandedKind by rememberSaveable { mutableStateOf<String?>(null) }

    // Persisted tile-order — survives reports / restarts. Stored as
    // a comma-separated list of tile identifiers under SharedPreferences
    // 'view_screen_prefs'. Tiles whose id isn't in the list fall to
    // the end of the grid in their default declaration order; user
    // reorders patch the persisted list keeping non-current tiles
    // in their previous relative positions.
    val viewPrefsCtx = LocalContext.current
    val tileOrderPrefs = remember { viewPrefsCtx.getSharedPreferences("view_screen_prefs", Context.MODE_PRIVATE) }
    var savedOrder by remember {
        mutableStateOf(loadTileOrder(tileOrderPrefs))
    }

    // TRANSLATE secondaries on this report — drives the language
    // picker at the top of the View screen. When the user taps a
    // tile we forward the active language to the opened sub-screen
    // as a lock, and that sub-screen suppresses its own picker.
    // Loaded together with the report.languageIcon so the Original
    // tab can render the detected source-language emoji.
    data class TranslatesLoad(
        val list: List<com.ai.data.SecondaryResult>,
        val originalIcon: String?
    )
    val translatesState = androidx.compose.runtime.produceState(
        initialValue = TranslatesLoad(emptyList(), null), reportId
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val list = com.ai.data.SecondaryResultStorage
                .listForReport(viewPrefsCtx, reportId, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
            val icon = com.ai.data.ReportStorage.getReport(viewPrefsCtx, reportId)?.languageIcon
            TranslatesLoad(list, icon)
        }
    }
    val translates = translatesState.value.list
    val originalLanguageIcon = translatesState.value.originalIcon
    val viewLangTabs = remember(translates) { buildLangTabs(translates) }
    var selectedViewLangKey by rememberSaveable(reportId) { mutableStateOf(LangTab.ORIGINAL_KEY) }
    androidx.compose.runtime.LaunchedEffect(viewLangTabs) {
        if (viewLangTabs.none { it.key == selectedViewLangKey }) {
            selectedViewLangKey = LangTab.ORIGINAL_KEY
        }
    }
    // Derived from the selected tab; a State holder so the cached
    // tile onClick lambdas read the latest value at click time
    // without invalidating `remember(docTiles)`.
    val currentLanguageState = remember { mutableStateOf<String?>("") }
    androidx.compose.runtime.LaunchedEffect(selectedViewLangKey, viewLangTabs) {
        currentLanguageState.value = if (selectedViewLangKey == LangTab.ORIGINAL_KEY) ""
            else viewLangTabs.firstOrNull { it.key == selectedViewLangKey }?.displayName ?: ""
    }

    // Documents tiles — fixed set, Icons only when the per-model
    // icon chain is enabled in Settings. The tile only OPENS the
    // destination; the View screen itself stays in the back-stack
    // underneath so Android-back from the destination falls back
    // to the View grid rather than the report page.
    val docTiles = remember(perModelIconGenEnabled, onViewPrompt, onViewCosts, onViewReports, onOpenHtmlPreview, onViewLog, onViewIcons, onViewTrace) {
        buildList {
            add(IdentifiedTile("doc:Prompt", ViewTile("Prompt", "📝", AppColors.Purple) { onViewPrompt(currentLanguageState.value) }))
            add(IdentifiedTile("doc:Reports", ViewTile("Reports", "📊", AppColors.Blue) { onViewReports(currentLanguageState.value) }))
            add(IdentifiedTile("doc:Costs", ViewTile("Costs", "💰", AppColors.Yellow) { onViewCosts() }))
            add(IdentifiedTile("doc:HTML", ViewTile("HTML", "🌐", AppColors.Indigo) { onOpenHtmlPreview() }))
            add(IdentifiedTile("doc:Log", ViewTile("Log", "📜", AppColors.Brown) { onViewLog() }))
            // 🐞 mirrors the title-bar trace icon — opens the API
            // trace list pre-filtered to this report.
            add(IdentifiedTile("doc:Trace", ViewTile("Trace", "🐞", AppColors.Red) { onViewTrace() }))
            if (perModelIconGenEnabled) {
                add(IdentifiedTile("doc:Icons", ViewTile("Icons", "🖼", AppColors.Orange) { onViewIcons() }))
            }
        }
    }

    // Meta is special-cased: one tile per persisted Meta row (e.g.
    // a Compare run, a Summary run) so the user can jump straight
    // into a specific result instead of going through an
    // aggregated "Meta (N)" tile and a follow-up picker. Each
    // tile's label = the meta prompt name; tap opens that row's
    // detail directly.
    //
    // Emoji rendering:
    //   - Primary = cached per-prompt emoji (falls back to 🧠 while
    //     the prompt-icon cache is cold or the master toggle is off).
    //   - Secondary (translated rows only) = the per-language
    //     `translation_icon` emoji, rendered NEXT TO the primary
    //     rather than replacing it — the meta's own icon stays
    //     visible so the user can still tell which kind of meta it
    //     is at a glance. Both icons render smaller when paired so
    //     they fit the same tile real estate as a single icon.
    val metaTiles = remember(everyItems, internalPrompts, useInternalPromptsIcons, iconRefreshTick, onBack) {
        everyItems["meta"].orEmpty().map { item ->
            val prompt = item.prompt
            val lang = item.targetLanguage
            val promptEmoji = if (useInternalPromptsIcons && prompt != null && prompt.name.isNotBlank()) {
                val e = com.ai.data.InternalPromptIconCache.get(prompt.name, prompt.title)
                if (e == null) onMissingPromptIcon(prompt)
                e
            } else null
            val translationEmoji = if (useInternalPromptsIcons && !lang.isNullOrBlank()) {
                val e = com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                if (e == null) onMissingTranslationIcon(lang)
                e
            } else null
            IdentifiedTile(
                id = "meta:${item.label}",
                tile = ViewTile(
                    label = item.label,
                    emoji = promptEmoji ?: "🧠",
                    accent = AppColors.Purple,
                    secondaryEmoji = translationEmoji,
                    onClick = { item.open(currentLanguageState.value) }
                )
            )
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
                        1 -> items[0].open(currentLanguageState.value)
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
            title = "Report - view",
            // Tap title → flip back to "Report - manage" (same as
            // back). Pairs with onTitleClick on Report - manage so
            // the two screens toggle from the title text.
            onTitleClick = onBack,
            reportIcon = reportIcon,
            onBackClick = onBack
        )
        // Grid vs list mode — toggled by the icon on the right of the
        // green subject row. The icon shown is always the OTHER
        // mode's emblem (☰ in grid mode → switch to list; ⊞ in list
        // mode → switch to grid). rememberSaveable so the mode
        // sticks across navigation, not config-change-only.
        var viewMode by rememberSaveable { mutableStateOf("grid") }
        HardcodedSubjectRow(
            text = promptTitle,
            trailing = {
                Text(
                    text = if (viewMode == "grid") "☰" else "⊞",
                    fontSize = 28.sp,
                    color = AppColors.Blue,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { viewMode = if (viewMode == "grid") "list" else "grid" }
                )
            }
        )

        // One picker for the whole View screen; tile clicks below
        // forward the active language to the opened sub-screen.
        // Hidden when no translations exist (single-language report).
        if (viewLangTabs.size > 1) {
            LanguagePickerRow(
                viewLangTabs, selectedViewLangKey,
                onSelect = { selectedViewLangKey = it },
                useIcons = true,
                originalIcon = originalLanguageIcon
            )
        }

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
            // with their count badge. Each carries a stable
            // identifier so the persisted tile order survives
            // per-report variability.
            val combinedTiles = docTiles + metaTiles +
                computedTiles.map { IdentifiedTile("computed:${it.key}", it.tile) }
            val sortedTiles = remember(combinedTiles, savedOrder) {
                val rankOf = savedOrder.withIndex().associate { it.value to it.index }
                combinedTiles.sortedBy { rankOf[it.id] ?: Int.MAX_VALUE }
            }
            if (viewMode == "list") {
                ListTileColumn(items = sortedTiles)
            } else {
                ReorderableTileFlow(
                    items = sortedTiles,
                    onReorder = { fromId, toId ->
                        val current = sortedTiles.map { it.id }.toMutableList()
                        val fromIdx = current.indexOf(fromId)
                        val toIdx = current.indexOf(toId)
                        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                            current.removeAt(fromIdx)
                            current.add(toIdx, fromId)
                            // Patch persisted: replace current-visible
                            // segment with the new local order, keep
                            // any non-current ids (from other reports)
                            // in their previous relative positions at
                            // the tail.
                            val currentSet = current.toSet()
                            val newSaved = current + savedOrder.filter { it !in currentSet }
                            savedOrder = newSaved
                            tileOrderPrefs.edit()
                                .putString("tile_order", newSaved.joinToString(","))
                                .apply()
                        }
                    }
                )
            }

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
                            item.open(currentLanguageState.value)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** A tile + its stable identifier — used to map the on-screen
 *  tile back to a persisted-order entry. Identifier format:
 *  `doc:<label>` for always-on Documents tiles, `meta:<promptName>`
 *  for individual Meta items, `computed:<key>` for the aggregated
 *  Computed kinds (rerank, moderation, fan_out, fan_in,
 *  fan-in-model, translate). */
private data class IdentifiedTile(val id: String, val tile: ViewTile)

/** Visual descriptor for one launcher tile. */
private data class ViewTile(
    val label: String,
    val emoji: String,
    val accent: Color,
    val count: Int = 0,
    /** Optional second emoji shown next to [emoji] (used by meta
     *  tiles that represent a translated row — the meta prompt's
     *  own icon on the left, the translation's per-language icon
     *  on the right). Null on every other tile. */
    val secondaryEmoji: String? = null,
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

/** Read the persisted comma-separated tile-order list from
 *  SharedPreferences. Blank / missing → empty list (every tile
 *  falls back to its declaration order). */
private fun loadTileOrder(prefs: android.content.SharedPreferences): List<String> =
    prefs.getString("tile_order", null)
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()

/** Reorderable tile grid. Identical visual layout to [TileFlow]
 *  (3 cols, fixed tile width, 10 dp spacing) plus per-tile
 *  long-press-and-drag handling. Short taps still fire the tile's
 *  onClick; only long-press (~500 ms) starts a drag.
 *
 *  Drop target = whichever tile's recorded layout rect contains
 *  the dragged tile's translated center point on release. When the
 *  drop hits a different tile, [onReorder] fires with the source
 *  and target identifiers; the parent computes the new ordering
 *  and updates the persisted state. Tiles other than the dragged
 *  one stay in place during the drag — no slide animation in v1. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReorderableTileFlow(
    items: List<IdentifiedTile>,
    onReorder: (fromId: String, toId: String) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 10.dp
        val cols = 3
        val tileWidth = ((maxWidth - spacing * (cols - 1)) / cols) - 0.5.dp

        var draggedId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        val positions = remember { mutableStateMapOf<String, Rect>() }
        val haptic = LocalHapticFeedback.current

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = cols
        ) {
            items.forEach { item ->
                val id = item.id
                val isDragged = draggedId == id
                Box(
                    modifier = Modifier
                        .width(tileWidth)
                        .onGloballyPositioned { coords ->
                            positions[id] = coords.boundsInParent()
                        }
                        .then(
                            if (isDragged) Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                }
                            else Modifier
                        )
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedId = id
                                    dragOffset = Offset.Zero
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragOffset += drag
                                },
                                onDragEnd = {
                                    val src = draggedId
                                    val srcRect = src?.let { positions[it] }
                                    if (src != null && srcRect != null) {
                                        val dropPoint = srcRect.center + dragOffset
                                        val dstId = positions.entries
                                            .firstOrNull { it.value.contains(dropPoint) }
                                            ?.key
                                        if (dstId != null && dstId != src) {
                                            onReorder(src, dstId)
                                        }
                                    }
                                    draggedId = null
                                    dragOffset = Offset.Zero
                                },
                                onDragCancel = {
                                    draggedId = null
                                    dragOffset = Offset.Zero
                                }
                            )
                        }
                ) { TileCard(item.tile) }
            }
        }
    }
}

/** Compact one-row-per-tile list view — the alternate rendering
 *  toggled from the subject-row icon. Each row carries the tile's
 *  accent as a coloured emoji on the left, label in the middle, the
 *  count badge (when N≥2) and a chevron on the right; the whole row
 *  is clickable and fires the same onClick the grid tile would. No
 *  drag-reorder in list mode (reorder lives in grid mode only). */
@Composable
private fun ListTileColumn(items: List<IdentifiedTile>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                val tile = item.tile
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(onClick = tile.onClick)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tile.emoji, fontSize = 22.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        tile.label, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (tile.count >= 2) {
                        Box(
                            modifier = Modifier.padding(end = 8.dp)
                                .size(22.dp).clip(CircleShape)
                                .background(tile.accent.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tile.count.toString(),
                                color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text("›", color = AppColors.TextTertiary, fontSize = 18.sp)
                }
            }
        }
    }
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
                // Single emoji → bigger (36 sp). Paired emojis →
                // smaller (30 sp) so both fit the same tile real
                // estate as a single icon. Spacing kept tight so the
                // pair reads as one composite glyph.
                if (tile.secondaryEmoji != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(tile.emoji, fontSize = 30.sp)
                        Text(tile.secondaryEmoji, fontSize = 30.sp)
                    }
                } else {
                    Text(tile.emoji, fontSize = 36.sp)
                }
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
