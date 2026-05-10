package com.ai.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Returns a state that increments every time the host's
 *  [androidx.lifecycle.Lifecycle] reaches [Lifecycle.State.RESUMED].
 *  Drop into a screen and key your `produceState` / disk read on it
 *  so the data refreshes when the user navigates back to a hub
 *  whose composable was preserved across the trip. */
@Composable
fun resumeRefreshTick(): Int {
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/** Card that starts collapsed — the title row is always visible and
 *  acts as a click target; tapping reveals [content]. */
@Composable
fun CollapsibleCard(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) content()
        }
    }
}

/** App-wide layout choice for combined provider+model labels.
 *  Provided once at the top of the composition tree by AppNavHost
 *  from GeneralSettings; consumed by [modelLabel] and any caller
 *  that wants to honour the user's preference. */
val LocalModelNameLayout = compositionLocalOf {
    com.ai.viewmodel.ModelNameLayout.MODEL_ONLY
}

/** Provided by AppNavHost so any model-name Text in the tree can
 *  jump to the Model Info screen without prop-drilling a callback.
 *  Default no-op covers previews and unit tests. */
val LocalNavigateToModelInfo = compositionLocalOf<(com.ai.data.AppService, String) -> Unit> {
    { _, _ -> }
}

/** When false, [TitleBar] hides its visible "< Back" button and the
 *  screen title left-aligns. The system / gesture back still works
 *  because TitleBar registers its BackHandler independently. */
val LocalShowBackButton = compositionLocalOf { true }

/** When true, screens that show a fixed TitleBar label plus a green
 *  "subject" sub-header (Model Info, Trace detail, Knowledge base,
 *  Translation run, Agent result, …) fold the subject into the
 *  title bar and drop the green line. Default false preserves the
 *  legacy two-row layout. Provided once by AppNavHost from
 *  GeneralSettings.subjectToTitleBar. */
val LocalSubjectToTitleBar = compositionLocalOf { false }

/** Provided by AppNavHost so the title-bar Help icon can navigate
 *  to a help page without prop-drilling a callback. The argument is
 *  the screen-specific topic ID (e.g., "agents", "report_result");
 *  null / blank routes to the compact General Help overview. */
val LocalNavigateToHelp = compositionLocalOf<(String?) -> Unit> { {} }

/** Provided by AppNavHost so the title-bar Home icon can navigate
 *  to the Hub without prop-drilling a callback. Replaces the role
 *  the removed "AI" text-button used to play. */
val LocalNavigateHome = compositionLocalOf<() -> Unit> { {} }

/** Set on every screen that's "deeper" than the AI Report Result
 *  page (overlay screens inside the result page — Edit Prompt /
 *  Title / Models / Parameters / Export / Translation Compare /
 *  Secondary Results / Translation Run / Call / Language picker /
 *  Scope picker / Meta picker / etc., plus the per-report Trace
 *  list / detail routes). Defaults to null; the title-bar 📝
 *  Memo icon renders only when this is non-null. The callback
 *  takes the user back to the active report's result page. */
val LocalNavigateToCurrentReport = compositionLocalOf<(() -> Unit)?> { null }

/** When true, every TitleBar renders only its title (no back arrow,
 *  no action icons) and publishes its icon callbacks to
 *  [LocalBottomIconState]; the single [BottomIconBar] composable at
 *  AppNavHost scope reads from that and renders the same action strip
 *  pinned to the screen bottom. Default false keeps the legacy
 *  icons-on-top-right layout. Driven by GeneralSettings.iconBarAtBottom. */
val LocalIconBarAtBottom = compositionLocalOf { false }

/** Snapshot of the icons the *currently composed* TitleBar would paint
 *  on its right. TitleBar fills this via SideEffect on every
 *  recomposition when bottom-bar mode is on; clears it via
 *  DisposableEffect on screen exit. The single [BottomIconBar] at
 *  AppNavHost scope reads from this so the same per-screen visibility
 *  rules carry through. Null when bottom-bar mode is off — TitleBar
 *  short-circuits the publish path. */
val LocalBottomIconState = compositionLocalOf<MutableState<TitleBarIcons?>?> { null }

/** Captured icon state from a TitleBar — what BottomIconBar needs to
 *  render the same strip the top bar would have rendered. */
data class TitleBarIcons(
    val helpTopic: String?,
    val onBack: (() -> Unit)?,
    val backText: String,
    val onChat: (() -> Unit)?,
    val onInfo: (() -> Unit)?,
    val onReload: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onTrace: (() -> Unit)?,
    val showMemo: Boolean
)

