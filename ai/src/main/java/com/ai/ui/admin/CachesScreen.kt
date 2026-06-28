package com.ai.ui.admin

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.EmbeddingsStore
import com.ai.data.InternalPromptIconCache
import com.ai.data.MetaCache
import com.ai.data.MetadataIconsHolder
import com.ai.data.ModelListCache
import com.ai.data.PricingCache
import com.ai.data.PromptCache
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.horizontalSwipeNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Housekeeping → Caches. A hub over every on-disk cache the app keeps
 * ([PromptCache], [InternalPromptIconCache], [MetaCache], [ModelListCache],
 * the [PricingCache] tiers + supported-params catalog, and [EmbeddingsStore]).
 *
 * - [CachesHubScreen] lists each cache with its entry count + disk size.
 * - [CacheEntriesScreen] browses one cache's entries: 👁 view / 🔄 refresh /
 *   🗑 delete per row, and a horizontal swipe to step to the next/previous
 *   cache. 🔄 is shown only where an entry can actually be regenerated
 *   (model lists, pricing tiers, supported params) — the hash-keyed caches
 *   (prompts, meta, embeddings) store no recoverable input, so they get 👁/🗑.
 *
 * Every cache is described once by a [CacheDescriptor]; the registry built by
 * [cacheRegistry] drives both screens (and the swipe order).
 */

// ---------------------------------------------------------------------------
// Descriptor model
// ---------------------------------------------------------------------------

data class CacheStats(val count: Int, val sizeBytes: Long, val note: String? = null)

data class CacheEntryVM(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Full text/JSON shown in the 👁 dialog. */
    val viewContent: String,
    /** null ⇒ no 🔄 (entry can't be regenerated). */
    val onRefresh: (suspend (Context) -> Unit)?,
    val onDelete: suspend (Context) -> Unit,
)

data class CacheDescriptor(
    val id: String,
    val icon: String,
    val title: String,
    val helpTopic: String,
    val subject: String,
    val stats: suspend (Context) -> CacheStats,
    val list: suspend (Context) -> List<CacheEntryVM>,
    val clearAll: suspend (Context) -> Unit,
)

// ---------------------------------------------------------------------------
// Registry — one entry per cache, in display + swipe order
// ---------------------------------------------------------------------------

/** Build the cache registry. Keyed refreshes that need API keys / the view
 *  model (model lists, supported params, AA + OpenRouter pricing) are routed
 *  through [onRefreshKeyed]`(cacheId, entryId)`, dispatched by the nav layer
 *  (see DeveloperRoutes). The public pricing fetchers (LiteLLM / models.dev /
 *  llm-prices / Helicone) are key-free and awaited inline. */
