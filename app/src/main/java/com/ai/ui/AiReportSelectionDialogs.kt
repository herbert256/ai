package com.ai.ui

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

// --- Compact report selection popup dialogs ---

internal fun dlgFmtPrice(pricePerToken: Double): String {
    val m = pricePerToken * 1_000_000
    return when { m == 0.0 -> "0"; m < 0.01 -> "<.01"; else -> "%.2f".format(m) }
}

internal fun dlgFmtPriceM(perMillion: Double): String {
    return when { perMillion == 0.0 -> "0"; perMillion < 0.01 -> "<.01"; else -> "%.2f".format(perMillion) }
}

@Composable
internal fun ReportSelectFlockDialog(
    aiSettings: AiSettings,
    onSelectFlock: (AiFlock) -> Unit,
    onDismiss: () -> Unit
) {
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
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF6B9BFF), unfocusedBorderColor = Color(0xFF444444), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = Color(0xFF888888), fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { flock ->
                        val agents = aiSettings.getAgentsForFlock(flock)
                        var tIn = 0.0; var tOut = 0.0; var real = false
                        agents.forEach { a ->
                            val p = com.ai.data.PricingCache.getPricing(context, a.provider, aiSettings.getEffectiveModelForAgent(a))
                            tIn += p.promptPrice * 1_000_000; tOut += p.completionPrice * 1_000_000
                            if (p.source != "DEFAULT") real = true
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectFlock(flock) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(flock.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${agents.size}", fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.padding(end = 6.dp))
                            Text("${dlgFmtPriceM(tIn)}/${dlgFmtPriceM(tOut)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) Color(0xFFFF6B6B) else Color(0xFF2A2A2A), modifier = if (!real) Modifier.background(Color(0xFF666666), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = Color(0xFF555555), thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = Color(0xFF6B9BFF)) }
            }
        }
    }
}

@Composable
internal fun ReportSelectAgentDialog(
    aiSettings: AiSettings,
    onSelectAgent: (AiAgent) -> Unit,
    onDismiss: () -> Unit
) {
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
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF6B9BFF), unfocusedBorderColor = Color(0xFF444444), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = Color(0xFF888888), fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { agent ->
                        val model = aiSettings.getEffectiveModelForAgent(agent)
                        val p = com.ai.data.PricingCache.getPricing(context, agent.provider, model)
                        val real = p.source != "DEFAULT"
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectAgent(agent) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${agent.provider.displayName} \u00B7 $model", fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${dlgFmtPrice(p.promptPrice)}/${dlgFmtPrice(p.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) Color(0xFFFF6B6B) else Color(0xFF2A2A2A), modifier = if (!real) Modifier.background(Color(0xFF666666), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = Color(0xFF555555), thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = Color(0xFF6B9BFF)) }
            }
        }
    }
}

@Composable
internal fun ReportSelectSwarmDialog(
    aiSettings: AiSettings,
    onSelectSwarm: (AiSwarm) -> Unit,
    onDismiss: () -> Unit
) {
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
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF6B9BFF), unfocusedBorderColor = Color(0xFF444444), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = Color(0xFF888888), fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { swarm ->
                        var tIn = 0.0; var tOut = 0.0; var real = false
                        swarm.members.forEach { m ->
                            val p = com.ai.data.PricingCache.getPricing(context, m.provider, m.model)
                            tIn += p.promptPrice * 1_000_000; tOut += p.completionPrice * 1_000_000
                            if (p.source != "DEFAULT") real = true
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectSwarm(swarm) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(swarm.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${swarm.members.size}", fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.padding(end = 6.dp))
                            Text("${dlgFmtPriceM(tIn)}/${dlgFmtPriceM(tOut)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) Color(0xFFFF6B6B) else Color(0xFF2A2A2A), modifier = if (!real) Modifier.background(Color(0xFF666666), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = Color(0xFF555555), thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = Color(0xFF6B9BFF)) }
            }
        }
    }
}

@Composable
internal fun ReportSelectProviderDialog(
    aiSettings: AiSettings,
    onSelectProvider: (com.ai.data.AiService) -> Unit,
    onDismiss: () -> Unit
) {
    val activeProviders = com.ai.data.AiService.entries.filter { aiSettings.getProviderState(it) == "ok" }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.heightIn(max = 400.dp), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("Select a provider", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                activeProviders.forEach { provider ->
                    Text(provider.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { onSelectProvider(provider) }.padding(vertical = 8.dp, horizontal = 4.dp))
                    HorizontalDivider(color = Color(0xFF555555), thickness = 1.dp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = Color(0xFF6B9BFF)) }
            }
        }
    }
}

@Composable
internal fun ReportSelectModelDialog(
    provider: com.ai.data.AiService,
    aiSettings: AiSettings,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
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
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF6B9BFF), unfocusedBorderColor = Color(0xFF444444), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = Color(0xFF888888), fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it }) { model ->
                        val p = com.ai.data.PricingCache.getPricing(context, provider, model)
                        val real = p.source != "DEFAULT"
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectModel(model) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(model, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${dlgFmtPrice(p.promptPrice)}/${dlgFmtPrice(p.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) Color(0xFFFF6B6B) else Color(0xFF2A2A2A), modifier = if (!real) Modifier.background(Color(0xFF666666), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = Color(0xFF555555), thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = Color(0xFF6B9BFF)) }
            }
        }
    }
}

@Composable
internal fun ReportSelectAllModelsDialog(
    aiSettings: AiSettings,
    onSelectModel: (com.ai.data.AiService, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val all = aiSettings.getActiveServices().flatMap { prov -> aiSettings.getModels(prov).map { prov to it } }
    val filtered = if (search.isBlank()) all else all.filter { (prov, model) ->
        prov.displayName.lowercase().contains(search.lowercase()) || model.lowercase().contains(search.lowercase())
    }
    val sorted = filtered.sortedWith(compareBy({ it.first.displayName.lowercase() }, { it.second.lowercase() }))

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.wrapContentWidth().widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.65f), shape = MaterialTheme.shapes.large, color = Color(0xFF2D2D2D)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("All models \u2014 ${all.size}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A3A3A), shape = MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), placeholder = { Text("Search...", fontSize = 14.sp) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF6B9BFF), unfocusedBorderColor = Color(0xFF444444), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("\u2715", color = Color(0xFF888888), fontSize = 12.sp) } })
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sorted, key = { "${it.first.id}:${it.second}" }) { (provider, model) ->
                        val p = com.ai.data.PricingCache.getPricing(context, provider, model)
                        val real = p.source != "DEFAULT"
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectModel(provider, model) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(provider.displayName, fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1)
                            }
                            Text("${dlgFmtPrice(p.promptPrice)}/${dlgFmtPrice(p.completionPrice)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (real) Color(0xFFFF6B6B) else Color(0xFF2A2A2A), modifier = if (!real) Modifier.background(Color(0xFF666666), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                        }
                        HorizontalDivider(color = Color(0xFF555555), thickness = 1.dp)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Back", color = Color(0xFF6B9BFF)) }
            }
        }
    }
}
