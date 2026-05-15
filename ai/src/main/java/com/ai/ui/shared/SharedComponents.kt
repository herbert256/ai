package com.ai.ui.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R

/** Horizontal swipe-to-navigate, the pattern first introduced on the
 *  Model response screen: swipe **left** for the next item, **right**
 *  for the previous one. Uses [detectHorizontalDragGestures], which
 *  only consumes events after horizontal touch slop — so a vertical
 *  scroll inside the gestured region keeps working untouched.
 *
 *  [key1] / [key2] feed [pointerInput]'s keys so the lambdas always
 *  close over fresh state when the current item or its surrounding
 *  list changes. Bound checks are the caller's responsibility — the
 *  callbacks should no-op at list ends (the buttons they replace
 *  were disabled there). */
fun Modifier.horizontalSwipeNavigation(
    key1: Any?,
    key2: Any? = Unit,
    thresholdDp: Dp = 60.dp,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = this.composed {
    val thresholdPx = with(LocalDensity.current) { thresholdDp.toPx() }
    pointerInput(key1, key2) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
                when {
                    totalDrag > thresholdPx -> onSwipeRight()
                    totalDrag < -thresholdPx -> onSwipeLeft()
                }
            },
            onDragCancel = { totalDrag = 0f }
        ) { _, dragAmount -> totalDrag += dragAmount }
    }
}

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

/** Spinning ⏳ glyph. Used wherever a screen wants to convey "this
 *  thing is in flight" without the visual weight of a full
 *  CircularProgressIndicator — secondary row status columns, the
 *  hub "X reports running" pill, the icon-gen row on the cost table,
 *  the Refresh-all step list, the per-row indicator on the
 *  Translation run detail screen, etc. Single canonical animation
 *  spec (1500 ms / linear / infinite) so the cadence stays uniform
 *  across the app. [modifier] composes BEFORE the rotation so
 *  caller padding etc. is unaffected by the spin. */
@Composable
fun AnimatedHourglass(
    fontSize: TextUnit = 12.sp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "hourglass")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
        label = "hourglass-rotation"
    )
    Text(text = "⏳", fontSize = fontSize, modifier = modifier.rotate(angle))
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

/** Variant of [CollapsibleCard] where the open/closed state lives in
 *  the caller — enables accordion behaviour: parent decides which card
 *  is open and only one can be open at a time. Same visual shape as
 *  the unmodified [CollapsibleCard]. */
@Composable
fun ControlledCollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
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

/** Master switch for the per-report icon-gen feature, mirrored from
 *  GeneralSettings.iconGenEnabled. Provided once by AppNavHost so
 *  every screen that renders a report-icon prefix (hub Existing
 *  reports, History, search hits, picker rows, the result-page icon
 *  row, the leftmost title-bar icon, the 📝 memo icon) can short-
 *  circuit when false. Default true keeps the feature live. */
val LocalIconGenEnabled = compositionLocalOf { true }

/** Resolved per-report emoji propagated to every TitleBar inside a
 *  report-scoped composition tree. Provided by ReportsScreen at every
 *  inline overlay's CompositionLocalProvider so picker / viewer / etc.
 *  composables that don't have the [com.ai.data.Report] in scope can
 *  still render the report icon as the leftmost glyph in their
 *  TitleBar. TitleBar uses this as a fallback when no explicit
 *  `reportIcon` parameter is passed. Default null = not on a
 *  report-scoped screen. */
val LocalReportIcon = compositionLocalOf<String?> { null }

/** Title of the active AI Report. Provided by ReportsScreen at every
 *  inline overlay's CompositionLocalProvider, alongside [LocalReportIcon].
 *  TitleBar reads this as a subject fallback in BOTH mode when the
 *  caller passes a hardcoded title but no per-screen subject — the
 *  bar then renders the report's title on the left and the screen's
 *  fixed title on the right, so the user always knows which report
 *  they're inside even on deep sub-screens whose subject would
 *  otherwise be blank. Null = not on a report-scoped screen. */
val LocalReportTitle = compositionLocalOf<String?> { null }

/** Snapshot of the icons the *currently composed* TitleBar would paint
 *  on its right. TitleBar fills this via SideEffect on every
 *  recomposition when bottom-bar mode is on; clears it via
 *  DisposableEffect on screen exit. The single [BottomIconBar] at
 *  AppNavHost scope reads from this so the same per-screen visibility
 *  rules carry through. Null when bottom-bar mode is off — TitleBar
 *  short-circuits the publish path. */
val LocalBottomIconState = compositionLocalOf<MutableState<TitleBarIcons?>?> { null }

/** Copy [text] to the system clipboard with [label] as the ClipData
 *  label and surface a "Copied" Toast. Empty text is a no-op so the
 *  caller doesn't have to pre-check. Used by every TitleBar `onCopy`
 *  callback so the wiring at each screen stays one line. */
