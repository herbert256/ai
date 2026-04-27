package com.ai.ui.report

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
internal fun ReportSelectAllModelsDialog(aiSettings: Settings, onSelectModel: (AppService, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val activeServices = aiSettings.getActiveServices()
    var selectedProvider by remember { mutableStateOf<AppService?>(null) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    val all = activeServices.flatMap { prov -> aiSettings.getModels(prov).map { prov to it } }
    val providerFiltered = if (selectedProvider != null) all.filter { it.first == selectedProvider } else all
    val filtered = if (search.isBlank()) providerFiltered else providerFiltered.filter { (prov, model) ->
        prov.displayName.lowercase().contains(search.lowercase()) || model.lowercase().contains(search.lowercase())
    }
    val sorted = filtered.sortedWith(compareBy({ it.first.displayName.lowercase() }, { it.second.lowercase() }))

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("All models \u2014 ${all.size}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Box {
                    OutlinedButton(onClick = { providerDropdownExpanded = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, AppColors.BorderUnfocused)) {
                        Text(selectedProvider?.displayName ?: "All Providers", modifier = Modifier.weight(1f), fontSize = 13.sp, color = if (selectedProvider != null) Color.White else AppColors.TextTertiary)
                        Text("\u25BE", color = AppColors.TextTertiary)
                    }
                    DropdownMenu(expanded = providerDropdownExpanded, onDismissRequest = { providerDropdownExpanded = false }, modifier = Modifier.background(Color(0xFF2D2D2D))) {
                        DropdownMenuItem(text = { Text("All Providers", color = if (selectedProvider == null) AppColors.Blue else Color.White, fontSize = 13.sp) },
                            onClick = { selectedProvider = null; providerDropdownExpanded = false })
                        remember(activeServices) { activeServices.sortedBy { it.displayName.lowercase() } }.forEach { provider ->
                            val mc = aiSettings.getModels(provider).size
                            DropdownMenuItem(text = { Text("${provider.displayName} ($mc)", color = if (selectedProvider == provider) AppColors.Blue else Color.White, fontSize = 13.sp) },
                                onClick = { selectedProvider = provider; providerDropdownExpanded = false })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = AppColors.outlinedFieldColors(), trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = AppColors.TextTertiary, fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sorted, key = { "${it.first.id}:${it.second}" }) { (provider, model) ->
                        val p = PricingCache.getPricing(context, provider, model)
                        val real = p.source != "DEFAULT"
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectModel(provider, model) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(model, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(provider, model))
                                    com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(provider, model))
                                }
                                if (selectedProvider == null) Text(provider.displayName, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1)
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