fun cacheRegistry(
    onRefreshKeyed: suspend (cacheId: String, entryId: String) -> Unit,
): List<CacheDescriptor> = listOf(
    CacheDescriptor(
        id = "prompts", icon = "🔖", title = "Prompts", helpTopic = "cache_prompts",
        subject = "Cached internal-prompt responses (48 h)",
        stats = { val l = PromptCache.list(); CacheStats(l.size, l.sumOf { it.sizeBytes }, "48 h TTL") },
        list = {
            PromptCache.list().map { e ->
                CacheEntryVM(
                    id = e.key,
                    title = e.response.ifBlank { "(empty)" },
                    subtitle = "${age(e.timestamp)} · ${formatBytes(e.sizeBytes)}${if (e.stale) " · STALE" else ""}",
                    viewContent = e.response,
                    onRefresh = null,
                    onDelete = { PromptCache.delete(e.key) },
                )
            }
        },
        clearAll = { PromptCache.clearAll() },
    ),
    CacheDescriptor(
        id = "icons", icon = "🐜", title = "Internal-prompt icons", helpTopic = "cache_icons",
        subject = "Per-(name, title) emoji for internal-prompt rows",
        stats = { c -> CacheStats(InternalPromptIconCache.list().size, fileLen(c, "internal_prompt_icons.json"), "persistent") },
        list = {
            InternalPromptIconCache.list().map { le ->
                val en = le.entry
                CacheEntryVM(
                    id = le.key,
                    title = "${en.emoji}  ${le.name}${if (le.title.isNotBlank()) " — ${le.title}" else ""}",
                    subtitle = "${en.providerId}/${en.model} · ${age(en.timestamp)} · ${formatCents(en.inputCost + en.outputCost)} ¢",
                    viewContent = buildString {
                        appendLine("Emoji: ${en.emoji}")
                        appendLine("Model: ${en.providerId}/${en.model}")
                        appendLine("Prompt name: ${en.promptName ?: "—"}")
                        appendLine("Cost: ${formatCents(en.inputCost + en.outputCost)} ¢ (in ${en.inputTokens} / out ${en.outputTokens} tok)")
                        appendLine()
                        appendLine("— Resolved prompt —")
                        appendLine(en.promptText)
                        appendLine()
                        appendLine("— Response —")
                        append(en.responseText)
                    },
                    onRefresh = null,
                    onDelete = { InternalPromptIconCache.delete(le.key) },
                )
            }
        },
        clearAll = { c -> InternalPromptIconCache.clearAll(c) },
    ),
    CacheDescriptor(
        id = "meta", icon = "🏷", title = "Meta (titles / lang-icon)", helpTopic = "cache_meta",
        subject = "Cached report titles + language icons (7 d)",
        stats = { c -> CacheStats(MetaCache.list().size, fileLen(c, "meta_cache.json"), "7 d TTL") },
        list = {
            MetaCache.list().map { e ->
                CacheEntryVM(
                    id = e.key,
                    title = e.value.ifBlank { "(empty)" },
                    subtitle = "${age(e.timestamp)}${if (e.stale) " · STALE" else ""}",
                    viewContent = e.value,
                    onRefresh = null,
                    onDelete = { MetaCache.removeByKey(e.key) },
                )
            }
        },
        clearAll = { c -> MetaCache.clearAll(c) },
    ),
    CacheDescriptor(
        id = "modellists", icon = "📋", title = "Model lists", helpTopic = "cache_modellists",
        subject = "Cached /models response per provider",
        stats = { c -> val l = ModelListCache.list(c); CacheStats(l.size, l.sumOf { it.sizeBytes }, "per provider") },
        list = { c ->
            ModelListCache.list(c).map { p ->
                CacheEntryVM(
                    id = p.providerId,
                    title = AppService.findById(p.providerId)?.id ?: p.providerId,
                    subtitle = "${age(p.fetchedAt)} · ${formatBytes(p.sizeBytes)}",
                    viewContent = p.raw,
                    onRefresh = { onRefreshKeyed("modellists", p.providerId) },
                    onDelete = { ctx -> ModelListCache.delete(ctx, p.providerId) },
                )
            }
        },
        clearAll = { c -> ModelListCache.clearAll(c) },
    ),
    CacheDescriptor(
        id = "pricing", icon = "💲", title = "Pricing tiers", helpTopic = "cache_pricing",
        subject = "Per-source model pricing catalogs",
        stats = { c -> val s = PricingCache.catalogStats(c); CacheStats(s.count { it.entries > 0 }, dirSize(c, "pricing"), "8 sources") },
        list = { c ->
            PricingCache.catalogStats(c).map { s ->
                CacheEntryVM(
                    id = s.name,
                    title = s.name,
                    subtitle = "${s.entries} priced model${if (s.entries == 1) "" else "s"} · " +
                        if (s.fetchedAt > 0) age(s.fetchedAt) else "never fetched",
                    viewContent = "Source: ${s.name}\nEntries: ${s.entries}\n" +
                        "Fetched: ${if (s.fetchedAt > 0) DateUtils.formatDateTime(c, s.fetchedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) else "never"}",
                    onRefresh = run {
                        // No refresh button for a provider switched off under
                        // AI Setup → Info providers (it wouldn't be used anyway).
                        val ipId = when (s.name) {
                            "LiteLLM" -> "litellm"; "models.dev" -> "modelsdev"; "llm-prices" -> "llmprices"
                            "Artificial Analysis" -> "aa"; "llm-stats" -> "llmstats"; "OpenRouter" -> "openrouter"
                            "Requesty" -> "requesty"; "Helicone" -> "helicone"; else -> null
                        }
                        val ipEnabled = ipId == null || (com.ai.model.SettingsHolder.current?.isInfoProviderEnabled(ipId) ?: true)
                        if (!ipEnabled) null else when (s.name) {
                            "LiteLLM" -> { ctx -> PricingCache.fetchLiteLLMPricingOnline(ctx) }
                            "models.dev" -> { ctx -> PricingCache.fetchModelsDevOnline(ctx) }
                            "llm-prices" -> { ctx -> PricingCache.fetchLLMPricesOnline(ctx) }
                            "Helicone" -> { ctx -> PricingCache.fetchHeliconeOnline(ctx) }
                            "Requesty" -> { ctx -> PricingCache.fetchRequestyOnline(ctx) }
                            // AA / llm-stats need their API key, OpenRouter pricing the
                            // OpenRouter key — all routed through the view model.
                            "Artificial Analysis", "llm-stats", "OpenRouter" -> { _ -> onRefreshKeyed("pricing", s.name) }
                            else -> null
                        }
                    },
                    onDelete = { ctx -> PricingCache.deleteTier(ctx, s.name) },
                )
            }
        },
        clearAll = { c -> PricingCache.clearInfoProviderTiers(c) },
    ),
    CacheDescriptor(
        id = "params", icon = "⚙️", title = "Supported params", helpTopic = "cache_params",
        subject = "OpenRouter per-model supported parameters",
        stats = { c -> CacheStats(PricingCache.listSupportedParameters(c).size, fileLen(c, "model_supported_parameters.json"), "OpenRouter") },
        list = { c ->
            PricingCache.listSupportedParameters(c).map { e ->
                CacheEntryVM(
                    id = "${e.provider}:${e.model}",
                    title = "${e.provider} / ${e.model}",
                    subtitle = "${e.supported_parameters?.size ?: 0} param${if (e.supported_parameters?.size == 1) "" else "s"}",
                    viewContent = e.supported_parameters?.joinToString("\n").orEmpty().ifBlank { "(none)" },
                    onRefresh = { onRefreshKeyed("params", "${e.provider}:${e.model}") },
                    onDelete = { ctx -> PricingCache.deleteSupportedParameter(ctx, e.provider, e.model) },
                )
            }
        },
        clearAll = { c ->
            File(c.filesDir, "model_supported_parameters.json").delete()
            PricingCache.clearSupportedParametersCache()
        },
    ),
    CacheDescriptor(
        id = "embeddings", icon = "🧬", title = "Embeddings", helpTopic = "cache_embeddings",
        subject = "Cached RAG / semantic-search vectors",
        stats = { c -> val l = EmbeddingsStore.list(c); CacheStats(l.size, l.sumOf { it.sizeBytes }, "RAG vectors") },
        list = { c ->
            EmbeddingsStore.list(c).map { e ->
                CacheEntryVM(
                    id = e.key,
                    title = "dim ${e.dim}",
                    subtitle = "${formatBytes(e.sizeBytes)} · ${e.key.take(12)}…",
                    viewContent = "Dimensions: ${e.dim}\nFirst values:\n${e.firstValues.joinToString(", ")}",
                    onRefresh = null,
                    onDelete = { ctx -> EmbeddingsStore.delete(ctx, e.key) },
                )
            }
        },
        clearAll = { c -> EmbeddingsStore.clearAll(c) },
    ),
)

