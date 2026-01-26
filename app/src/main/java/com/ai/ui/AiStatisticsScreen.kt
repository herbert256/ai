package com.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Screen displaying AI usage statistics per provider and model combination.
 */
@Composable
fun AiStatisticsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs) }

    var stats by remember { mutableStateOf(settingsPrefs.loadUsageStats()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Statistics",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (stats.isEmpty()) {
            // No statistics yet
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "\uD83D\uDCCA",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No statistics yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Generate AI reports to start tracking usage statistics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        } else {
            // Summary card
            val totalCalls = stats.values.sumOf { it.callCount }
            val totalInput = stats.values.sumOf { it.inputTokens }
            val totalOutput = stats.values.sumOf { it.outputTokens }
            val totalTokens = stats.values.sumOf { it.totalTokens }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A3A4A)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Total Usage",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B9BFF)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatValue(label = "Calls", value = formatNumber(totalCalls))
                        StatValue(label = "Input", value = formatTokens(totalInput))
                        StatValue(label = "Output", value = formatTokens(totalOutput))
                        StatValue(label = "Total", value = formatTokens(totalTokens))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Clear button
            OutlinedButton(
                onClick = {
                    settingsPrefs.clearUsageStats()
                    stats = emptyMap()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Statistics")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // List of provider+model stats
            val sortedStats = stats.values.sortedWith(
                compareBy({ it.provider.displayName }, { it.model })
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedStats) { stat ->
                    StatisticsCard(stat = stat)
                }
            }
        }
    }
}

@Composable
private fun StatValue(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAAAAA)
        )
    }
}

@Composable
private fun StatisticsCard(stat: AiUsageStats) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Provider and model
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stat.provider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B9BFF)
                    )
                    Text(
                        text = stat.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                }
                Text(
                    text = "${stat.callCount} calls",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Token usage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TokenStat(label = "Input", value = stat.inputTokens)
                TokenStat(label = "Output", value = stat.outputTokens)
                TokenStat(label = "Total", value = stat.totalTokens)
            }
        }
    }
}

@Composable
private fun TokenStat(label: String, value: Long) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF888888)
        )
        Text(
            text = formatTokens(value),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCCCCCC)
        )
    }
}

private fun formatNumber(value: Int): String {
    return when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

private fun formatTokens(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

/**
 * Screen displaying AI usage costs based on multi-source pricing.
 * Uses PricingCache for weekly-cached OpenRouter pricing data.
 */
@Composable
fun AiCostsScreen(
    openRouterApiKey: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs) }
    val scope = rememberCoroutineScope()

    // Filter out DUMMY provider stats
    val stats = remember {
        settingsPrefs.loadUsageStats().filterValues { it.provider != com.ai.data.AiService.DUMMY }
    }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var pricingSource by remember { mutableStateOf("") }
    var cacheAge by remember { mutableStateOf("") }
    var pricingReady by remember { mutableStateOf(false) }

    // Function to load/refresh pricing
    suspend fun loadPricing(forceRefresh: Boolean = false) {
        val pricingCache = com.ai.data.PricingCache

        // Ensure LiteLLM pricing is loaded
        pricingCache.refreshLiteLLMPricing(context)

        // Check if we need to refresh OpenRouter pricing
        if (forceRefresh || pricingCache.needsOpenRouterRefresh(context)) {
            if (openRouterApiKey.isNotBlank()) {
                val pricing = pricingCache.fetchOpenRouterPricing(openRouterApiKey)
                if (pricing.isNotEmpty()) {
                    pricingCache.saveOpenRouterPricing(context, pricing)
                }
            }
        }

        // Update UI state
        pricingSource = pricingCache.getPricingStats(context)
        cacheAge = pricingCache.getOpenRouterCacheAge(context)
        pricingReady = true
    }

    // Load pricing data on first launch
    LaunchedEffect(Unit) {
        loadPricing()
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Costs",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF4CAF50))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading pricing data...",
                            color = Color(0xFFAAAAAA)
                        )
                    }
                }
            }
            stats.isEmpty() -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "💰", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No usage data",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Generate AI reports to start tracking costs.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                }
            }
            pricingReady -> {
                val pricingCache = com.ai.data.PricingCache

                // Calculate costs for each stat using cached pricing
                val statsWithCosts = stats.values.map { stat ->
                    val pricing = pricingCache.getPricing(context, stat.provider, stat.model)
                    val inputCost = if (pricing != null) stat.inputTokens * pricing.promptPrice else null
                    val outputCost = if (pricing != null) stat.outputTokens * pricing.completionPrice else null
                    val totalCost = if (inputCost != null && outputCost != null) inputCost + outputCost else null
                    StatWithCost(stat, inputCost, outputCost, totalCost, pricing != null, pricing?.source)
                }

                val totalCost = statsWithCosts.mapNotNull { it.totalCost }.sum()
                val totalInputCost = statsWithCosts.mapNotNull { it.inputCost }.sum()
                val totalOutputCost = statsWithCosts.mapNotNull { it.outputCost }.sum()
                val pricedCount = statsWithCosts.count { it.hasPricing }
                val unpricedCount = statsWithCosts.count { !it.hasPricing }

                // Summary card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A4A3A))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Estimated Cost",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                            // Refresh button
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        isRefreshing = true
                                        loadPricing(forceRefresh = true)
                                        isRefreshing = false
                                    }
                                },
                                enabled = !isRefreshing && openRouterApiKey.isNotBlank()
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color(0xFF4CAF50),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("↻ Refresh", color = Color(0xFF64B5F6), fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = formatCurrency(totalCost),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Input", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                                Text(formatCurrency(totalInputCost), fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC), fontSize = 11.sp)
                            }
                            Column {
                                Text("Output", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                                Text(formatCurrency(totalOutputCost), fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC), fontSize = 11.sp)
                            }
                            Column {
                                Text("Models", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                                Text("$pricedCount priced", color = Color(0xFFCCCCCC))
                            }
                        }
                        if (unpricedCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$unpricedCount model(s) without pricing data",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pricing info with cache age
                Column {
                    Text(
                        text = "Pricing: $pricingSource",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888)
                    )
                    if (cacheAge.isNotEmpty()) {
                        Text(
                            text = "OpenRouter cache: $cacheAge",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Group stats by provider
                val groupedByProvider = statsWithCosts.groupBy { it.stat.provider }
                    .mapValues { (_, models) ->
                        ProviderCostGroup(
                            models = models.sortedByDescending { it.totalCost ?: 0.0 },
                            totalCost = models.mapNotNull { it.totalCost }.sum(),
                            totalInputCost = models.mapNotNull { it.inputCost }.sum(),
                            totalOutputCost = models.mapNotNull { it.outputCost }.sum(),
                            totalCalls = models.sumOf { it.stat.callCount }
                        )
                    }
                    .toList()
                    .sortedByDescending { it.second.totalCost }

                // Track expanded providers
                var expandedProviders by remember { mutableStateOf(setOf<com.ai.data.AiService>()) }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groupedByProvider) { (provider, group) ->
                        ProviderCostCard(
                            provider = provider,
                            group = group,
                            isExpanded = provider in expandedProviders,
                            onToggle = {
                                expandedProviders = if (provider in expandedProviders) {
                                    expandedProviders - provider
                                } else {
                                    expandedProviders + provider
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Group of costs for a provider.
 */
data class ProviderCostGroup(
    val models: List<StatWithCost>,
    val totalCost: Double,
    val totalInputCost: Double,
    val totalOutputCost: Double,
    val totalCalls: Int
)

/**
 * Expandable card showing provider costs.
 */
@Composable
private fun ProviderCostCard(
    provider: com.ai.data.AiService,
    group: ProviderCostGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header row (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        color = Color(0xFF888888),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6B9BFF)
                        )
                        Text(
                            text = "${group.models.size} model${if (group.models.size != 1) "s" else ""} • ${group.totalCalls} calls",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF888888)
                        )
                    }
                }
                Text(
                    text = formatCurrency(group.totalCost),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF4CAF50)
                )
            }

            // Expanded content
            if (isExpanded) {
                HorizontalDivider(color = Color(0xFF404040))
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    group.models.forEach { statWithCost ->
                        CompactModelCostRow(statWithCost = statWithCost)
                    }
                }
            }
        }
    }
}

