package com.ai.ui.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.Agent
import com.ai.model.ModelSource
import com.ai.model.Settings

private fun formatPrice(pricePerToken: Double): String {
    val perMillion = pricePerToken * 1_000_000
    return when {
        perMillion == 0.0 -> "0.00"
        perMillion < 0.01 -> "<0.01"
        else -> "%.2f".format(perMillion)
    }
}

/**
 * Full-screen model selection screen with pricing columns.
 */
@Composable
fun SelectModelScreen(
    provider: AppService,
    aiSettings: Settings,
    currentModel: String,
    showDefaultOption: Boolean = false,
    onSelectModel: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    onNavigateToProviderModels: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    // For an API-mode provider, hold the model list behind a refresh: kick off the fetch,
    // wait for it to complete (observed via the parent-supplied isRefreshing flag), then
    // unveil the list. A 15s timeout makes sure the screen doesn't hang on a stalled call.
    val providerSource = aiSettings.getProvider(provider).modelSource
    val refreshingState = rememberUpdatedState(isRefreshing)
    var initialRefreshDone by remember(provider.id) {
        mutableStateOf(providerSource != ModelSource.API || onRefresh == null)
    }
    LaunchedEffect(provider.id) {
        if (providerSource == ModelSource.API && onRefresh != null) {
            onRefresh()
            try {
                withTimeout(15_000) {
                    if (!refreshingState.value) {
                        snapshotFlow { refreshingState.value }.first { it }
                    }
                    snapshotFlow { refreshingState.value }.first { !it }
                }
            } catch (_: TimeoutCancellationException) {
                // Stalled fetch: unveil what we have rather than blocking forever.
            }
            initialRefreshDone = true
        }
    }

    val allModels = aiSettings.getModels(provider)
    val filteredModels = remember(searchQuery, allModels) {
        if (searchQuery.isBlank()) allModels
        else { val lq = searchQuery.lowercase(); allModels.filter { it.lowercase().contains(lq) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Select Model", onBackClick = onBack, onAiClick = onNavigateHome)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                text = "${provider.displayName} — ${allModels.size} models",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AppColors.Blue, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Refreshing…", fontSize = 11.sp, color = AppColors.TextTertiary)
            }
            if (onNavigateToProviderModels != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToProviderModels,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                ) { Text("Open Models", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            }
        }

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search models...") },
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { Text("\u2715", color = AppColors.TextTertiary) }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (!initialRefreshDone) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppColors.Blue)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Refreshing model list…", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Model", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            Text("In $/M", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
            Text("Out $/M", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
        }
        HorizontalDivider(color = AppColors.BorderUnfocused)

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (showDefaultOption) {
                item(key = "__default__") {
                    val isSelected = currentModel.isBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (isSelected) AppColors.Indigo.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelectModel("") }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default (use provider setting)", style = MaterialTheme.typography.bodyMedium, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                            Text(aiSettings.getModel(provider), style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }

            items(filteredModels, key = { it }) { modelName ->
                val pricing = PricingCache.getPricing(context, provider, modelName)
                val isSelected = modelName == currentModel
                val priceColor = if (pricing.source == "default") AppColors.TextDim else AppColors.Red

                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (isSelected) AppColors.Indigo.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onSelectModel(modelName) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(modelName, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatPrice(pricing.promptPrice), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = priceColor, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                    Text(formatPrice(pricing.completionPrice), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = priceColor, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
        }
    }
}

/**
 * Full-screen provider selection screen.
 */
@Composable
fun SelectProviderScreen(
    aiSettings: Settings,
    onSelectProvider: (AppService) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    activeOnly: Boolean = false
) {
    BackHandler { onBack() }
    var searchQuery by remember { mutableStateOf("") }
    val allProviders = remember(activeOnly, aiSettings) {
        if (activeOnly) aiSettings.getActiveServices() else AppService.entries
    }
    val filteredProviders = remember(searchQuery, allProviders) {
        if (searchQuery.isBlank()) allProviders
        else { val lq = searchQuery.lowercase(); allProviders.filter { it.displayName.lowercase().contains(lq) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Select Provider", onBackClick = onBack, onAiClick = onNavigateHome)

        Text(
            text = "${allProviders.size} providers",
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search providers...") },
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { Text("\u2715", color = AppColors.TextTertiary) }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = AppColors.BorderUnfocused)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredProviders, key = { it.id }) { provider ->
                val state = aiSettings.getProviderState(provider)
                val stateEmoji = when (state) {
                    "ok" -> "\uD83D\uDD11"
                    "error" -> "\u274C"
                    "inactive" -> "\uD83D\uDCA4"
                    else -> "\u2B55"
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectProvider(provider) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(provider.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stateEmoji, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
        }
    }
}

/**
 * Full-screen agent selection screen with pricing columns.
 */
@Composable
fun SelectAgentScreen(
    aiSettings: Settings,
    onSelectAgent: (Agent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val allAgents = aiSettings.agents
    val filteredAgents = remember(searchQuery, allAgents) {
        if (searchQuery.isBlank()) allAgents
        else {
            val lq = searchQuery.lowercase()
            allAgents.filter { agent ->
                agent.name.lowercase().contains(lq) ||
                    agent.provider.displayName.lowercase().contains(lq) ||
                    aiSettings.getEffectiveModelForAgent(agent).lowercase().contains(lq)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Select Agent", onBackClick = onBack, onAiClick = onNavigateHome)

        Text(
            text = "${allAgents.size} agents",
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search agents...") },
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { Text("\u2715", color = AppColors.TextTertiary) }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Agent", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
            Text("In $/M", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
            Text("Out $/M", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
        }
        HorizontalDivider(color = AppColors.BorderUnfocused)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredAgents, key = { it.id }) { agent ->
                val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
                val pricing = PricingCache.getPricing(context, agent.provider, effectiveModel)
                val priceColor = if (pricing.source == "default") AppColors.TextDim else AppColors.Red

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectAgent(agent) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(agent.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${agent.provider.displayName} \u00B7 $effectiveModel", style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatPrice(pricing.promptPrice), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = priceColor, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                    Text(formatPrice(pricing.completionPrice), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = priceColor, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
        }
    }
}