// ---------------------------------------------------------------------------
// Hub
// ---------------------------------------------------------------------------

@Composable
fun CachesHubScreen(
    registry: List<CacheDescriptor>,
    onOpenCache: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val resumeTick = com.ai.ui.shared.resumeRefreshTick()
    val stats by produceState(initialValue = emptyMap<String, CacheStats>(), registry, resumeTick) {
        value = withContext(Dispatchers.IO) { registry.associate { it.id to it.stats(context) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "caches", title = "Caches", subject = "Browse and manage every on-disk cache", onBackClick = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(registry, key = { it.id }) { d ->
                val s = stats[d.id]
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenCache(d.id) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(d.icon, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.title, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            val countLabel = s?.let { "${it.count} ${if (it.count == 1) "entry" else "entries"} · ${formatBytes(it.sizeBytes)}" } ?: "…"
                            Text(
                                countLabel + (s?.note?.let { " · $it" } ?: ""),
                                fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                            )
                        }
                        Text("›", fontSize = 18.sp, color = AppColors.TextTertiary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Per-cache entries
// ---------------------------------------------------------------------------

@Composable
fun CacheEntriesScreen(
    registry: List<CacheDescriptor>,
    initialCacheId: String,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentId by remember { mutableStateOf(initialCacheId) }
    val idx = registry.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
    val descriptor = registry[idx]

    var refreshTick by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<CacheEntryVM?>(null) }
    var busyEntryKey by remember { mutableStateOf<String?>(null) }

    val entries by produceState(initialValue = emptyList<CacheEntryVM>(), currentId, refreshTick) {
        value = withContext(Dispatchers.IO) { descriptor.list(context) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .horizontalSwipeNavigation(
                key1 = currentId, key2 = registry.size,
                atFirst = idx == 0, atLast = idx == registry.lastIndex,
                onSwipeLeft = { if (idx < registry.lastIndex) currentId = registry[idx + 1].id },
                onSwipeRight = { if (idx > 0) currentId = registry[idx - 1].id },
            )
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = descriptor.helpTopic,
            title = "${descriptor.icon} ${descriptor.title}",
            subject = descriptor.subject,
            onBackClick = onBack,
            onClear = if (entries.isNotEmpty()) ({ confirmClear = true }) else null
        )

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No entries. Swipe to another cache.", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Text(
                    "${entries.size} ${if (entries.size == 1) "entry" else "entries"} · swipe ← → to step between caches.",
                    fontSize = 11.sp, color = AppColors.TextTertiary, lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            items(entries, key = { it.id }) { entry ->
                val entryKey = "$currentId::${entry.id}"
                CacheEntryRow(
                    entry = entry,
                    busy = busyEntryKey == entryKey,
                    onView = { viewing = entry },
                    onRefresh = entry.onRefresh?.let { refresh ->
                        {
                            scope.launch {
                                busyEntryKey = entryKey
                                try {
                                    withContext(Dispatchers.IO) { refresh(context) }
                                } finally {
                                    if (busyEntryKey == entryKey) busyEntryKey = null
                                    refreshTick++
                                }
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { entry.onDelete(context) }
                            refreshTick++
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    viewing?.let { v ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(v.viewContent.ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AppColors.TextPrimary)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close", maxLines = 1, softWrap = false) } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${descriptor.title}?") },
            text = { Text("Deletes every entry in this cache. Regenerable caches re-fetch on next use; the rest are simply gone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { descriptor.clearAll(context) }
                        refreshTick++
                    }
                }) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

@Composable
private fun CacheEntryRow(
    entry: CacheEntryVM,
    busy: Boolean,
    onView: () -> Unit,
    onRefresh: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val mi = MetadataIconsHolder.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(entry.subtitle, fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onView) { Text(mi.view, fontSize = 15.sp) }
            if (onRefresh != null) {
                IconButton(onClick = onRefresh, enabled = !busy) { Text(mi.reload, fontSize = 15.sp) }
            }
            IconButton(onClick = onDelete) { Text(mi.delete, fontSize = 15.sp) }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun age(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun fileLen(context: Context, name: String): Long =
    File(context.filesDir, name).let { if (it.exists()) it.length() else 0L }

private fun dirSize(context: Context, name: String): Long =
    File(context.filesDir, name).listFiles()?.sumOf { it.length() } ?: 0L

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024.0)
    else -> "$bytes B"
}