fun copyToClipboard(context: android.content.Context, text: String, label: String = "AI app") {
    if (text.isEmpty()) return
    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clip.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

/** Fire the standard Android Share sheet (ACTION_SEND) with [text] as
 *  the plain-text payload. Optional [subject] becomes EXTRA_SUBJECT
 *  (used by Email / Drive / a few other targets). Empty text no-ops
 *  so callers don't need to pre-check. Distinct from the file-
 *  attachment shareExport / shareReportAsHtml helpers — this one
 *  ships text only via EXTRA_TEXT. */
fun shareText(context: android.content.Context, text: String, subject: String? = null) {
    if (text.isBlank()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share"))
}

/** Captured icon state from a TitleBar — what BottomIconBar needs to
 *  render the same strip the top bar would have rendered. */
data class TitleBarIcons(
    val helpTopic: String?,
    val onBack: (() -> Unit)?,
    val onChat: (() -> Unit)?,
    val onInfo: (() -> Unit)?,
    val onCopy: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onReload: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onTrace: (() -> Unit)?,
    /** Captured from [LocalNavigateToCurrentReport] at TitleBar render
     *  time. Cannot be re-read from the local inside BottomIconBar
     *  itself — the bar lives at AppNavHost scope where the per-screen
     *  CompositionLocalProvider override isn't visible. */
    val onMemo: (() -> Unit)?
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
 * Tiny "on a >1h-429 cooldown" badge for model lists. ⏳ matches the
 * Model cooldowns card / SetupScreen icon. Same shape as VisionBadge.
 */
@Composable
fun CooldownBadge(onCooldown: Boolean) {
    if (!onCooldown) return
    Text(
        text = "⏳",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "user-blocked" badge for model lists. 🚫 matches the Blocked
 * models card icon. Same shape as VisionBadge.
 */
@Composable
fun BlockedBadge(isBlocked: Boolean) {
    if (!isBlocked) return
    Text(
        text = "🚫",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "tier-gated / inaccessible" badge for model lists. 🔒 matches
 * the Inaccessible models card icon. Selecting one will usually fail
 * unless the user has dedicated capacity on that provider.
 */
@Composable
fun InaccessibleBadge(isInaccessible: Boolean) {
    if (!isInaccessible) return
    Text(
        text = "🔒",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "not a chat model" badge for the main model pickers. 🖼️ stands
 * in for the family — image / TTS / STT / moderation / classify / OCR.
 * Selecting one of these for a chat flow will fail at runtime; the
 * badge + dimmed row warn the user before they tap.
 */
@Composable
fun NonTestableBadge(isNonTestable: Boolean) {
    if (!isNonTestable) return
    Text(
        text = "🖼️",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Generic title bar — three-section anatomy. Left: AI logo (→ Hub)
 * + ❓ Help (→ screen's help topic). Centre (only when a per-report
 * emoji is in scope): the dynamic report icon, centred between the
 * left button group and the right title. Right: the hardcoded screen
 * title, top-aligned so the label sits high on the row.
 *
 * The visible back arrow lives on [BottomIconBar], not here. This
 * function still registers a [BackHandler] when [onBackClick] is
 * non-null so the system / gesture back routes through whatever the
 * screen passed — overlay close, drill-in pop, edit-cancel, etc.
 *
 * Every action callback (chat / info / copy / share / reload / delete
 * / trace) is captured into [LocalBottomIconState]; the global
 * [BottomIconBar] paints them at the screen bottom.
 */
@Composable
fun TitleBar(
    title: String? = null,
    /** Legacy slot — every caller draws its own green subject row via
     *  [HardcodedSubjectRow] now, so this parameter is accepted-but-
     *  unused while the call-site sweep finishes. New callers should
     *  not pass it. */
    @Suppress("UNUSED_PARAMETER") subject: String? = null,
    onBackClick: (() -> Unit)? = null,
    centered: Boolean = false,
    helpTopic: String? = null,
    onTrace: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onReload: (() -> Unit)? = null,
    onChat: (() -> Unit)? = null,
    /** Optional 📋 copy-to-clipboard hook. Wire it from screens that
     *  display substantial copyable text (agent response, raw JSON,
     *  prompt body, translated text, redacted trace bytes, …). Null →
     *  icon hidden. Use the top-level [copyToClipboard] helper inside
     *  the lambda for the standard "set ClipData + Toast" behaviour. */
    onCopy: (() -> Unit)? = null,
    /** Optional 📤 share-as hook. Most screens use the top-level
     *  [shareText] helper to fire the Android share sheet with the
     *  same body the copy icon uses. The main AI Report screen reuses
     *  this slot for the export-format-picker flow — the previous
     *  in-action-row Export button is gone. Null → icon hidden. */
    onShare: (() -> Unit)? = null,
    /** Resolved per-report emoji (e.g. 📊). When non-null the icon
     *  renders centred between the left button group and the right
     *  title on every report-scoped screen. Tap navigates back to the
     *  main report (via [LocalNavigateToCurrentReport]) when one is
     *  provided; otherwise the icon is decorative. Callsites should
     *  pass `report.icon ?: "📝"` so the slot is filled while
     *  icon-gen is in flight or after it errored. */
    reportIcon: String? = null,
    /** Applied to the bar's outer Row. */
    modifier: Modifier = Modifier
) {
    if (onBackClick != null) {
        androidx.activity.compose.BackHandler { onBackClick() }
    }
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val resolvedReportIcon = reportIcon ?: LocalReportIcon.current
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
        return
    }
    // Publish action callbacks into LocalBottomIconState; the global
    // BottomIconBar at AppNavHost scope paints the strip. SideEffect
    // re-publishes on every recomposition; DisposableEffect clears on
    // screen exit with an identity check so a racing nav doesn't
    // clobber the next screen's just-published state.
    val state = LocalBottomIconState.current
    val captured = TitleBarIcons(
        helpTopic = helpTopic,
        onBack = onBackClick,
        onChat = onChat,
        onInfo = onInfo,
        onCopy = onCopy,
        onShare = onShare,
        onReload = onReload,
        onDelete = onDelete,
        onTrace = onTrace,
        onMemo = null
    )
    if (state != null) {
        SideEffect { state.value = captured }
        DisposableEffect(Unit) {
            onDispose { if (state.value === captured) state.value = null }
        }
    }
    val titleStyle = MaterialTheme.typography.titleLarge
    val barFontSize = titleStyle.fontSize * 1.35f
    val reportIconTap = LocalNavigateToCurrentReport.current
    // Negative-Y offset pulls the whole bar up against the status-bar
    // inset that Scaffold added, gaining ~10dp of vertical space for
    // the content underneath.
    Row(
        modifier = modifier.fillMaxWidth().offset(y = (-10).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiLogoButton(
            onClick = navigateHome,
            modifier = Modifier.offset(x = (-14).dp, y = (-10).dp)
        )
        HelpButton(
            onClick = { navigateHelp(helpTopic) },
            modifier = Modifier.offset(x = (-20).dp, y = (-10).dp)
        )
        if (resolvedReportIcon != null) {
            Box(modifier = Modifier.offset(x = (-24).dp, y = (-10).dp)) {
                TitleBarIcon(
                    resolvedReportIcon, Color.Unspecified,
                    onClick = reportIconTap ?: {},
                    width = 32.dp, scale = 2.0f
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (title != null) {
            Text(
                text = title, style = titleStyle, color = Color.White,
                fontSize = barFontSize, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Top).padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun AiLogoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = "Home",
        alpha = 0.75f,
        modifier = modifier.size(64.dp).clickable(onClick = onClick)
    )
}

@Composable
private fun HelpButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "❓",
        fontSize = 28.sp,
        color = AppColors.Blue,
        modifier = modifier.clickable(onClick = onClick)
    )
}

/**
 * Green subject sub-header rendered just below [TitleBar]. The only
 * shared definition of the green page-subject line — every screen
 * paints it via this helper so font / colour / y-position stay
 * identical app-wide.
 *
 * Self-gates on blank text so callers can drop it in unconditionally.
 * [providerService] + [model] make the subject clickable → Model Info
 * when both are non-null. [horizontalPadding] pads start/end for
 * screens whose outer Column doesn't already inset by 16 dp.
 * [trailing] slot at the right edge — used by Fan out L3 to surface
 * the role indicator beside the answerer label.
 */
@Composable
fun HardcodedSubjectRow(
    text: String?,
    providerService: com.ai.data.AppService? = null,
    model: String? = null,
    horizontalPadding: Dp = 0.dp,
    maxLines: Int = 1,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth()
            .offset(y = (-8).dp)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val clickableTextMod = if (providerService != null && !model.isNullOrBlank()) {
            Modifier.weight(1f, fill = true).modelInfoClickable(providerService, model)
        } else {
            Modifier.weight(1f, fill = true)
        }
        Text(
            text = text,
            fontSize = 26.sp, color = AppColors.Green,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = clickableTextMod
        )
        trailing()
    }
}

/** Action strip rendered on the right of [BottomIconBar]. Home / Help
 *  live in the top bar, so they're absent here. Every slot is
 *  conditional — null means the icon is omitted entirely so the strip
 *  stays tight. */
@Composable
private fun TitleBarActionStrip(
    onReload: (() -> Unit)?,
    onChat: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onTrace: (() -> Unit)?,
    onMemo: (() -> Unit)?,
    scale: Float = 1f,
    /** Extra horizontal gap inserted between every adjacent icon —
     *  on top of the per-pair Spacers already coded into the strip. */
    extraSpacing: Dp = 0.dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(extraSpacing)
    ) {
        if (onChat != null) TitleBarIcon("💬", Color.Unspecified, onChat, width = 28.dp, scale = scale)
        if (onInfo != null) TitleBarIcon("ℹ️", Color.Unspecified, onInfo, width = 28.dp, scale = scale)
        if (onCopy != null) TitleBarIcon("📋", Color.Unspecified, onCopy, width = 28.dp, scale = scale)
        if (onShare != null) TitleBarIcon("📤", Color.Unspecified, onShare, width = 28.dp, scale = scale)
        if (onReload != null) TitleBarIcon("🔄", AppColors.Orange, onReload, width = 28.dp, scale = scale)
        if (onDelete != null) TitleBarIcon("🗑", AppColors.Red, onDelete, width = 22.dp, scale = scale)
        if (onDelete != null && onTrace != null) {
            Spacer(modifier = Modifier.width(2.dp * scale))
        }
        if (onTrace != null) TitleBarIcon("🐞", Color.Unspecified, onTrace, width = 22.dp, scale = scale)
        if (onDelete != null && onTrace == null) {
            Spacer(modifier = Modifier.width(2.dp * scale))
        }
        if (onMemo != null) TitleBarIcon("📝", Color.Unspecified, onMemo, width = 28.dp, scale = scale)
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
 *  action icons + back arrow. Home + Help live in the top bar; this
 *  strip carries the per-screen actions only (chat / info / copy /
 *  share / reload / delete / trace / memo + the ← back arrow on the
 *  left). Icons render at a 1.25× scale by default, narrowing
 *  adaptively when the strip would otherwise overflow on a narrow
 *  screen. */
@Composable
fun BottomIconBar(icons: TitleBarIcons?, modifier: Modifier = Modifier) {
    val onBack = icons?.onBack
    val onChat = icons?.onChat
    val onInfo = icons?.onInfo
    val onCopy = icons?.onCopy
    val onShare = icons?.onShare
    val onReload = icons?.onReload
    val onDelete = icons?.onDelete
    val onTrace = icons?.onTrace
    val onMemo = icons?.onMemo
    val extraGap = 2
    var stripBase = 0
    var slotCount = 0
    fun slot(w: Int) { stripBase += w; slotCount++ }
    if (onChat != null) slot(28)
    if (onInfo != null) slot(28)
    if (onCopy != null) slot(28)
    if (onShare != null) slot(28)
    if (onReload != null) slot(28)
    if (onDelete != null) slot(22)
    if (onDelete != null && onTrace != null) stripBase += 2
    if (onTrace != null) slot(22)
    if (onDelete != null && onTrace == null) stripBase += 2
    if (onMemo != null) slot(28)
    val stripIntrinsic = (stripBase + (slotCount - 1).coerceAtLeast(0) * extraGap).coerceAtLeast(1)
    androidx.compose.foundation.layout.BoxWithConstraints(
        // Bottom padding lifts the bar a touch above the gesture
        // pill — without it the strip sat dead flush against the
        // physical edge once the system-bars inset was dropped.
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 12.dp)
    ) {
        val backW = if (onBack != null) 45 else 0
        val backGap = if (onBack != null) 4 else 0
        val available = maxWidth.value
        val desired = (available - backW - backGap) / stripIntrinsic
        // Floor at 1.0× so the strip never shrinks below the previous
        // top-bar size; ceiling at 1.875× = 1.5× × 1.25× preserves the
        // original headroom multiplier on roomy screens.
        val scale = desired.coerceIn(1.0f, 1.875f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                // Larger glyph with negative y offset — the arrow lifts
                // above the action-icon strip baseline for a more
                // tappable target, while the Box's reserved height
                // still matches the icon row so the bar stays tight.
                Box(
                    modifier = Modifier.size(width = 50.dp, height = 32.dp).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "←", color = Color.White, fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.offset(y = (-10).dp)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TitleBarActionStrip(
                onReload = onReload,
                onChat = onChat,
                onInfo = onInfo,
                onCopy = onCopy,
                onShare = onShare,
                onDelete = onDelete,
                onTrace = onTrace,
                onMemo = onMemo,
                scale = scale,
                extraSpacing = extraGap.dp
            )
        }
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
    onDelete: (() -> Unit)?,
    /** Optional widget rendered just before the trailing delete X —
     *  e.g. a 🐞 trace link on a model-cooldown row. */
    trailing: (@Composable () -> Unit)? = null
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
            trailing?.invoke()
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
