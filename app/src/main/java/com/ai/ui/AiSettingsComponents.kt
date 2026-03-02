package com.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.net.toUri
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import com.ai.data.PricingCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Reusable collapsible card with a clickable title header.
 * Collapsed by default, click to expand/collapse.
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                if (summary != null && !expanded) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = AiColors.TextTertiary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = AiColors.TextTertiary,
                    fontSize = 16.sp
                )
            }
            if (expanded) {
                content()
            }
        }
    }
}

/**
 * Format a per-token price as per-million-token display string.
 */
private fun formatPrice(pricePerToken: Double): String {
    val perMillion = pricePerToken * 1_000_000
    return when {
        perMillion == 0.0 -> "0.00"
        perMillion < 0.01 -> "<0.01"
        else -> "%.2f".format(perMillion)
    }
}

/**
 * Format a pre-computed per-million price as display string.
 */
private fun formatPricePerMillion(perMillion: Double): String {
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
    provider: AiService,
    aiSettings: AiSettings,
    currentModel: String,
    showDefaultOption: Boolean = false,
    onSelectModel: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val allModels = aiSettings.getModels(provider)
    val filteredModels = if (searchQuery.isBlank()) allModels
    else allModels.filter { it.lowercase().contains(searchQuery.lowercase()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Select Model",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = "${provider.displayName} — ${allModels.size} models",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search models...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("✕", color = AiColors.TextTertiary)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Model",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "In $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
            Text(
                text = "Out $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
        }

        HorizontalDivider(color = AiColors.BorderUnfocused)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Optional "Default" row
            if (showDefaultOption) {
                item(key = "__default__") {
                    val isSelected = currentModel.isBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) Color(0xFF6366F1).copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                            .clickable { onSelectModel("") }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Default (use provider setting)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AiColors.Blue,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = aiSettings.getModel(provider),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                    HorizontalDivider(color = AiColors.DividerDark)
                }
            }

            // Model rows
            items(filteredModels, key = { it }) { modelName ->
                val pricing = PricingCache.getPricing(context, provider, modelName)
                val isSelected = modelName == currentModel
                val isDefaultSource = pricing.source == "default"
                val priceColor = if (isDefaultSource) Color(0xFF666666) else Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) Color(0xFF6366F1).copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable { onSelectModel(modelName) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatPrice(pricing.promptPrice),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                    Text(
                        text = formatPrice(pricing.completionPrice),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                }
                HorizontalDivider(color = AiColors.DividerDark)
            }
        }
    }
}

/**
 * Full-screen provider selection screen.
 */
@Composable
fun SelectProviderScreen(
    aiSettings: AiSettings,
    onSelectProvider: (AiService) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val allProviders = AiService.entries
    val filteredProviders = if (searchQuery.isBlank()) allProviders
    else allProviders.filter { it.displayName.lowercase().contains(searchQuery.lowercase()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Select Provider",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = "${allProviders.size} providers",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search providers...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("✕", color = AiColors.TextTertiary)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Provider",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = AiColors.BorderUnfocused)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(filteredProviders, key = { it.id }) { provider ->
                val state = aiSettings.getProviderState(provider)
                val stateEmoji = when (state) {
                    "ok" -> "\uD83D\uDD11"       // 🔑
                    "error" -> "❌"
                    "inactive" -> "\uD83D\uDCA4"  // 💤
                    else -> "\u2B55"              // ⭕
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProvider(provider) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = stateEmoji,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                HorizontalDivider(color = AiColors.DividerDark)
            }
        }
    }
}

/**
 * Full-screen agent selection screen with pricing columns.
 */