/**
 * Compact row showing model cost within expanded provider.
 */
@Composable
private fun CompactModelCostRow(statWithCost: StatWithCost) {
    val stat = statWithCost.stat

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stat.model,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${stat.callCount} calls",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888)
                )
                Text(
                    text = "In: ${formatTokens(stat.inputTokens)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888)
                )
                Text(
                    text = "Out: ${formatTokens(stat.outputTokens)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888)
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (statWithCost.hasPricing) {
                Text(
                    text = formatCurrency(statWithCost.totalCost ?: 0.0),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF4CAF50)
                )
                val sourceColor = when (statWithCost.pricingSource) {
                    "openrouter" -> Color(0xFF64B5F6)
                    "litellm" -> Color(0xFFBA68C8)
                    "fallback" -> Color(0xFFFFB74D)
                    else -> Color(0xFF888888)
                }
                Text(
                    text = statWithCost.pricingSource ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = sourceColor
                )
            } else {
                Text(
                    text = "No pricing",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        }
    }
}

// Pricing lookup is now handled by com.ai.data.PricingCache

data class StatWithCost(
    val stat: AiUsageStats,
    val inputCost: Double?,
    val outputCost: Double?,
    val totalCost: Double?,
    val hasPricing: Boolean,
    val pricingSource: String? = null
)

@Composable
private fun CostCard(statWithCost: StatWithCost) {
    val stat = statWithCost.stat

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (statWithCost.hasPricing) Color(0xFF2A2A2A) else Color(0xFF2A2A35)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Provider, model, and cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stat.provider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B9BFF)
                    )
                    Text(
                        text = stat.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (statWithCost.hasPricing) {
                        Text(
                            text = formatCurrency(statWithCost.totalCost ?: 0.0),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF4CAF50)
                        )
                        // Show pricing source with color coding
                        val sourceColor = when (statWithCost.pricingSource) {
                            "openrouter" -> Color(0xFF64B5F6)  // Blue
                            "litellm" -> Color(0xFFBA68C8)     // Purple
                            "fallback" -> Color(0xFFFFB74D)    // Orange
                            else -> Color(0xFF888888)
                        }
                        Text(
                            text = statWithCost.pricingSource ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = sourceColor
                        )
                    } else {
                        Text(
                            text = "No pricing",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                    Text(
                        text = "${stat.callCount} calls",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Token usage and costs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CostTokenStat(
                    label = "Input",
                    tokens = stat.inputTokens,
                    cost = statWithCost.inputCost
                )
                CostTokenStat(
                    label = "Output",
                    tokens = stat.outputTokens,
                    cost = statWithCost.outputCost
                )
                CostTokenStat(
                    label = "Total",
                    tokens = stat.totalTokens,
                    cost = statWithCost.totalCost
                )
            }
        }
    }
}

@Composable
private fun CostTokenStat(label: String, tokens: Long, cost: Double?) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF888888)
        )
        Text(
            text = formatTokens(tokens),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCCCCCC)
        )
        if (cost != null) {
            Text(
                text = formatCurrency(cost),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

private fun formatCurrency(value: Double): String {
    return String.format("$%.11f", value)
}
