package com.ai.ui.report.manage

import com.ai.ui.other.*
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// Genuine Material3 Dialog flows used by the Reports screen — the
// provider picker and the per-provider model picker. Everything else
// in the sibling ReportSelectionScreens.kt is full-screen even though
// the old filename called itself "Dialogs"; these two are the only
// composables in the family that actually call Dialog(...).
//
// They share dlgFmtPrice with ReportSelectionScreens.kt; the helper
// lives there (internal) so both files can format pricing identically.

@Composable
internal fun ReportSelectProviderDialog(aiSettings: Settings, onSelectProvider: (AppService) -> Unit, onDismiss: () -> Unit) {
    val activeProviders = AppService.entries.filter { aiSettings.getProviderState(it) == "ok" }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.heightIn(max = 400.dp), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("Select a provider", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                activeProviders.forEach { provider ->
                    Text(provider.id, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { onSelectProvider(provider) }.padding(vertical = 8.dp, horizontal = 4.dp))
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
internal fun ReportSelectModelDialog(
    provider: AppService,
    aiSettings: Settings,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Models recently picked from a Report-section picker, filtered
     *  to this provider. Surfaced as a "Recent" section above the
     *  main alphabetical list. Empty = no Recent section. */
    recentModels: List<String> = emptyList(),
    /** Optional record hook fired right before [onSelectModel]. Lets
     *  callers persist the pick into the shared recents list. */
    onRecordRecent: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    var search by remember { mutableStateOf("") }
    val all = aiSettings.getModels(provider)
    // Match against the model id and any aliases the provider's
    // /models endpoint declared (Mistral exposes mistral-large-latest
    // → mistral-large-2407 this way; without alias matching a search
    // for "latest" misses the dated id). Aliases live on
    // ModelCapabilities.aliases.
    val capsByModel = aiSettings.getProvider(provider).modelCapabilities
    val filtered = if (search.isBlank()) all else all.filter { id ->
        val q = search.lowercase()
        id.lowercase().contains(q) ||
            capsByModel[id]?.aliases?.any { it.lowercase().contains(q) } == true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("${provider.id} — ${all.size} models", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = AppColors.outlinedFieldColors(), trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                // Shared row renderer so the Recent section and the
                // main list stay byte-identical in look-and-feel.
                @Composable
                fun ModelRow(model: String) {
                    val p = PricingCache.getPricing(context, provider, model)
                    val real = p.source != "DEFAULT"
                    // Deprecation badge: Mistral ships an ISO date on
                    // entries past their EOL, OpenRouter ships
                    // expiration_date on its detailed list. Either
                    // one trips a tiny ⚠ in the row so the user
                    // sees "this still works but the upstream
                    // plans to pull it" before they pin it to a
                    // saved Agent / Swarm.
                    val deprecation = capsByModel[model]?.deprecationDate
                    // Benched by a >1h 429 (ModelCooldownStore) — dim + block taps.
                    val benchedUntil = cooldowns["${provider.id}:$model"]
                        ?.takeIf { it > System.currentTimeMillis() }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = benchedUntil == null) {
                                onRecordRecent?.invoke(model)
                                onSelectModel(model)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                            .alpha(if (benchedUntil != null) 0.4f else 1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (deprecation != null) {
                                Text("⚠", fontSize = 12.sp, color = AppColors.Orange, modifier = Modifier.padding(end = 2.dp))
                            }
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(provider, model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(provider, model))
                            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(provider, model))
                            Text("${dlgFmtPrice(p.promptPrice)} / ${dlgFmtPrice(p.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) AppColors.Red else AppColors.SurfaceDark, modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        if (benchedUntil != null) {
                            Text(
                                com.ai.data.ModelCooldownStore.cooldownCaption(benchedUntil),
                                fontSize = 10.sp, color = AppColors.Orange, maxLines = 1
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
                // Filter recents to this provider's catalog so we don't
                // surface a model the user deleted / the provider
                // dropped, and don't trim by the live search box —
                // recents are a quick-access shortcut.
                val recentForProvider = recentModels.filter { it in all }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (recentForProvider.isNotEmpty()) {
                        item {
                            Text(
                                "Recent",
                                fontSize = 11.sp,
                                color = AppColors.TextTertiary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 4.dp)
                            )
                        }
                        items(recentForProvider, key = { "recent:$it" }) { model -> ModelRow(model) }
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
                    items(filtered, key = { it }) { model -> ModelRow(model) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
    }
}