@Composable
fun SelectAgentScreen(
    aiSettings: AiSettings,
    onSelectAgent: (AiAgent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val allAgents = aiSettings.agents
    val filteredAgents = if (searchQuery.isBlank()) allAgents
    else allAgents.filter { agent ->
        val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
        agent.name.lowercase().contains(searchQuery.lowercase()) ||
            agent.provider.displayName.lowercase().contains(searchQuery.lowercase()) ||
            effectiveModel.lowercase().contains(searchQuery.lowercase())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Select Agent",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = "${allAgents.size} agents",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search agents...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("✕", color = AiColors.TextTertiary)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Agent",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "In $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
            Text(
                text = "Out $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
        }

        HorizontalDivider(color = AiColors.BorderUnfocused)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(filteredAgents, key = { it.id }) { agent ->
                val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
                val pricing = PricingCache.getPricing(context, agent.provider, effectiveModel)
                val isDefaultSource = pricing.source == "default"
                val priceColor = if (isDefaultSource) Color(0xFF666666) else Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAgent(agent) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${agent.provider.displayName} · $effectiveModel",
                            style = MaterialTheme.typography.bodySmall,
                            color = AiColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = formatPrice(pricing.promptPrice),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                    Text(
                        text = formatPrice(pricing.completionPrice),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                }
                HorizontalDivider(color = AiColors.DividerDark)
            }
        }
    }
}

/**
 * Full-screen swarm selection screen with total pricing columns.
 */
@Composable
fun SelectSwarmScreen(
    aiSettings: AiSettings,
    onSelectSwarm: (AiSwarm) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val allSwarms = aiSettings.swarms
    val filteredSwarms = if (searchQuery.isBlank()) allSwarms
    else allSwarms.filter { it.name.lowercase().contains(searchQuery.lowercase()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Select Swarm",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = "${allSwarms.size} swarms",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search swarms...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("✕", color = AiColors.TextTertiary)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Swarm",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "In $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
            Text(
                text = "Out $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
        }

        HorizontalDivider(color = AiColors.BorderUnfocused)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(filteredSwarms, key = { it.id }) { swarm ->
                var totalInPerMillion = 0.0
                var totalOutPerMillion = 0.0
                var hasRealPricing = false
                swarm.members.forEach { member ->
                    val pricing = PricingCache.getPricing(context, member.provider, member.model)
                    totalInPerMillion += pricing.promptPrice * 1_000_000
                    totalOutPerMillion += pricing.completionPrice * 1_000_000
                    if (pricing.source != "default") hasRealPricing = true
                }
                val priceColor = if (hasRealPricing) Color(0xFFFF6B6B) else Color(0xFF666666)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSwarm(swarm) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = swarm.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${swarm.members.size} members",
                            style = MaterialTheme.typography.bodySmall,
                            color = AiColors.TextTertiary
                        )
                    }
                    Text(
                        text = formatPricePerMillion(totalInPerMillion),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                    Text(
                        text = formatPricePerMillion(totalOutPerMillion),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                }
                HorizontalDivider(color = AiColors.DividerDark)
            }
        }
    }
}

/**
 * Full-screen flock selection screen with total pricing columns.
 */
@Composable
fun SelectFlockScreen(
    aiSettings: AiSettings,
    onSelectFlock: (AiFlock) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val allFlocks = aiSettings.flocks
    val filteredFlocks = if (searchQuery.isBlank()) allFlocks
    else allFlocks.filter { it.name.lowercase().contains(searchQuery.lowercase()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Select Flock",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = "${allFlocks.size} flocks",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search flocks...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("✕", color = AiColors.TextTertiary)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Flock",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "In $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
            Text(
                text = "Out $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
        }

        HorizontalDivider(color = AiColors.BorderUnfocused)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(filteredFlocks, key = { it.id }) { flock ->
                val agents = aiSettings.getAgentsForFlock(flock)
                var totalInPerMillion = 0.0
                var totalOutPerMillion = 0.0
                var hasRealPricing = false
                agents.forEach { agent ->
                    val effectiveModel = aiSettings.getEffectiveModelForAgent(agent)
                    val pricing = PricingCache.getPricing(context, agent.provider, effectiveModel)
                    totalInPerMillion += pricing.promptPrice * 1_000_000
                    totalOutPerMillion += pricing.completionPrice * 1_000_000
                    if (pricing.source != "default") hasRealPricing = true
                }
                val priceColor = if (hasRealPricing) Color(0xFFFF6B6B) else Color(0xFF666666)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFlock(flock) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = flock.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${agents.size} agents",
                            style = MaterialTheme.typography.bodySmall,
                            color = AiColors.TextTertiary
                        )
                    }
                    Text(
                        text = formatPricePerMillion(totalInPerMillion),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                    Text(
                        text = formatPricePerMillion(totalOutPerMillion),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                }
                HorizontalDivider(color = AiColors.DividerDark)
            }
        }
    }
}

