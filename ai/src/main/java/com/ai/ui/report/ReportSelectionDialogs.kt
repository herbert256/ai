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

private fun dlgFmtPrice(pricePerToken: Double): String {
    val m = pricePerToken * 1_000_000
    return when { m == 0.0 -> "0"; m < 0.01 -> "<.01"; else -> "%.2f".format(m) }
}

private fun dlgFmtPriceM(perMillion: Double): String {
    return when { perMillion == 0.0 -> "0"; perMillion < 0.01 -> "<.01"; else -> "%.2f".format(perMillion) }
}

@Composable
internal fun ReportSelectFlockDialog(aiSettings: Settings, onSelectFlock: (Flock) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.flocks
    val filtered = if (search.isBlank()) all else all.filter { it.name.lowercase().contains(search.lowercase()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Select a flock", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = AppColors.outlinedFieldColors(), trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { flock ->
                        // Mirror what expandFlockToModels actually feeds the report: skip
                        // agents whose provider isn't active so the displayed count matches
                        // the worker count shown after Generate.
                        val agents = aiSettings.getAgentsForFlock(flock).filter { aiSettings.isProviderActive(it.provider) }
                        var tIn = 0.0; var tOut = 0.0; var real = false
                        agents.forEach { a ->
                            val p = PricingCache.getPricing(context, a.provider, aiSettings.getEffectiveModelForAgent(a))
                            tIn += p.promptPrice * 1_000_000; tOut += p.completionPrice * 1_000_000; if (p.source != "DEFAULT") real = true
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectFlock(flock) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(flock.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${agents.size}", fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(end = 6.dp))
                            Text("${dlgFmtPriceM(tIn)}/${dlgFmtPriceM(tOut)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) AppColors.Red else AppColors.SurfaceDark, modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
internal fun ReportSelectAgentDialog(aiSettings: Settings, onSelectAgent: (Agent) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.agents
    val filtered = if (search.isBlank()) all else all.filter { a ->
        a.name.lowercase().contains(search.lowercase()) || a.provider.displayName.lowercase().contains(search.lowercase())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Select an agent", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = AppColors.outlinedFieldColors(), trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { agent ->
                        val model = aiSettings.getEffectiveModelForAgent(agent)
                        val p = PricingCache.getPricing(context, agent.provider, model)
                        val real = p.source != "DEFAULT"
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectAgent(agent) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${agent.provider.displayName} \u00B7 $model", fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${dlgFmtPrice(p.promptPrice)}/${dlgFmtPrice(p.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) AppColors.Red else AppColors.SurfaceDark, modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
internal fun ReportSelectSwarmDialog(aiSettings: Settings, onSelectSwarm: (Swarm) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.swarms
    val filtered = if (search.isBlank()) all else all.filter { it.name.lowercase().contains(search.lowercase()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Select a swarm", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = AppColors.outlinedFieldColors(), trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { swarm ->
                        var tIn = 0.0; var tOut = 0.0; var real = false
                        swarm.members.forEach { m ->
                            val p = PricingCache.getPricing(context, m.provider, m.model)
                            tIn += p.promptPrice * 1_000_000; tOut += p.completionPrice * 1_000_000; if (p.source != "DEFAULT") real = true
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectSwarm(swarm) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(swarm.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${swarm.members.size}", fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(end = 6.dp))
                            Text("${dlgFmtPriceM(tIn)}/${dlgFmtPriceM(tOut)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) AppColors.Red else AppColors.SurfaceDark, modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
    }
}

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
                    Text(provider.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { onSelectProvider(provider) }.padding(vertical = 8.dp, horizontal = 4.dp))
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
internal fun ReportSelectModelDialog(provider: AppService, aiSettings: Settings, onSelectModel: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.getModels(provider)
    val filtered = if (search.isBlank()) all else all.filter { it.lowercase().contains(search.lowercase()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("${provider.displayName} \u2014 ${all.size} models", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = AppColors.outlinedFieldColors(), trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it }) { model ->
                        val p = PricingCache.getPricing(context, provider, model)
                        val real = p.source != "DEFAULT"
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectModel(model) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(model, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(provider, model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(provider, model))
                            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(provider, model))
                            Text("${dlgFmtPrice(p.promptPrice)}/${dlgFmtPrice(p.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) AppColors.Red else AppColors.SurfaceDark, modifier = if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = AppColors.Blue, maxLines = 1, softWrap = false) }
            }
        }
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
    /** Optional callback for "Local model" selection. Currently fires
     *  from the Rerank picker only — local moderation requires a
     *  separate MediaPipe TextClassifier model and isn't wired yet. */
    onLocalConfirm: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val activeServices = aiSettings.getActiveServices()
    var providerFilter by remember { mutableStateOf<AppService?>(null) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var typeOnly by remember { mutableStateOf(modelTypeFilter != null) }

    val all = remember(aiSettings) {
        activeServices.flatMap { prov -> aiSettings.getModels(prov).map { prov to it } }
    }
    val providerFiltered = if (providerFilter != null) all.filter { it.first == providerFilter } else all
    val typeFiltered = if (typeOnly && modelTypeFilter != null) {
        providerFiltered.filter { (prov, model) -> aiSettings.getModelType(prov, model) == modelTypeFilter }
    } else providerFiltered
    val searched = if (search.isBlank()) typeFiltered else typeFiltered.filter { (prov, model) ->
        prov.displayName.lowercase().contains(search.lowercase()) || model.lowercase().contains(search.lowercase())
    }
    val sorted = remember(searched) {
        // Stable alphabetical order \u2014 no jumping when the user taps.
        searched.sortedWith(
            compareBy<Pair<AppService, String>> { it.first.displayName.lowercase() }
                .thenBy { it.second.lowercase() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = titleText, onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { providerDropdownExpanded = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, AppColors.BorderUnfocused)) {
                Text(providerFilter?.displayName ?: "All Providers", modifier = Modifier.weight(1f), fontSize = 13.sp,
                    color = if (providerFilter != null) Color.White else AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("\u25be", color = AppColors.TextTertiary)
            }
            DropdownMenu(expanded = providerDropdownExpanded, onDismissRequest = { providerDropdownExpanded = false }, modifier = Modifier.background(Color(0xFF2D2D2D))) {
                DropdownMenuItem(text = { Text("All Providers", color = if (providerFilter == null) AppColors.Blue else Color.White, fontSize = 13.sp) },
                    onClick = { providerFilter = null; providerDropdownExpanded = false })
                remember(activeServices) { activeServices.sortedBy { it.displayName.lowercase() } }.forEach { provider ->
                    val mc = aiSettings.getModels(provider).size
                    DropdownMenuItem(text = { Text("${provider.displayName} ($mc)", color = if (providerFilter == provider) AppColors.Blue else Color.White, fontSize = 13.sp) },
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

        // Local model shortcut — only on the Rerank picker for now.
        // Tap opens a small dropdown of installed MediaPipe TextEmbedder
        // .tflite files; selecting one routes the rerank through cosine
        // similarity instead of a remote API call.
        if (modelTypeFilter == com.ai.data.ModelType.RERANK) {
            val installed = remember { com.ai.data.LocalEmbedder.availableModels(context) }
            var localOpen by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { localOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) {
                    Text(
                        if (installed.isEmpty()) "Local model — none installed"
                        else "Local model (${installed.size} installed)",
                        fontSize = 13.sp, maxLines = 1, softWrap = false
                    )
                }
                DropdownMenu(
                    expanded = localOpen, onDismissRequest = { localOpen = false },
                    modifier = Modifier.background(Color(0xFF2D2D2D))
                ) {
                    if (installed.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Install one in Housekeeping → Local LiteRT models", fontSize = 12.sp, color = AppColors.TextTertiary) },
                            onClick = { localOpen = false }
                        )
                    } else installed.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m, color = Color.White, fontSize = 13.sp) },
                            onClick = { localOpen = false; onLocalConfirm(m) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search models...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
        Text("${sorted.size} of ${all.size} models", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sorted, key = { "${it.first.id}:${it.second}" }) { entry ->
                val (provider, model) = entry
                val isAlreadyAdded = entry in alreadyAdded
                val pricing = aiSettings.getModelPricing(provider, model)
                    ?: PricingCache.getPricing(context, provider, model)
                val real = pricing.source != "DEFAULT"
                val rowAlpha = if (isAlreadyAdded) 0.4f else 1f
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !isAlreadyAdded) { onConfirm(entry) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).alpha(rowAlpha)) {
                        Text(provider.displayName, fontSize = 12.sp, color = AppColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(provider, model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(provider, model))
                            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(provider, model))
                            if (isAlreadyAdded) {
                                Text(" · already added", fontSize = 10.sp, color = AppColors.TextTertiary)
                            }
                        }
                    }
                    Text("${dlgFmtPrice(pricing.promptPrice)}/${dlgFmtPrice(pricing.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (real) AppColors.Red else AppColors.SurfaceDark,
                        modifier = (if (!real) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier).alpha(rowAlpha))
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
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
    val reports by produceState(initialValue = emptyList<com.ai.data.Report>()) {
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
    val tsFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Pick previous report", onBackClick = onBack, onAiClick = onNavigateHome)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(report.title.ifBlank { "(untitled)" }, fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${tsFormat.format(java.util.Date(report.timestamp))}  ·  ${report.agents.size} model${if (report.agents.size == 1) "" else "s"}",
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

