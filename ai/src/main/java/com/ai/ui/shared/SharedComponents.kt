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

/** Provided by AppNavHost so the title-bar Help icon can navigate
 *  to the general help page without prop-drilling a callback. */
val LocalNavigateToHelp = compositionLocalOf<() -> Unit> { {} }

/** Provided by AppNavHost so the title-bar Home icon can navigate
 *  to the Hub without prop-drilling a callback. Replaces the role
 *  the removed "AI" text-button used to play. */
val LocalNavigateHome = compositionLocalOf<() -> Unit> { {} }

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
 * routing the < arrow popped past inner state — e.g. on the Cross
 * drill-in's L2 it skipped L1 and exited the screen entirely.
 */
@Composable
fun TitleBar(
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    backText: String = "< Back",
    leftContent: (@Composable RowScope.() -> Unit)? = null,
    centered: Boolean = false,
    onTrace: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onReload: (() -> Unit)? = null
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
    } else {
        val showBackButton = LocalShowBackButton.current
        val backVisible = onBackClick != null && showBackButton
        val hasLeftSlot = leftContent != null || backVisible
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
                    text = title, style = MaterialTheme.typography.titleLarge, color = Color.White,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    textAlign = if (hasLeftSlot) TextAlign.Center else TextAlign.Start,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            TitleBarActionStrip(
                onHome = navigateHome,
                onReload = onReload,
                onInfo = onInfo,
                onDelete = onDelete,
                onTrace = onTrace,
                onHelp = navigateHelp
            )
        }
    }
}

/** Action strip rendered on the right of every [TitleBar]. Help and
 *  Home are always shown (Home is the rightmost slot, Help just to
 *  its left); the four conditional slots (Reload / Info / Delete /
 *  Trace) only render when their callback is non-null — null means
 *  the icon is omitted entirely so the strip stays tight. */
@Composable
private fun TitleBarActionStrip(
    onHome: () -> Unit,
    onReload: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onTrace: (() -> Unit)?,
    onHelp: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onReload != null) TitleBarIcon("🔄", AppColors.Orange, onReload)
        if (onInfo != null) TitleBarIcon("ℹ️", Color.Unspecified, onInfo)
        if (onDelete != null) TitleBarIcon("🗑", AppColors.Red, onDelete)
        if (onTrace != null) TitleBarIcon("🐞", Color.Unspecified, onTrace)
        TitleBarIcon("❓", AppColors.Blue, onHelp)
        TitleBarIcon("🏠", AppColors.Blue, onHome)
    }
}

@Composable
private fun TitleBarIcon(
    emoji: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(width = 28.dp, height = 32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji, fontSize = 16.sp,
            color = if (tint == Color.Unspecified) Color.White else tint
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
 * confirm button re-fires the screen's API call; the dismiss leaves
 * the existing result alone.
 */
@Composable
fun ReloadConfirmationDialog(
    target: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-run API call?") },
        text = { Text("This will fire a new API call for $target and replace the current result.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Re-run", color = AppColors.Blue, maxLines = 1, softWrap = false) }
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
    onDelete: () -> Unit
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
            IconButton(onClick = onDelete) {
                Text("X", color = AppColors.Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Reusable collapsible card with a clickable title header.
 */
@Composable
fun CollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
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