/**
 * Navigation card for an AI service.
 */
@Composable
fun AiServiceNavigationCard(
    title: String,
    hasApiKey: Boolean = false,
    providerState: String = if (hasApiKey) "ok" else "not-used",
    showStateDetails: Boolean = false,
    @Suppress("UNUSED_PARAMETER") adminUrl: String = "",
    onEdit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )

            // Provider state indicator (only shown when viewing all providers)
            if (showStateDetails) {
                val stateColor = when (providerState) {
                    "ok" -> AiColors.Green
                    "error" -> Color(0xFFFF5252)
                    "inactive" -> AiColors.TextTertiary
                    else -> Color(0xFF555555)
                }
                Text(
                    text = providerState,
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor
                )
                when (providerState) {
                    "ok" -> Text(text = "\uD83D\uDD11", fontSize = 14.sp) // 🔑
                    "error" -> Text(text = "❌", fontSize = 14.sp)
                    "inactive" -> Text(text = "\uD83D\uDCA4", fontSize = 14.sp) // 💤
                    "not-used" -> Text(text = "\u2B55", fontSize = 14.sp) // ⭕
                }
            }
        }
    }
}

/**
 * Template for AI service settings screens.
 */
@Composable
fun AiServiceSettingsScreenTemplate(
    title: String,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    apiKey: String = "",
    adminUrl: String = "",
    onAdminUrlChange: (String) -> Unit = {},
    onTestApiKey: (suspend () -> String?)? = null,
    onProviderStateChange: ((String) -> Unit)? = null,
    additionalContent: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }

    // Test API key on back navigation, then navigate back
    val handleBack: () -> Unit = {
        if (apiKey.isNotBlank() && onTestApiKey != null) {
            isTesting = true
            coroutineScope.launch {
                val error = onTestApiKey()
                onProviderStateChange?.invoke(if (error == null) "ok" else "error")
                isTesting = false
                onBackToAiSettings()
            }
        } else {
            if (apiKey.isBlank()) onProviderStateChange?.invoke("not-used")
            onBackToAiSettings()
        }
    }

    // Show loading overlay when validating
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AiTitleBar(
                title = title,
                onBackClick = handleBack,
                onAiClick = onBackToHome
            )

            // Admin URL (editable with open button)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Admin URL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = adminUrl,
                            onValueChange = onAdminUrlChange,
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        if (adminUrl.isNotBlank()) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, adminUrl.toUri())
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // Invalid URL
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AiColors.Blue
                                )
                            ) {
                                Text("Open")
                            }
                        }
                    }
                }
            }

            // Additional content
            additionalContent()
        }

        // Loading overlay when testing API key
        if (isTesting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Validating API key...")
                    }
                }
            }
        }
    }
}

/**
 * API key input section for provider settings.
 */
@Composable
fun ApiKeyInputSection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onTestApiKey: (suspend () -> String?)? = null
) {
    var showApiKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val keySummary = if (apiKey.isBlank()) "not set" else "••••${apiKey.takeLast(4)}"
    CollapsibleCard(title = "API Key", summary = keySummary) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    onApiKeyChange(it)
                    testResult = null  // Clear test result when key changes
                },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(
                            text = if (showApiKey) "Hide" else "Show",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            // Test API Key button
            if (onTestApiKey != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                testResult = "API key is empty"
                                testSuccess = false
                                return@Button
                            }
                            isTesting = true
                            testResult = null
                            coroutineScope.launch {
                                val error = onTestApiKey()
                                testResult = error ?: "API key is valid!"
                                testSuccess = error == null
                                isTesting = false
                            }
                        },
                        enabled = !isTesting && apiKey.isNotBlank()
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing...")
                        } else {
                            Text("Test API Key")
                        }
                    }

                    // Test result
                    if (testResult != null) {
                        Text(
                            text = testResult ?: "",
                            color = if (testSuccess) AiColors.Green else Color(0xFFEF5350),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
    }
}

/**
 * Unified Models section combining default model, model list management, and model info settings.
 */
