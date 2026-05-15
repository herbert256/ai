package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** Format a per-token price as a two-decimal $-per-million-tokens
 *  string. Internal so the sibling ReportSelectionDialogs.kt can
 *  reuse the same formatting in its dialog rows. */
internal fun dlgFmtPrice(pricePerToken: Double): String {
    val m = pricePerToken * 1_000_000
    return when { m == 0.0 -> "0"; m < 0.01 -> "<.01"; else -> "%.2f".format(m) }
}

internal fun dlgFmtPriceM(perMillion: Double): String {
    return when { perMillion == 0.0 -> "0"; perMillion < 0.01 -> "<.01"; else -> "%.2f".format(perMillion) }
}

/** Full-screen agent picker. Mirror of [ReportSelectFlockScreen] \u2014
 *  search field + list of agents with effective model and per-call
 *  cost estimate, plus an "Edit Agents" footer that deep-links into
 *  Settings \u2192 AI Workers \u2192 Agents. */
@Composable
internal fun ReportSelectAgentScreen(
    aiSettings: Settings,
    onSelectAgent: (Agent) -> Unit,
    onBack: () -> Unit,
    onEditAgents: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.agents
    val filtered = remember(all, search) {
        if (search.isBlank()) all
        else all.filter { a ->
            val q = search.lowercase(java.util.Locale.ROOT)
            a.name.lowercase(java.util.Locale.ROOT).contains(q)
                || a.provider.id.lowercase(java.util.Locale.ROOT).contains(q)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "report_pick_agent", title = "Pick an agent", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search agents...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
        )
        Text("${filtered.size} of ${all.size} agents", fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(8.dp))

        if (all.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No agents yet.", color = AppColors.TextTertiary, fontSize = 14.sp)
                    Text("Agents pair a provider, model, and parameter set for one-tap inclusion.", color = AppColors.TextDim, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { agent ->
                    val model = aiSettings.getEffectiveModelForAgent(agent)
                    val p = PricingCache.getPricing(context, agent.provider, model)
                    val real = p.source != "DEFAULT"
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectAgent(agent) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(agent.name, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(com.ai.ui.shared.modelLabel(agent.provider.id, model),
                                fontSize = 12.sp, color = AppColors.TextTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            "${dlgFmtPrice(p.promptPrice)} / ${dlgFmtPrice(p.completionPrice)}",
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (real) AppColors.Red else AppColors.SurfaceDark,
                            modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                        )
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onEditAgents, modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Edit Agents", maxLines = 1, softWrap = false) }
    }
}


/** Full-screen single-select picker. Tap a row → onConfirm fires with
 *  that one (provider, model) pair and the caller closes the picker —
 *  no checkboxes, no batch confirm, no "selected float to top"
 *  reordering. The old multi-select flow was confusing: tapping a row
 *  reordered the list invisibly as the row jumped to the top, leaving
 *  the user wondering where the next row went.
 *
 *  Used by the +Model report flow, the Rerank/Summarize/Compare/
 *  Moderation meta picker, and the chat moderation-model picker. All
 *  three trade "pick many at once" for tap-and-go; users wanting N
 *  models on a report just open the picker N times.
 *
 *  [alreadyAdded] rows render dimmed and ignore taps so a model can't
 *  be re-added to a report. [modelTypeFilter] adds a "<Type> models
 *  only" toggle, default ON. */
@Composable
internal fun ReportSelectModelsScreen(
    aiSettings: Settings,
    onConfirm: (Pair<AppService, String>) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    alreadyAdded: Set<Pair<AppService, String>> = emptySet(),
    titleText: String = "Pick Model",
    /** When non-null, surfaces a "<Type> models only" toggle that filters
     *  the catalog to that ModelType. Defaults to ON when shown — Rerank
     *  / Moderation flows almost always want the typed catalog; the user
     *  can untick to widen. Pass one of the ModelType.* string constants. */
    modelTypeFilter: String? = null,
    /** Recently picked (provider, model) entries to surface as a
     *  "Recent" section above the main alphabetical list. Empty = no
     *  Recent section is drawn. The caller is responsible for
     *  persisting picks; the picker just calls [onRecordRecent]
     *  before [onConfirm] when set. */
    recentEntries: List<Pair<AppService, String>> = emptyList(),
    /** Optional record hook fired right before [onConfirm] when the
     *  user picks a row (from either Recent or the main list). Lets
     *  Report-section call sites bump their entry to the front of
     *  the persisted recents without changing onConfirm signatures. */
    onRecordRecent: ((Pair<AppService, String>) -> Unit)? = null,
    /** When true (default), entries in [Settings.inaccessibleModels] are
     *  filtered out of the visible list entirely — those models can't
     *  be called on this account. Set to false only on the
     *  Inaccessible-models CRUD screen so the user can still pick a
     *  currently-inaccessible model to swap it. */
    hideInaccessible: Boolean = true
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    // "providerId:model" → reason for user-blocked pairs. Dim them but
    // keep them selectable (advisory) — distinct from the disabled
    // already-added / benched states.
    val blockedReasons = remember(aiSettings) { aiSettings.blockedReasonByKey }
    var search by remember { mutableStateOf("") }
    val activeServices = aiSettings.getActiveServices()
    var providerFilter by remember { mutableStateOf<AppService?>(null) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var typeOnly by remember { mutableStateOf(modelTypeFilter != null) }

    // Local models surface alongside remote providers under the
    // synthetic AppService.LOCAL. Source depends on the picker's
    // type filter:
    //   - RERANK → MediaPipe TextEmbedder .tflite files (LocalEmbedder)
    //   - else   → MediaPipe LLM Inference .task files (LocalLlm)
    // Moderation has no on-device equivalent yet so the local list
    // is empty there. The (provider, model) tuple ends up at the
    // caller's onConfirm — they branch on provider.id ==
    // AppService.LOCAL.id when needed.
    val localModelsForFilter = remember(modelTypeFilter, context) {
        when (modelTypeFilter) {
            com.ai.data.ModelType.RERANK -> com.ai.data.local.LocalEmbedder.availableModels(context)
            com.ai.data.ModelType.MODERATION -> emptyList()
            else -> com.ai.data.local.LocalLlm.availableLlms(context)
        }
    }
    val effectiveServices = remember(activeServices, localModelsForFilter) {
        if (localModelsForFilter.isNotEmpty()) activeServices + AppService.LOCAL else activeServices
    }
    val all = remember(aiSettings, localModelsForFilter) {
        val remote = activeServices.flatMap { prov -> aiSettings.getModels(prov).map { prov to it } }
        val local = localModelsForFilter.map { AppService.LOCAL to it }
        remote + local
    }
    val providerFiltered = if (providerFilter != null) all.filter { it.first == providerFilter } else all
    // Inaccessible entries (Together non-serverless catalog noise,
    // etc.) are hidden from every picker by default — the model can't
    // be called on this account, so showing it is just a footgun. The
    // Inaccessible CRUD screen overrides this so its own edit picker
    // can still see them.
    val inaccessibleKeys = remember(aiSettings) { aiSettings.inaccessibleKeys }
    val accessibleFiltered = if (hideInaccessible && inaccessibleKeys.isNotEmpty())
        providerFiltered.filter { (prov, model) -> "${prov.id}:$model" !in inaccessibleKeys }
        else providerFiltered
    val typeFiltered = if (typeOnly && modelTypeFilter != null) {
        accessibleFiltered.filter { (prov, model) ->
            // LOCAL is not in Settings.providers so getModelType returns
            // null; but localModelsForFilter was already populated by
            // modelTypeFilter, so any (LOCAL, model) pair already matches
            // by construction. Pass it through unconditionally to keep
            // the local rerank / LLM rows visible with the type filter on.
            prov.id == AppService.LOCAL.id || aiSettings.getModelType(prov, model) == modelTypeFilter
        }
    } else accessibleFiltered
    val searched = if (search.isBlank()) typeFiltered else typeFiltered.filter { (prov, model) ->
        prov.id.lowercase().contains(search.lowercase()) || model.lowercase().contains(search.lowercase())
    }
    val sorted = remember(searched) {
        // Stable alphabetical order \u2014 no jumping when the user taps.
        searched.sortedWith(
            compareBy<Pair<AppService, String>> { it.first.id.lowercase() }
                .thenBy { it.second.lowercase() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "report_pick_model", title = titleText, onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { providerDropdownExpanded = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, AppColors.BorderUnfocused)) {
                Text(providerFilter?.id ?: "All Providers", modifier = Modifier.weight(1f), fontSize = 13.sp,
                    color = if (providerFilter != null) Color.White else AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("\u25be", color = AppColors.TextTertiary)
            }
            DropdownMenu(expanded = providerDropdownExpanded, onDismissRequest = { providerDropdownExpanded = false }, modifier = Modifier.background(Color(0xFF2D2D2D))) {
                DropdownMenuItem(text = { Text("All Providers", color = if (providerFilter == null) AppColors.Blue else Color.White, fontSize = 13.sp) },
                    onClick = { providerFilter = null; providerDropdownExpanded = false })
                remember(effectiveServices) { effectiveServices.sortedBy { it.id.lowercase() } }.forEach { provider ->
                    val mc = if (provider.id == AppService.LOCAL.id) localModelsForFilter.size else aiSettings.getModels(provider).size
                    DropdownMenuItem(text = { Text("${provider.id} ($mc)", color = if (providerFilter == provider) AppColors.Blue else Color.White, fontSize = 13.sp) },
                        onClick = { providerFilter = provider; providerDropdownExpanded = false })
                }
            }
        }

        if (modelTypeFilter != null) {
            val label = when (modelTypeFilter) {
                com.ai.data.ModelType.RERANK -> "Rerank models only"
                com.ai.data.ModelType.MODERATION -> "Moderation models only"
                com.ai.data.ModelType.EMBEDDING -> "Embedding models only"
                else -> "$modelTypeFilter models only"
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(checked = typeOnly, onCheckedChange = { typeOnly = it })
                Text(label, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search models...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
        Text("${sorted.size} of ${all.size} models", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        // Common row renderer for both the Recent section and the main
        // alphabetical list. Hoisted so the picker body stays compact
        // and the two sections stay byte-identical in behaviour.
        @Composable
        fun ModelRow(entry: Pair<AppService, String>) {
            val (provider, model) = entry
            val isAlreadyAdded = entry in alreadyAdded
            // Benched by a >1h 429 (ModelCooldownStore) — dim, but
            // still selectable (advisory), same as a blocked row.
            val benchedUntil = cooldowns["${provider.id}:$model"]
                ?.takeIf { it > System.currentTimeMillis() }
            // Non-testable type (IMAGE / TTS / STT / Moderation /
            // Classify / OCR) — no chat-shaped dispatch in the app, so
            // picking one for a chat / report flow will fail. Skip
            // this hint when modelTypeFilter explicitly *asks* for one
            // of these (e.g. the Moderation picker passes MODERATION
            // — the user wants exactly that type then).
            val modelType = aiSettings.getModelType(provider, model)
            val isNonTestable = provider.id != AppService.LOCAL.id &&
                modelType in com.ai.data.ModelType.NON_TESTABLE_TYPES &&
                modelType != modelTypeFilter
            // Only an already-added row is non-selectable; benched +
            // blocked + non-testable rows just dim.
            val disabled = isAlreadyAdded
            val blockReason = blockedReasons["${provider.id}:$model"]
            val pricing = aiSettings.getModelPricing(provider, model)
                ?: PricingCache.getPricing(context, provider, model)
            val real = pricing.source != "DEFAULT"
            val rowAlpha = if (disabled || benchedUntil != null || blockReason != null || isNonTestable) 0.4f else 1f
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !disabled) {
                        onRecordRecent?.invoke(entry)
                        onConfirm(entry)
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).alpha(rowAlpha)) {
                    Text(provider.id, fontSize = 12.sp, color = AppColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(provider, model))
                        com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(provider, model))
                        com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(provider, model))
                        com.ai.ui.shared.CooldownBadge(benchedUntil != null)
                        com.ai.ui.shared.BlockedBadge(blockReason != null)
                        com.ai.ui.shared.NonTestableBadge(isNonTestable)
                        // already-added rows still render dimmed and
                        // ignore taps so the user can see them in the
                        // catalog without re-adding; the trailing
                        // caption was noisy on a long list.
                    }
                    if (benchedUntil != null) {
                        Text(
                            com.ai.data.ModelCooldownStore.cooldownCaption(benchedUntil),
                            fontSize = 10.sp, color = AppColors.Orange, maxLines = 1
                        )
                    }
                    if (blockReason != null) {
                        Text(
                            if (blockReason.isBlank()) "🚫 Blocked" else "🚫 Blocked: $blockReason",
                            fontSize = 10.sp, color = AppColors.Red, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text("${dlgFmtPrice(pricing.promptPrice)} / ${dlgFmtPrice(pricing.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = if (real) AppColors.Red else AppColors.SurfaceDark,
                    modifier = (if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier).alpha(rowAlpha))
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            // Recent section — surfaces the last 3 picks for the
            // Report flow above the main alphabetical list. Mirrors
            // every filter applied to the main list (provider, type
            // toggle, search, alreadyAdded) so a Recent row never
            // shows when the user has narrowed past it; the section
            // is supposed to be a shortcut into the *current* view,
            // not a view of recents outside the view.
            //
            // If filtering empties the section we drop the header
            // and the "All" divider too so the user doesn't see a
            // labelled-but-empty band.
            val recentFiltered = recentEntries.filter { entry ->
                val (prov, model) = entry
                if (entry in alreadyAdded) return@filter false
                if (providerFilter != null && prov != providerFilter) return@filter false
                if (typeOnly && modelTypeFilter != null) {
                    val matchesType = prov.id == AppService.LOCAL.id ||
                        aiSettings.getModelType(prov, model) == modelTypeFilter
                    if (!matchesType) return@filter false
                }
                if (search.isNotBlank()) {
                    val q = search.lowercase()
                    if (!prov.id.lowercase().contains(q) && !model.lowercase().contains(q)) {
                        return@filter false
                    }
                }
                // Drop recents whose (provider, model) no longer exists
                // in the catalog — a deleted model would otherwise
                // surface as a Recent row that does nothing useful when
                // tapped.
                entry in all
            }
            if (recentFiltered.isNotEmpty()) {
                item {
                    Text(
                        "Recent",
                        fontSize = 11.sp,
                        color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 4.dp)
                    )
                }
                items(recentFiltered, key = { "recent:${it.first.id}:${it.second}" }) { entry ->
                    ModelRow(entry)
                }
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "All",
                        fontSize = 11.sp,
                        color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 4.dp)
                    )
                }
            }
            items(sorted, key = { "${it.first.id}:${it.second}" }) { entry ->
                ModelRow(entry)
            }
        }
    }
}

// ========================================================================
// Full-screen replacements for the +Flock / +Swarm dialogs and the
// Meta / Fan out / Fan-in picker popups. Each one shows richer per-row
// info than the cramped AlertDialog could, surfaces an "Edit …" button
// that deep-links into Settings, and (for prompts) a "Use a one-time
// prompt" entry that lets the user type a throwaway template inline.
// ========================================================================

/** Full-screen flock picker. Replaces the popup ReportSelectFlockDialog
 *  with more breathing room: each row shows the flock name, the
 *  per-flock cost estimate (sum across active agents), the number of
 *  active vs total members, and the comma-joined agent names. Each
 *  row also carries an ℹ️ icon that pops a per-flock detail screen
 *  listing every agent with its provider, model, capabilities, cost,
 *  and configured system-prompt / parameters presets. */
@Composable
internal fun ReportSelectFlockScreen(
    aiSettings: Settings,
    onSelectFlock: (Flock) -> Unit,
    onBack: () -> Unit,
    onEditFlocks: () -> Unit,
    /** Drill into Model Info from a row inside the per-flock info
     *  screen. Caller (ReportsScreen) forwards the screen's existing
     *  onNavigateToModelInfo so taps land on the same Model Info
     *  page the rest of the app uses. */
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.flocks
    val filtered = remember(all, search) {
        if (search.isBlank()) all
        else all.filter { it.name.lowercase(java.util.Locale.ROOT).contains(search.lowercase(java.util.Locale.ROOT)) }
    }
    // Set when the user taps the ℹ️ icon on a flock row; cleared on
    // the info screen's back arrow / gesture.
    var infoFlock by remember { mutableStateOf<Flock?>(null) }
    infoFlock?.let { f ->
        FlockInfoScreen(aiSettings, f, onNavigateToModelInfo) { infoFlock = null }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "report_pick_flock", title = "Pick a flock", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search flocks...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
        )
        Text("${filtered.size} of ${all.size} flocks", fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(8.dp))

        if (all.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No flocks yet.", color = AppColors.TextTertiary, fontSize = 14.sp)
                    Text("Group your agents into a flock for one-tap inclusion.", color = AppColors.TextDim, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { flock ->
                    val allAgents = aiSettings.getAgentsForFlock(flock)
                    val active = allAgents.filter { aiSettings.isProviderActive(it.provider) }
                    var tIn = 0.0; var tOut = 0.0; var realPrice = false
                    active.forEach { a ->
                        val p = PricingCache.getPricing(context, a.provider, aiSettings.getEffectiveModelForAgent(a))
                        tIn += p.promptPrice * 1_000_000
                        tOut += p.completionPrice * 1_000_000
                        if (p.source != "DEFAULT") realPrice = true
                    }
                    val countLabel = if (active.size != allAgents.size)
                        "${active.size}/${allAgents.size} agents"
                    else "${allAgents.size} agents"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ℹ️ icon: stays separate from the row's main
                        // tap target so the user can preview members
                        // without accidentally adding the flock.
                        Text(
                            "ℹ️", fontSize = 18.sp, color = AppColors.Blue,
                            modifier = Modifier
                                .clickable { infoFlock = flock }
                                .padding(end = 12.dp, start = 2.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f).clickable { onSelectFlock(flock) },
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    flock.name, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${dlgFmtPriceM(tIn)} / ${dlgFmtPriceM(tOut)}",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = if (realPrice) AppColors.Red else AppColors.SurfaceDark,
                                    modifier = if (!realPrice) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                                )
                            }
                            Text(countLabel, fontSize = 12.sp, color = AppColors.TextTertiary)
                            if (allAgents.isNotEmpty()) {
                                Text(
                                    allAgents.joinToString(", ") { it.name },
                                    fontSize = 11.sp, color = AppColors.TextDim,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onEditFlocks, modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Edit Flocks", maxLines = 1, softWrap = false) }
    }
}

/** Full-screen swarm picker. Mirror of ReportSelectFlockScreen — shows
 *  member count, cost estimate, and the comma-joined provider/model
 *  list for context. Each row carries an ℹ️ icon that pops a per-swarm
 *  detail screen listing every member with provider, model,
 *  capabilities, and cost. */
@Composable
internal fun ReportSelectSwarmScreen(
    aiSettings: Settings,
    onSelectSwarm: (Swarm) -> Unit,
    onBack: () -> Unit,
    onEditSwarms: () -> Unit,
    /** Drill into Model Info from a row inside the per-swarm info
     *  screen. Same shape as the equivalent on
     *  [ReportSelectFlockScreen]. */
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.swarms
    val filtered = remember(all, search) {
        if (search.isBlank()) all
        else all.filter { it.name.lowercase(java.util.Locale.ROOT).contains(search.lowercase(java.util.Locale.ROOT)) }
    }
    var infoSwarm by remember { mutableStateOf<Swarm?>(null) }
    infoSwarm?.let { s ->
        SwarmInfoScreen(aiSettings, s, onNavigateToModelInfo) { infoSwarm = null }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "report_pick_swarm", title = "Pick a swarm", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search swarms...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
        )
        Text("${filtered.size} of ${all.size} swarms", fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(8.dp))

        if (all.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No swarms yet.", color = AppColors.TextTertiary, fontSize = 14.sp)
                    Text("Group provider+model pairs to test many models at once.", color = AppColors.TextDim, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { swarm ->
                    var tIn = 0.0; var tOut = 0.0; var realPrice = false
                    swarm.members.forEach { m ->
                        val p = PricingCache.getPricing(context, m.provider, m.model)
                        tIn += p.promptPrice * 1_000_000
                        tOut += p.completionPrice * 1_000_000
                        if (p.source != "DEFAULT") realPrice = true
                    }
                    val activeMembers = swarm.members.filter { aiSettings.isProviderActive(it.provider) }
                    val countLabel = if (activeMembers.size != swarm.members.size)
                        "${activeMembers.size}/${swarm.members.size} members"
                    else "${swarm.members.size} members"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ℹ️", fontSize = 18.sp, color = AppColors.Blue,
                            modifier = Modifier
                                .clickable { infoSwarm = swarm }
                                .padding(end = 12.dp, start = 2.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f).clickable { onSelectSwarm(swarm) },
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    swarm.name, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${dlgFmtPriceM(tIn)} / ${dlgFmtPriceM(tOut)}",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = if (realPrice) AppColors.Red else AppColors.SurfaceDark,
                                    modifier = if (!realPrice) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                                )
                            }
                            Text(countLabel, fontSize = 12.sp, color = AppColors.TextTertiary)
                            if (swarm.members.isNotEmpty()) {
                                Text(
                                    swarm.members.joinToString(", ") { "${it.provider.id}/${it.model}" },
                                    fontSize = 11.sp, color = AppColors.TextDim,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onEditSwarms, modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Edit Swarms", maxLines = 1, softWrap = false) }
    }
}

/** Full-screen internal-prompt picker. One Composable serves the Meta /
 *  Fan-out / Fan-in launcher pickers — pass the filtered prompt
 *  list, the title, and the category id used for "Edit X prompts" deep
 *  link + the "one-time prompt" InternalPrompt construction.
 *
 *  Each row shows the prompt name, its title (if any), and the first
 *  two non-empty lines of the template body, so the user can pick by
 *  content rather than by remembering names. The bottom row offers
 *  "Edit prompts" and "Use a one-time prompt"; the latter pushes the
 *  one-time editor onto the screen via a local boolean. */
@Composable
internal fun ReportSelectInternalPromptScreen(
    titleText: String,
    category: String,
    prompts: List<InternalPrompt>,
    onSelectPrompt: (InternalPrompt) -> Unit,
    onBack: () -> Unit,
    onEditPrompts: () -> Unit
) {
    BackHandler { onBack() }
    var search by remember { mutableStateOf("") }
    var showOneTime by remember { mutableStateOf(false) }
    val sorted = remember(prompts) { prompts.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } }
    val filtered = remember(sorted, search) {
        if (search.isBlank()) sorted
        else {
            val q = search.lowercase(java.util.Locale.ROOT)
            sorted.filter {
                it.name.lowercase(java.util.Locale.ROOT).contains(q) ||
                    it.title.lowercase(java.util.Locale.ROOT).contains(q) ||
                    it.text.lowercase(java.util.Locale.ROOT).contains(q)
            }
        }
    }

    if (showOneTime) {
        ReportOneTimePromptScreen(
            category = category,
            onConfirm = { built ->
                showOneTime = false
                onSelectPrompt(built)
            },
            onBack = { showOneTime = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // Reuse the closest existing helpTopic per category so the help
        // icon points at something meaningful even though this screen
        // is shared across the three picker entry points.
        val helpId = when (category) {
            "meta" -> "report_meta"
            "fan_out" -> "secondary_fan_out"
            "fan_in" -> "secondary_fan_out"
            else -> "secondary_list"
        }
        TitleBar(helpTopic = helpId, title = titleText, onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search prompts...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
        )
        Text("${filtered.size} of ${sorted.size} prompts", fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(8.dp))

        if (sorted.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No prompts in this category.", color = AppColors.TextTertiary, fontSize = 14.sp)
                    Text("Add one via Edit prompts, or run a one-time prompt below.", color = AppColors.TextDim, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { p ->
                    // First two non-blank lines of the template body —
                    // gives the user enough context to recognise the
                    // prompt without showing a wall of text.
                    val previewLines = remember(p.text) {
                        p.text.lineSequence().filter { it.isNotBlank() }.take(2).toList()
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectPrompt(p) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            p.name, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (p.title.isNotBlank()) {
                            Text(p.title, fontSize = 12.sp, color = AppColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        previewLines.forEach { line ->
                            Text(line, fontSize = 11.sp, color = AppColors.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onEditPrompts, modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Edit prompts", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = { showOneTime = true }, modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("One-time prompt", maxLines = 1, softWrap = false) }
        }
    }
}

/** Generic full-screen action picker — used by the report-result View
 *  and Edit menus. Each option carries a label plus up to two lines
 *  of secondary info (count, current value, etc.) so the user can
 *  pick the right action without first tapping it to find out what
 *  it shows. */
internal data class ReportActionOption(
    val label: String,
    val detail: String? = null,
    val secondary: String? = null,
    val onClick: () -> Unit
)

@Composable
internal fun ReportActionPickerScreen(
    titleText: String,
    helpTopic: String,
    options: List<ReportActionOption>,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = helpTopic, title = titleText, onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            options.forEach { opt ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { opt.onClick() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(opt.label, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                    if (!opt.detail.isNullOrBlank()) {
                        Text(opt.detail, fontSize = 12.sp, color = AppColors.Blue,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!opt.secondary.isNullOrBlank()) {
                        Text(opt.secondary, fontSize = 11.sp, color = AppColors.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }
}

/** Inline editor for a one-time, non-saved prompt. The user types a
 *  template body; on Run, builds an in-memory [InternalPrompt] (with a
 *  fresh UUID that doesn't land on disk) and hands it to the launcher
 *  via the onConfirm callback. The launcher path treats it the same
 *  as a saved prompt — substitution, scope screen, model picker, and
 *  result persistence all run unchanged. The helper card lists the
 *  placeholders that resolve at execution time so the user doesn't
 *  need to remember them. */
@Composable
internal fun ReportOneTimePromptScreen(
    category: String,
    onConfirm: (InternalPrompt) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var name by remember { mutableStateOf("One-time ${category.replace('_', ' ')}") }
    var text by remember { mutableStateOf("") }

    val placeholders: List<Pair<String, String>> = when (category) {
        "fan_out" -> listOf(
            "@RESPONSE@" to "the source agent's response being checked",
            "@QUESTION@" to "the report's prompt",
            "@TITLE@" to "the report's title",
            "@DATE@" to "current date",
            "@COUNT@" to "agent count"
        )
        "fan_in" -> listOf(
            "@QUESTION@" to "the report's prompt",
            "@TITLE@" to "report title",
            "@DATE@" to "current date",
            "@COUNT@" to "N reports",
            "@FAN_OUT_COUNT@" to "N-1 responses each",
            "@REPORT@@RESPONSES@" to "iterable block — use once; @RESPONSE@ goes inside @RESPONSES@"
        )
        else -> listOf( // meta / chat / fallback
            "@QUESTION@" to "the report's prompt",
            "@RESULTS@" to "every successful agent's response, joined",
            "@COUNT@" to "agent count",
            "@TITLE@" to "report title",
            "@DATE@" to "current date"
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        val helpId = when (category) {
            "meta" -> "report_meta"
            "fan_out" -> "secondary_fan_out"
            "fan_in" -> "secondary_fan_out"
            else -> "internal_prompt_edit"
        }
        TitleBar(helpTopic = helpId, title = "One-time prompt", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (used as the result row label)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Template body") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                colors = AppColors.outlinedFieldColors()
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Available placeholders", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Substituted at run time. Tap a placeholder to copy it into the template body at the cursor end.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    placeholders.forEach { (token, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { text = if (text.isEmpty()) token else "$text$token" }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                token, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = AppColors.Orange, modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(desc, fontSize = 11.sp, color = AppColors.TextDim,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Text(
                "This prompt is not saved. Use \"Edit prompts\" to create a permanent entry instead.",
                fontSize = 11.sp, color = AppColors.TextDim
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val built = InternalPrompt(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name.trim().ifBlank { "One-time prompt" },
                    category = category,
                    text = text
                )
                onConfirm(built)
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Run", maxLines = 1, softWrap = false) }
    }
}

/** Single-select picker over the user's previous AI reports. Used by
 *  the +Report button on the new-report selection screen — tapping a
 *  report copies its model list into the current selection so the
 *  user can reuse a working set without re-picking each model.
 *
 *  Reports are loaded off the UI thread (getAllReports parses every
 *  report JSON, including image-attached reports which can be MB-
 *  sized). Sorted newest-first by Report.timestamp. */
@Composable
internal fun ReportSelectFromReportScreen(
    onConfirm: (com.ai.data.Report) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    // Re-read on every ON_RESUME so a report added in the background
    // while this picker is composed (e.g. user popped to a different
    // screen, started a fresh report, came back) shows up. The
    // previous produceState had an implicit Unit key, so the disk
    // read fired exactly once per screen lifetime.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val reports by produceState(initialValue = emptyList<com.ai.data.Report>(), refreshTick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ai.data.ReportStorage.getAllReports(context)
        }
    }
    val filtered = remember(reports, search) {
        if (search.isBlank()) reports
        else {
            val q = search.lowercase()
            reports.filter { it.title.lowercase().contains(q) || it.prompt.lowercase().contains(q) }
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "report_pick_previous", title = "Pick previous report", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search title or prompt...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp) } })
        Text("${filtered.size} of ${reports.size} reports", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        if (reports.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No previous reports yet.", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { report ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onConfirm(report) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (com.ai.ui.shared.LocalIconGenEnabled.current) {
                            report.icon?.let {
                                Text(it, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(report.title.ifBlank { "(untitled)" }, fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${report.agents.size} model${if (report.agents.size == 1) "" else "s"}",
                                fontSize = 11.sp, color = AppColors.TextTertiary
                            )
                        }
                        Text(">", color = AppColors.Blue, fontSize = 14.sp,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }
    }
}

/** Single row of the Swarm / Flock info screens. Renders provider id,
 *  model id, capability badges (vision / web search / reasoning), and
 *  the per-million-token price pair on the right. Flock rows also pass
 *  the configured params / system-prompt preset names, which surface
 *  as dim lines below the model row when present. */
@Composable
private fun ModelInfoRow(
    aiSettings: Settings,
    provider: AppService,
    model: String,
    paramsLabels: List<String> = emptyList(),
    systemPromptLabel: String? = null,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val pricing = aiSettings.getModelPricing(provider, model)
        ?: PricingCache.getPricing(context, provider, model)
    val real = pricing.source != "DEFAULT"
    val rowMod = Modifier.fillMaxWidth()
        .let { if (onClick != null) it.clickable { onClick() } else it }
        .padding(vertical = 8.dp, horizontal = 4.dp)
    Column(modifier = rowMod,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(provider.id, fontSize = 12.sp, color = AppColors.Blue,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model, fontSize = 13.sp, color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(provider, model))
            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(provider, model))
            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(provider, model))
            Text("${dlgFmtPrice(pricing.promptPrice)} / ${dlgFmtPrice(pricing.completionPrice)}",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = if (real) AppColors.Red else AppColors.SurfaceDark,
                modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
        }
        if (paramsLabels.isNotEmpty()) {
            Text("Parameters: ${paramsLabels.joinToString(", ")}",
                fontSize = 11.sp, color = AppColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (systemPromptLabel != null) {
            Text("System prompt: $systemPromptLabel",
                fontSize = 11.sp, color = AppColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}

/** Detail screen for a single swarm. Pops over [ReportSelectSwarmScreen]
 *  via early-return on the local `infoSwarm` state. Lists every
 *  member with provider, model, capability badges, and cost. */
@Composable
private fun SwarmInfoScreen(
    aiSettings: Settings,
    swarm: Swarm,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "report_swarm_info",
            title = "Swarm",
            subject = swarm.name,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (swarm.members.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No members", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(swarm.members, key = { "${it.provider.id}|${it.model}" }) { m ->
                    ModelInfoRow(
                        aiSettings, m.provider, m.model,
                        onClick = { onNavigateToModelInfo(m.provider, m.model) }
                    )
                }
            }
        }
    }
}

/** Detail screen for a single flock. Lists every agent with provider,
 *  model, capability badges, cost, and the configured params /
 *  system-prompt preset names. Flock-level overrides (presets pinned
 *  on the flock itself rather than the agent) surface once at the top
 *  so the user can tell which presets the report run will actually
 *  use after merging. */
@Composable
private fun FlockInfoScreen(
    aiSettings: Settings,
    flock: Flock,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val agents = aiSettings.getAgentsForFlock(flock)
    val flockParamNames = aiSettings.getParametersByIds(flock.paramsIds).map { it.name }
    val flockSystemPromptName = flock.systemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "report_flock_info",
            title = "Flock",
            subject = flock.name,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (flockParamNames.isNotEmpty() || flockSystemPromptName != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Flock overrides", fontSize = 11.sp, color = AppColors.TextTertiary,
                    fontWeight = FontWeight.Bold)
                if (flockParamNames.isNotEmpty()) {
                    Text("Parameters: ${flockParamNames.joinToString(", ")}",
                        fontSize = 11.sp, color = AppColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (flockSystemPromptName != null) {
                    Text("System prompt: $flockSystemPromptName",
                        fontSize = 11.sp, color = AppColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        if (agents.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No agents", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(agents, key = { it.id }) { agent ->
                    val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
                    val paramNames = aiSettings.getParametersByIds(agent.paramsIds).map { it.name }
                    val systemName = agent.systemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
                    ModelInfoRow(
                        aiSettings, agent.provider, effectiveModel, paramNames, systemName,
                        onClick = { onNavigateToModelInfo(agent.provider, effectiveModel) }
                    )
                }
            }
        }
    }
}