/** Make a model-name Text clickable so tapping it opens the Model
 *  Info screen for [providerService] / [model]. No-op when the
 *  provider can't be resolved or the model is blank. Stack on top
 *  of any existing modifier; existing parent clickables on the
 *  same Row continue to work because Compose merges pointer-input
 *  layers per-element, not by inheritance. */
@Composable
fun Modifier.modelInfoClickable(
    providerService: com.ai.data.AppService?,
    model: String
): Modifier {
    if (providerService == null || model.isBlank()) return this
    val nav = LocalNavigateToModelInfo.current
    return this.clickable { nav(providerService, model) }
}

/** Format a "provider · model" label according to the current
 *  [LocalModelNameLayout]. The default is MODEL_ONLY (just the model
 *  id); PROVIDER_AND_MODEL prepends the provider's display name with
 *  the chosen separator. The separator defaults to " · " — the most
 *  common one across the app — but call sites can override it (e.g.,
 *  to keep " — " or " / " when the layout shows both). */
@Composable
fun modelLabel(
    providerDisplay: String,
    model: String,
    separator: String = " · "
): String {
    val layout = LocalModelNameLayout.current
    return when (layout) {
        com.ai.viewmodel.ModelNameLayout.MODEL_ONLY -> model
        com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL -> "$providerDisplay$separator$model"
    }
}

/**
 * Tiny "vision-capable" badge for model lists. Renders nothing when the
 * model isn't flagged so the row stays compact for the long tail of
 * text-only models.
 */