@Composable
fun ModelsSection(
    defaultModel: String,
    onDefaultModelChange: (String) -> Unit,
    onSelectDefaultModel: (() -> Unit)? = null,
    modelSource: ModelSource,
    models: List<String>,
    isLoadingModels: Boolean,
    onModelSourceChange: (ModelSource) -> Unit,
    onModelsChange: (List<String>) -> Unit,
    onFetchModels: () -> Unit,
    modelListUrl: String = "",
    defaultModelListUrl: String = "",
    onModelListUrlChange: (String) -> Unit = {},
    modelsPath: String = "v1/models",
    modelsPathNull: Boolean = false,
    onModelsPathChange: (String) -> Unit = {},
    onModelsPathNullChange: (Boolean) -> Unit = {},
    modelFilter: String = "",
    onModelFilterChange: (String) -> Unit = {},
    modelListFormat: String = "object",
    onModelListFormatChange: (String) -> Unit = {},
    litellmPrefix: String = "",
    onLitellmPrefixChange: (String) -> Unit = {},
    onTestModel: (suspend (String) -> Pair<Boolean, String?>) ? = null,
    onNavigateToTrace: ((String) -> Unit)? = null
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    var newModelName by remember { mutableStateOf("") }
    // Test results: model -> (success, traceFilename)
    val testResults = remember { mutableStateMapOf<String, Pair<Boolean, String?>>() }
    var isTesting by remember { mutableStateOf(false) }
    val testingModels = remember { mutableStateListOf<String>() }
    val testScope = rememberCoroutineScope()

    // Default Model card
    val modelsSummary = if (defaultModel.isNotBlank()) defaultModel else "${models.size} models"
    CollapsibleCard(title = "Default Model", summary = modelsSummary) {
        Text(
            text = "Default model used for API key testing and new agents",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextSecondary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = defaultModel,
                onValueChange = onDefaultModelChange,
                label = { Text("Default Model") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            if (onSelectDefaultModel != null) {
                Button(
                    onClick = onSelectDefaultModel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6366F1)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Select", fontSize = 12.sp)
                }
            }
        }
    }

    // Automatic model list card
    val autoModelCount = if (modelSource == ModelSource.API) models.size else 0
    CollapsibleCard(title = "Automatic model list", summary = "$autoModelCount models") {
        OutlinedTextField(
            value = modelListUrl,
            onValueChange = onModelListUrlChange,
            label = { Text("Model List URL") },
            placeholder = { Text(defaultModelListUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        if (modelListUrl.isBlank() && defaultModelListUrl.isNotBlank()) {
            Text(
                text = "Default: $defaultModelListUrl",
                style = MaterialTheme.typography.bodySmall,
                color = AiColors.TextTertiary
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = if (modelsPathNull) "" else modelsPath,
                onValueChange = { onModelsPathChange(it); onModelsPathNullChange(false) },
                label = { Text("Models Path") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !modelsPathNull,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Blue,
                    unfocusedBorderColor = AiColors.BorderUnfocused
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("None", style = MaterialTheme.typography.bodySmall, color = AiColors.TextTertiary)
                Switch(checked = modelsPathNull, onCheckedChange = onModelsPathNullChange)
            }
        }

        OutlinedTextField(
            value = modelFilter,
            onValueChange = onModelFilterChange,
            label = { Text("Model Filter Regex") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Only models matching this regex are shown") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused
            )
        )

        OutlinedTextField(
            value = litellmPrefix,
            onValueChange = onLitellmPrefixChange,
            label = { Text("LiteLLM Prefix") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused
            )
        )

        // Model list format dropdown
        var listFormatExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = modelListFormat,
                onValueChange = {},
                label = { Text("Model List Format") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { listFormatExpanded = true },
                readOnly = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Blue,
                    unfocusedBorderColor = AiColors.BorderUnfocused
                )
            )
            DropdownMenu(
                expanded = listFormatExpanded,
                onDismissRequest = { listFormatExpanded = false }
            ) {
                listOf("object", "array", "google").forEach { fmt ->
                    DropdownMenuItem(
                        text = { Text(fmt) },
                        onClick = { onModelListFormatChange(fmt); listFormatExpanded = false }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    onModelSourceChange(ModelSource.API)
                    onFetchModels()
                },
                enabled = !isLoadingModels
            ) {
                if (isLoadingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading...")
                } else {
                    Text("Retrieve models")
                }
            }
            if (onTestModel != null && modelSource == ModelSource.API && models.isNotEmpty()) {
                Button(
                    onClick = {
                        testScope.launch {
                            isTesting = true
                            testResults.clear()
                            testingModels.clear()
                            testingModels.addAll(models)
                            models.map { m ->
                                async {
                                    val (success, trace) = onTestModel(m)
                                    testResults[m] = Pair(success, trace)
                                    testingModels.remove(m)
                                }
                            }.awaitAll()
                            isTesting = false
                        }
                    },
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Test")
                }
            }
            if (modelSource == ModelSource.API) {
                Text("Active", color = AiColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (modelSource == ModelSource.API && models.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A3A4A), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (model in testingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFFFF9800),
                                strokeWidth = 2.dp
                            )
                        }
                        val result = testResults[model]
                        if (result != null) {
                            val traceFile = result.second
                            Text(
                                text = if (result.first) "\u2714" else "\u2716",
                                color = if (result.first) AiColors.Green else Color(0xFFFF5252),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = if (traceFile != null && onNavigateToTrace != null) {
                                    Modifier.clickable { onNavigateToTrace(traceFile) }
                                } else Modifier
                            )
                        }
                    }
                }
            }
        }
    }

    // Manual model list card
    val manualModelCount = if (modelSource == ModelSource.MANUAL) models.size else 0
    CollapsibleCard(title = "Manual model list", summary = "$manualModelCount models") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                onModelSourceChange(ModelSource.MANUAL)
                newModelName = ""; showAddDialog = true
            }) {
                Text("+ Add model")
            }
            if (onTestModel != null && modelSource == ModelSource.MANUAL && models.isNotEmpty()) {
                Button(
                    onClick = {
                        testScope.launch {
                            isTesting = true
                            testResults.clear()
                            testingModels.clear()
                            testingModels.addAll(models)
                            models.map { m ->
                                async {
                                    val (success, trace) = onTestModel(m)
                                    testResults[m] = Pair(success, trace)
                                    testingModels.remove(m)
                                }
                            }.awaitAll()
                            isTesting = false
                        }
                    },
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Test")
                }
            }
            if (modelSource == ModelSource.MANUAL) {
                Text("Active", color = AiColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (modelSource == ModelSource.MANUAL && models.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A3A4A), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = model, color = Color.White, modifier = Modifier.weight(1f))
                        if (model in testingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFFFF9800),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        val result = testResults[model]
                        if (result != null) {
                            val traceFile = result.second
                            Text(
                                text = if (result.first) "\u2714" else "\u2716",
                                color = if (result.first) AiColors.Green else Color(0xFFFF5252),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = if (traceFile != null && onNavigateToTrace != null) {
                                    Modifier.clickable { onNavigateToTrace(traceFile) }
                                } else Modifier
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(
                            onClick = { editingModel = model; newModelName = model; showAddDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) { Text("\u270E", color = AiColors.TextSecondary) }
                        IconButton(
                            onClick = { onModelsChange(models.filter { it != model }) },
                            modifier = Modifier.size(32.dp)
                        ) { Text("\u2715", color = Color(0xFFFF6666)) }
                    }
                }
            }
        }
    }

    // Add/Edit model dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; editingModel = null },
            title = {
                Text(
                    if (editingModel != null) "Edit Model" else "Add Model",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = newModelName,
                    onValueChange = { newModelName = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newModelName.isNotBlank()) {
                            val newList = if (editingModel != null) {
                                models.map { if (it == editingModel) newModelName.trim() else it }
                            } else {
                                models + newModelName.trim()
                            }
                            onModelsChange(newList)
                        }
                        showAddDialog = false
                        editingModel = null
                    },
                    enabled = newModelName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; editingModel = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Endpoints section for provider settings - allows CRUD for API endpoints.
 * Uses the provider's hardcoded baseUrl as the default when no endpoints are configured.
 */
@Composable
fun EndpointsSection(
    endpoints: List<AiEndpoint>,
    defaultEndpointUrl: String,  // The hardcoded URL from AiService.baseUrl
    onEndpointsChange: (List<AiEndpoint>) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEndpoint by remember { mutableStateOf<AiEndpoint?>(null) }
    var endpointName by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf("") }

    // Effective endpoints list - if empty, show a virtual "Default" endpoint
    val effectiveEndpoints = if (endpoints.isEmpty()) {
        listOf(AiEndpoint(
            id = "default",
            name = "Default",
            url = defaultEndpointUrl,
            isDefault = true
        ))
    } else {
        endpoints
    }

    val endpointsSummary = "${effectiveEndpoints.size} endpoint${if (effectiveEndpoints.size != 1) "s" else ""}"
    CollapsibleCard(title = "API Endpoints", summary = endpointsSummary) {
            Text(
                text = "Configure custom API endpoints for this provider",
                style = MaterialTheme.typography.bodySmall,
                color = AiColors.TextSecondary
            )

            // Add endpoint button
            Button(
                onClick = {
                    endpointName = ""
                    endpointUrl = defaultEndpointUrl
                    editingEndpoint = null
                    showAddDialog = true
                }
            ) {
                Text("+ Add Endpoint")
            }

            // Endpoint list
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                effectiveEndpoints.forEach { endpoint ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (endpoint.isDefault) Color(0xFF2A4A3A) else Color(0xFF2A3A4A),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Default indicator
                        if (endpoint.isDefault) {
                            Text(
                                text = "★",
                                color = AiColors.Green,
                                fontSize = 14.sp
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = endpoint.name,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (endpoint.isDefault) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = endpoint.url,
                                color = AiColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Only show action buttons for real endpoints (not the virtual default)
                        if (endpoints.isNotEmpty() || endpoint.id != "default") {
                            // Set as default button (only if not already default)
                            if (!endpoint.isDefault) {
                                IconButton(
                                    onClick = {
                                        val newList = endpoints.map { it.copy(isDefault = it.id == endpoint.id) }
                                        onEndpointsChange(newList)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("☆", color = AiColors.TextSecondary)
                                }
                            }

                            // Edit button
                            IconButton(
                                onClick = {
                                    editingEndpoint = endpoint
                                    endpointName = endpoint.name
                                    endpointUrl = endpoint.url
                                    showAddDialog = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("✎", color = AiColors.TextSecondary)
                            }

                            // Delete button (only if more than one endpoint or not the only one)
                            if (endpoints.size > 1 || !endpoint.isDefault) {
                                IconButton(
                                    onClick = {
                                        var newList = endpoints.filter { it.id != endpoint.id }
                                        // If we deleted the default, make the first one default
                                        if (endpoint.isDefault && newList.isNotEmpty()) {
                                            newList = listOf(newList.first().copy(isDefault = true)) + newList.drop(1).map { it.copy(isDefault = false) }
                                        }
                                        onEndpointsChange(newList)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("✕", color = Color(0xFFFF6666))
                                }
                            }
                        }
                    }
                }
            }
    }

    // Add/Edit endpoint dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingEndpoint = null
            },
            title = {
                Text(
                    if (editingEndpoint != null) "Edit Endpoint" else "Add Endpoint",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = endpointName,
                        onValueChange = { endpointName = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g., Custom Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = endpointUrl,
                        onValueChange = { endpointUrl = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://api.example.com/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (endpointName.isNotBlank() && endpointUrl.isNotBlank()) {
                            val currentEditing = editingEndpoint
                            val newList = if (currentEditing != null) {
                                // Editing existing endpoint
                                endpoints.map {
                                    if (it.id == currentEditing.id) {
                                        it.copy(name = endpointName.trim(), url = endpointUrl.trim())
                                    } else {
                                        it
                                    }
                                }
                            } else {
                                // Adding new endpoint
                                val newEndpoint = AiEndpoint(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = endpointName.trim(),
                                    url = endpointUrl.trim(),
                                    isDefault = endpoints.isEmpty()  // First endpoint is default
                                )
                                endpoints + newEndpoint
                            }
                            onEndpointsChange(newList)
                        }
                        showAddDialog = false
                        editingEndpoint = null
                    },
                    enabled = endpointName.isNotBlank() && endpointUrl.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editingEndpoint = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Full-screen screen showing all models across all active providers.
 * Flat searchable list with provider name, model name, and pricing columns.
 */
@Composable
fun SelectAllModelsScreen(
    aiSettings: AiSettings,
    onSelectModel: (AiService, String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val activeServices = aiSettings.getActiveServices()
    var selectedProvider by remember { mutableStateOf<AiService?>(null) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var freeOnly by remember { mutableStateOf(false) }

    val allProviderModels = activeServices
        .flatMap { provider ->
            aiSettings.getModels(provider).map { model -> provider to model }
        }

    val providerFiltered = if (selectedProvider != null) {
        allProviderModels.filter { it.first == selectedProvider }
    } else allProviderModels

    val searchFiltered = if (searchQuery.isBlank()) providerFiltered
    else providerFiltered.filter { (provider, model) ->
        provider.displayName.lowercase().contains(searchQuery.lowercase()) ||
            model.lowercase().contains(searchQuery.lowercase())
    }

    val filteredModels = if (freeOnly) {
        searchFiltered.filter { (provider, model) ->
            val pricing = PricingCache.getPricing(context, provider, model)
            pricing.promptPrice == 0.0 && pricing.completionPrice == 0.0
        }
    } else searchFiltered

    val sortedModels = filteredModels
        .sortedWith(compareBy({ it.first.displayName.lowercase() }, { it.second.lowercase() }))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "All Models",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = if (sortedModels.size == allProviderModels.size)
                "${allProviderModels.size} models across ${activeServices.size} providers"
            else
                "${sortedModels.size} of ${allProviderModels.size} models",
            style = MaterialTheme.typography.bodySmall,
            color = AiColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Provider filter dropdown
        Box {
            OutlinedButton(
                onClick = { providerDropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, AiColors.BorderUnfocused)
            ) {
                Text(
                    text = selectedProvider?.displayName ?: "All Providers",
                    modifier = Modifier.weight(1f),
                    color = if (selectedProvider != null) Color.White else AiColors.TextTertiary
                )
                Text("\u25BE", color = AiColors.TextTertiary)
            }
            DropdownMenu(
                expanded = providerDropdownExpanded,
                onDismissRequest = { providerDropdownExpanded = false },
                modifier = Modifier.background(Color(0xFF2D2D2D))
            ) {
                DropdownMenuItem(
                    text = { Text("All Providers", color = if (selectedProvider == null) AiColors.Blue else Color.White) },
                    onClick = { selectedProvider = null; providerDropdownExpanded = false }
                )
                activeServices.sortedBy { it.displayName.lowercase() }.forEach { provider ->
                    val modelCount = aiSettings.getModels(provider).size
                    DropdownMenuItem(
                        text = { Text("${provider.displayName} ($modelCount)", color = if (selectedProvider == provider) AiColors.Blue else Color.White) },
                        onClick = { selectedProvider = provider; providerDropdownExpanded = false }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search models...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AiColors.Blue,
                unfocusedBorderColor = AiColors.BorderUnfocused,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("\u2715", color = AiColors.TextTertiary)
                    }
                }
            }
        )

        // Free only switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Free models only",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Switch(
                checked = freeOnly,
                onCheckedChange = { freeOnly = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AiColors.Blue,
                    uncheckedThumbColor = AiColors.TextTertiary,
                    uncheckedTrackColor = AiColors.BorderUnfocused
                )
            )
        }

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Model",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "In $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
            Text(
                text = "Out $/M",
                style = MaterialTheme.typography.labelSmall,
                color = AiColors.TextTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
        }

        HorizontalDivider(color = AiColors.BorderUnfocused)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(sortedModels, key = { "${it.first.id}:${it.second}" }) { (provider, model) ->
                val pricing = PricingCache.getPricing(context, provider, model)
                val isDefaultSource = pricing.source == "default"
                val priceColor = if (isDefaultSource) Color(0xFF666666) else Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectModel(provider, model) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selectedProvider == null) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = AiColors.TextTertiary,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        text = formatPrice(pricing.promptPrice),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                    Text(
                        text = formatPrice(pricing.completionPrice),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = priceColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(70.dp)
                    )
                }
                HorizontalDivider(color = AiColors.DividerDark)
            }
        }
    }
}