@Composable
fun VisionBadge(isVisionCapable: Boolean) {
    if (!isVisionCapable) return
    Text(
        text = "👁",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "web-search-tool-capable" badge for model lists — same shape as
 * VisionBadge, parallel data source (Settings.isWebSearchCapable).
 */
@Composable
fun WebSearchBadge(isWebSearchCapable: Boolean) {
    if (!isWebSearchCapable) return
    Text(
        text = "🌐",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "thinking / reasoning_effort capable" badge for model lists —
 * same shape as VisionBadge / WebSearchBadge, parallel data source
 * (Settings.isReasoningCapable). 🧠 matches the per-report
 * reasoning-effort indicator already in use elsewhere.
 */
@Composable
fun ReasoningBadge(isReasoningCapable: Boolean) {
    if (!isReasoningCapable) return
    Text(
        text = "🧠",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Generic title bar used across all screens.
 * Left: optional back button. Right: a strip of six action icons —
 * Home, Reload, Info, Delete, Trace, Help (left → right). Home and
 * Help are always enabled and route through their CompositionLocals;
 * the four middle icons gray out (alpha 0.4f, taps absorbed) when
 * their callback is null.
 *
 * The < button does NOT call [onBackClick] directly — it invokes the
 * host activity's back-press dispatcher, so any [BackHandler] a
 * screen registers (overlay open, drill-in level, edit-cancel) wins
 * just as it does for the system / gesture back. To keep the visible
 * back button working when no other handler is enabled, TitleBar
 * registers its own `BackHandler { onBackClick() }` as the
 * lowest-priority fallback (it composes after any outer
 * BackHandler that was registered earlier in the screen body, but
 * BEFORE any inner BackHandlers tied to nested state). Without this
 * routing the < arrow popped past inner state — e.g. on the Fan out
 * drill-in's L2 it skipped L1 and exited the screen entirely.
 */
@Composable
fun TitleBar(
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    backText: String = "< Back",
    leftContent: (@Composable RowScope.() -> Unit)? = null,
    centered: Boolean = false,
    helpTopic: String? = null,
    onTrace: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onReload: (() -> Unit)? = null,
    onChat: (() -> Unit)? = null,
    /** Applied to the bar's outer Row. Default no-op preserves the
     *  existing convention (most screens wrap the bar in a parent
     *  Column with `.padding(16.dp)` and pay zero pad here).
     *  Screens that DON'T outer-pad — e.g. those whose body content
     *  needs to flow edge-to-edge under the bar — pass
     *  `Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)`
     *  here so the bar still gets the same top breathing room as
     *  the standard pattern. */
    modifier: Modifier = Modifier
) {
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    if (onBackClick != null) {
        androidx.activity.compose.BackHandler { onBackClick() }
    }
    val handleBack: () -> Unit = remember(backDispatcher, onBackClick) {
        {
            when {
                backDispatcher != null -> backDispatcher.onBackPressed()
                onBackClick != null -> onBackClick()
                else -> {}
            }
        }
    }
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val navigateToCurrentReport = LocalNavigateToCurrentReport.current
    if (centered) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AI", fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { navigateHome() }
            )
        }
    } else if (LocalIconBarAtBottom.current) {
        // Bottom-bar mode: top bar renders only the title text. Every
        // icon (and the back arrow) gets published into
        // LocalBottomIconState so the global BottomIconBar paints them.
        // SideEffect on every recomposition keeps the published state
        // fresh; DisposableEffect clears it on screen exit (with an
        // identity check so a racing nav transition doesn't clobber
        // the next screen's just-published state).
        val state = LocalBottomIconState.current
        val showMemo = LocalNavigateToCurrentReport.current != null
        val captured = TitleBarIcons(
            helpTopic = helpTopic,
            onBack = onBackClick,
            backText = backText,
            onChat = onChat,
            onInfo = onInfo,
            onReload = onReload,
            onDelete = onDelete,
            onTrace = onTrace,
            showMemo = showMemo
        )
        if (state != null) {
            SideEffect { state.value = captured }
            DisposableEffect(Unit) {
                onDispose { if (state.value === captured) state.value = null }
            }
        }
        // Bare top bar: just the title, left-aligned.
        val titleStyle = MaterialTheme.typography.titleLarge
        Row(
            modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (title != null) {
                Text(
                    text = title, style = titleStyle, color = Color.White,
                    fontSize = titleStyle.fontSize * 1.25f,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    } else {
        val showBackButton = LocalShowBackButton.current
        val foldSubject = LocalSubjectToTitleBar.current
        val backVisible = onBackClick != null && showBackButton
        val hasLeftSlot = leftContent != null || backVisible
        // When the user has hidden the "< Back" button the title row
        // has more horizontal room and a less crowded look. Scale up
        // the title text by 25 % so the bar takes advantage of that
        // space and reads larger at a glance. Standard back-button
        // mode keeps the original 1× sizing so the layout still fits
        // next to "< Back".
        //
        // Icons get a bigger 1.5× bump in the same "no Back, no
        // folded subject" mode — the action strip on result-phase
        // screens (Reports / Chat / Trace detail) carries the most
        // tap targets and benefits from the larger glyph more than
        // the title text does. Subject-to-title-bar mode is the
        // exception: it folds the page subject (often a long model
        // id or KB name) into the title slot, so that slot is the
        // bottleneck and the icons shouldn't steal width from it —
        // keep the icons at 1× whenever foldSubject is on.
        val scale = if (showBackButton) 1f else 1.25f
        val iconScale = if (showBackButton || foldSubject) 1f else 1.5f
        val titleStyle = MaterialTheme.typography.titleLarge
        Row(
            modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leftContent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    leftContent()
                }
            } else if (backVisible) {
                TextButton(onClick = handleBack) {
                    Text(backText, color = Color.White, fontSize = 16.sp, maxLines = 1, softWrap = false)
                }
            }
            if (title != null) {
                Text(
                    text = title, style = titleStyle, color = Color.White,
                    fontSize = titleStyle.fontSize * scale,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    textAlign = if (hasLeftSlot) TextAlign.Center else TextAlign.Start,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            TitleBarActionStrip(
                onHome = navigateHome,
                onReload = onReload,
                onChat = onChat,
                onInfo = onInfo,
                onDelete = onDelete,
                onTrace = onTrace,
                onHelp = { navigateHelp(helpTopic) },
                onMemo = navigateToCurrentReport,
                scale = iconScale,
                // Tight inter-icon spacing in the 1.5× mode. Without
                // this, scaled-up slots leave 1.5× the air too —
                // the strip looks loose. With it, slots shrink so
                // the glyphs sit nearly shoulder-to-shoulder.
                compactSpacing = iconScale > 1f
            )
        }
    }
}

/** Action strip rendered on the right of every [TitleBar]. Help and
 *  Home are always shown (Help is the rightmost slot — the global
 *  "what does this screen do?" anchor — with Home one over from it);
 *  the conditional slots (Reload / Chat / Info / Delete / Trace /
 *  Memo) only render when their callback is non-null — null means
 *  the icon is omitted entirely so the strip stays tight. */
@Composable
private fun TitleBarActionStrip(
    onHome: () -> Unit,
    onReload: (() -> Unit)?,
    onChat: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onTrace: (() -> Unit)?,
    onHelp: () -> Unit,
    onMemo: (() -> Unit)?,
    scale: Float = 1f,
    /** When true, every icon's slot width shrinks by 6dp (clamped at
     *  16dp minimum) so the strip looks compact instead of airy at
     *  the larger 1.5× icon scale. The glyph itself still scales —
     *  only the surrounding tap-target slop is reduced. */
    compactSpacing: Boolean = false,
    /** Extra horizontal gap inserted between every adjacent icon —
     *  on top of the per-pair Spacers already coded into the strip.
     *  Default 0.dp keeps the dense top-bar layout; the bottom-bar
     *  uses ~6dp so the icons read as separate tap targets instead
     *  of one chunk. */
    extraSpacing: Dp = 0.dp
) {
    fun w(slot: Dp): Dp = if (compactSpacing) (slot - 6.dp).coerceAtLeast(16.dp) else slot
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(extraSpacing)
    ) {
        if (onChat != null) TitleBarIcon("💬", Color.Unspecified, onChat, width = w(28.dp), scale = scale)
        if (onInfo != null) TitleBarIcon("ℹ️", Color.Unspecified, onInfo, width = w(28.dp), scale = scale)
        // 🔄 sits immediately to the left of 🗑 — the reload-vs-
        // discard pair lives together so a user reaching for one
        // doesn't accidentally hit the other from across the strip.
        if (onReload != null) TitleBarIcon("🔄", AppColors.Orange, onReload, width = w(28.dp), scale = scale)
        // 🗑 reads narrow on the leading edge, so a 28dp slot leaves
        // a visible gap before it; tighten to 22dp to bring it
        // closer to the neighbour on its left.
        if (onDelete != null) TitleBarIcon("🗑", AppColors.Red, onDelete, width = w(22.dp), scale = scale)
        // Visual gap between 🗑 Delete and 🐞 Trace — the two 22dp
        // slots butt flush, leaving the destructive trash icon
        // touching the diagnostic bug icon as one chunk. Only fires
        // when both are shown.
        if (onDelete != null && onTrace != null) {
            Spacer(modifier = Modifier.width(2.dp * scale))
        }
        // Trace's 🐞 glyph reads narrower than its trailing space in
        // a 28dp slot, leaving a visible gap before the next icon.
        // Tighten to 22dp so it sits closer to its right neighbour
        // (Help) without getting cramped.
        if (onTrace != null) TitleBarIcon("🐞", Color.Unspecified, onTrace, width = w(22.dp), scale = scale)
        // Safety gap: when 🐞 is hidden, 🗑 lands directly next to
        // 📝 (Memo) or 🏠 (Home). Fat-finger-prevention spacer so the
        // destructive trash icon doesn't sit flush against a
        // navigation icon. 2dp matches the Trash↔Trace gap above.
        if (onDelete != null && onTrace == null) {
            Spacer(modifier = Modifier.width(2.dp * scale))
        }
        // Visual gap between 🐞 Trace and 🏠 Home — the user asked
        // for the pairing specifically. The two 22dp slots butt flush
        // and the emojis read as one chunk despite the colour
        // difference. Only fires when 📝 Memo is absent (otherwise
        // Memo sits between them and the gap doesn't apply).
        if (onTrace != null && onMemo == null) {
            Spacer(modifier = Modifier.width(4.dp * scale))
        }
        // 📝 Memo — "back to the current AI Report's result page".
        // Sits to the left of Home / Help; only renders when
        // [LocalNavigateToCurrentReport] is non-null, i.e. the user
        // is on a screen that's deeper than the result page itself.
        if (onMemo != null) TitleBarIcon("📝", Color.Unspecified, onMemo, width = w(28.dp), scale = scale)
        // 🏠 Home — tightened to 22dp so the gap between it and the
        // rightmost ❓ Help icon shrinks; both glyphs read narrower
        // than the standard 28dp slot.
        TitleBarIcon("🏠", AppColors.Blue, onHome, width = w(22.dp), scale = scale)
        // Help glyph reads narrower than the other emojis, so a
        // standard 28dp slot leaves visible gaps on either side.
        // Tightening further to 14dp pulls it snug against Home on
        // the left. Help is the rightmost slot — the global "what
        // does this screen do?" anchor.
        TitleBarIcon("❓", AppColors.Blue, onHelp, width = w(14.dp), scale = scale)
    }
}

@Composable
private fun TitleBarIcon(
    emoji: String,
    tint: Color,
    onClick: () -> Unit,
    width: Dp = 28.dp,
    scale: Float = 1f
) {
    Box(
        modifier = Modifier.size(width = width * scale, height = 32.dp * scale).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji, fontSize = 16.sp * scale,
            color = if (tint == Color.Unspecified) Color.White else tint
        )
    }
}

/** Fixed-position bottom bar that mirrors the active TitleBar's
 *  action icons + back arrow. Always present (every nav destination,
 *  every screen) when GeneralSettings.iconBarAtBottom is on; the
 *  AppNavHost renders one instance and feeds it the icons published
 *  by whichever TitleBar is currently composed. Falls back to a
 *  dim "no icons" bar during the brief sub-frame between two
 *  screens' nav transitions. */
@Composable
fun BottomIconBar(icons: TitleBarIcons?, modifier: Modifier = Modifier) {
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val navigateToCurrentReport = LocalNavigateToCurrentReport.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 0.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: back-arrow rendered inline (not via
        // TitleBarIcon) so it can hug the screen's left edge with
        // CenterStart alignment and cap its slot height at the
        // right-strip's intrinsic height — keeps the bar short and
        // the glyph flush left.
        val onBack = icons?.onBack
        if (onBack != null) {
            Box(
                modifier = Modifier.size(width = 56.dp, height = 48.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("←", color = Color.White, fontSize = 32.sp,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        // Right side: same TitleBarActionStrip the top bar would
        // render, with the same Home / Help wiring and per-icon
        // null-check rules.
        TitleBarActionStrip(
            onHome = navigateHome,
            onReload = icons?.onReload,
            onChat = icons?.onChat,
            onInfo = icons?.onInfo,
            onDelete = icons?.onDelete,
            onTrace = icons?.onTrace,
            onHelp = { navigateHelp(icons?.helpTopic) },
            onMemo = if (icons?.showMemo == true) navigateToCurrentReport else null,
            scale = 1.5f,
            compactSpacing = false,
            extraSpacing = 2.dp
        )
    }
}

/**
 * Reusable delete confirmation dialog.
 */
@Composable
fun DeleteConfirmationDialog(
    entityType: String,
    entityName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $entityType") },
        text = { Text("Are you sure you want to delete \"$entityName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) }
        }
    )
}

/**
 * Reusable confirmation dialog for the title-bar Reload icon. The
 * confirm button re-fires whatever the screen calls "reload"; the
 * dismiss leaves things alone.
 *
 * Single-call screens can pass [target] for the default copy
 * ("Re-run API call? This will fire a new API call for X and replace
 * the current result."). Multi-call / list screens that want to spell
 * out exactly which rows get re-fired can override [title] and
 * [message] directly — pass [target] = "" in that case.
 */
@Composable
fun ReloadConfirmationDialog(
    target: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Re-run API call?",
    message: String = "This will fire a new API call for $target and replace the current result.",
    confirmLabel: String = "Re-run"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = AppColors.Blue, maxLines = 1, softWrap = false) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) }
        }
    )
}

/**
 * Reusable list item card with title, subtitle, and delete button.
 */
@Composable
fun SettingsListItemCard(
    title: String,
    subtitle: String,
    extraLine: String? = null,
    subtitleColor: Color = AppColors.TextTertiary,
    onClick: () -> Unit,
    /** Null hides the trailing X — used by fixed-list screens (e.g.
     *  Other Internal prompts) where rows are editable but not deletable. */
    onDelete: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, fontSize = 14.sp, color = subtitleColor)
                if (extraLine != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = extraLine, fontSize = 12.sp, color = AppColors.TextDim)
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Text("X", color = AppColors.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Reusable collapsible card with a clickable title header.
 *
 * [helpTopic] — when non-null, an ❓ icon sits between the summary
 * and the chevron. Tapping it routes through [LocalNavigateToHelp]
 * to the full-screen help page for that topic. Tap stays inside
 * the icon — does NOT propagate to the row's expand/collapse
 * handler.
 */
@Composable
fun CollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    summary: String? = null,
    helpTopic: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val navigateHelp = LocalNavigateToHelp.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f)
                )
                if (summary != null && !expanded) {
                    Text(
                        text = summary, style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary, modifier = Modifier.padding(end = 8.dp)
                    )
                }
                if (helpTopic != null) {
                    Text(
                        text = "❓", fontSize = 14.sp, color = AppColors.Blue,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { navigateHelp(helpTopic) }
                    )
                }
                Text(text = if (expanded) "▾" else "▸", color = AppColors.TextTertiary, fontSize = 16.sp)
            }
            if (expanded) { content() }
        }
    }
}

/**
 * Hub navigation card - clickable card with title and description.
 */
@Composable
fun HubCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = AppColors.CardBackground
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, fontSize = 14.sp, color = AppColors.TextTertiary)
        }
    }
}
