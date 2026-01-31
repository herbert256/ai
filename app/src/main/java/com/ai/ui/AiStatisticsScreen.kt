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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService

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
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }

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
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }
    val scope = rememberCoroutineScope()

    val stats = remember {
        settingsPrefs.loadUsageStats()
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

                // Calculate costs for each stat using cached pricing (always returns a value)
                val statsWithCosts = stats.values.map { stat ->
                    val pricing = pricingCache.getPricing(context, stat.provider, stat.model)
                    val inputCost = stat.inputTokens * pricing.promptPrice
                    val outputCost = stat.outputTokens * pricing.completionPrice
                    val totalCost = inputCost + outputCost
                    StatWithCost(stat, inputCost, outputCost, totalCost, true, pricing.source)
                }

                val totalCost = statsWithCosts.sumOf { it.totalCost ?: 0.0 }
                val totalInputCost = statsWithCosts.sumOf { it.inputCost ?: 0.0 }
                val totalOutputCost = statsWithCosts.sumOf { it.outputCost ?: 0.0 }
                val pricedCount = statsWithCosts.size

                // Summary card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A4A3A))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Estimated Cost",
                                style = MaterialTheme.typography.titleSmall,
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
                                enabled = !isRefreshing && openRouterApiKey.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Color(0xFF4CAF50),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("↻ Refresh", color = Color(0xFF64B5F6), fontSize = 11.sp)
                                }
                            }
                        }
                        Text(
                            text = formatCurrency(totalCost),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Input", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                                Text(formatCurrency(totalInputCost), fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC), fontSize = 10.sp)
                            }
                            Column {
                                Text("Output", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                                Text(formatCurrency(totalOutputCost), fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC), fontSize = 10.sp)
                            }
                            Column {
                                Text("Models", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                                Text("$pricedCount priced", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Group stats by provider, sorted by total cost descending
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
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header row (always visible) - only provider name and cost
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B9BFF)
                    )
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

private fun formatCurrency(value: Double): String {
    return String.format("$%.8f", value)
}

/**
 * Cost Configuration Screen - CRUD for manual price overrides per model.
 */
@Composable
fun CostConfigurationScreen(
    aiSettings: AiSettings,
    developerMode: Boolean = false,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack
) {
    BackHandler { onBack() }

    val context = LocalContext.current

    // State for editing existing entries
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editInputPrice by remember { mutableStateOf("") }
    var editOutputPrice by remember { mutableStateOf("") }

    // State for adding new entries
    var showAddDialog by remember { mutableStateOf(false) }
    var newProvider by remember { mutableStateOf(com.ai.data.AiService.OPENAI) }
    var newModel by remember { mutableStateOf("") }
    var newInputPrice by remember { mutableStateOf("") }
    var newOutputPrice by remember { mutableStateOf("") }

    // Get models for the selected provider
    fun getModelsForProvider(provider: com.ai.data.AiService): List<String> {
        return aiSettings.getModels(provider).ifEmpty { listOf(aiSettings.getModel(provider)) }
    }

    // Force recomposition when manual pricing changes
    var refreshTrigger by remember { mutableStateOf(0) }

    // Get manual pricing (reloads when refreshTrigger changes)
    val manualPricing = remember(refreshTrigger) {
        com.ai.data.PricingCache.getAllManualPricing(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Cost Configuration",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Add button
        Button(
            onClick = {
                newProvider = com.ai.data.AiService.OPENAI
                newModel = ""
                newInputPrice = ""
                newOutputPrice = ""
                showAddDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("+ Add Manual Override")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Manual price overrides (per 1M tokens): ${manualPricing.size}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAAAAA)
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (manualPricing.isEmpty()) {
            Text(
                text = "No manual price overrides configured.\nTap '+ Add Manual Override' to add one.",
                color = Color(0xFF888888)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Sort manual pricing entries by provider name, then model name
                val sortedEntries = manualPricing.entries.sortedWith(
                    compareBy(
                        { entry ->
                            val parts = entry.key.split(":", limit = 2)
                            parts.getOrNull(0) ?: ""
                        },
                        { entry ->
                            val parts = entry.key.split(":", limit = 2)
                            parts.getOrNull(1) ?: ""
                        }
                    )
                )

                items(sortedEntries, key = { it.key }) { (manualKey, pricing) ->
                    val parts = manualKey.split(":", limit = 2)
                    if (parts.size == 2) {
                        val providerName = parts[0]
                        val modelName = parts[1]
                        val provider = try {
                            com.ai.data.AiService.valueOf(providerName)
                        } catch (e: Exception) {
                            null
                        }

                        if (provider != null) {
                            val isEditing = editingKey == manualKey

                            CostConfigCard(
                                provider = provider,
                                model = modelName,
                                currentInputPrice = pricing.promptPrice,
                                currentOutputPrice = pricing.completionPrice,
                                isEditing = isEditing,
                                editInputPrice = if (isEditing) editInputPrice else "",
                                editOutputPrice = if (isEditing) editOutputPrice else "",
                                onEditInputPriceChange = { editInputPrice = it },
                                onEditOutputPriceChange = { editOutputPrice = it },
                                onEditClick = {
                                    editingKey = manualKey
                                    val inputPerMillion = pricing.promptPrice * 1_000_000
                                    val outputPerMillion = pricing.completionPrice * 1_000_000
                                    editInputPrice = if (inputPerMillion > 0) String.format("%.2f", inputPerMillion) else ""
                                    editOutputPrice = if (outputPerMillion > 0) String.format("%.2f", outputPerMillion) else ""
                                },
                                onSaveClick = {
                                    val inputPerToken = editInputPrice.toDoubleOrNull()?.let { it / 1_000_000 }
                                    val outputPerToken = editOutputPrice.toDoubleOrNull()?.let { it / 1_000_000 }
                                    if (inputPerToken != null && outputPerToken != null) {
                                        com.ai.data.PricingCache.setManualPricing(
                                            context, provider, modelName,
                                            inputPerToken, outputPerToken
                                        )
                                        editingKey = null
                                        refreshTrigger++
                                    }
                                },
                                onCancelClick = {
                                    editingKey = null
                                },
                                onRemoveClick = {
                                    com.ai.data.PricingCache.removeManualPricing(context, provider, modelName)
                                    refreshTrigger++
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Add Manual Override Dialog
    if (showAddDialog) {
        var providerExpanded by remember { mutableStateOf(false) }
        var modelExpanded by remember { mutableStateOf(false) }
        val availableModels = remember(newProvider) { getModelsForProvider(newProvider) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Manual Override", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Provider dropdown
                    Text("Provider", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                    Box {
                        OutlinedButton(
                            onClick = { providerExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(newProvider.displayName, modifier = Modifier.weight(1f))
                            Text(if (providerExpanded) "▲" else "▼")
                        }
                        DropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false }
                        ) {
                            aiSettings.getActiveServices(developerMode)
                                .sortedBy { it.displayName.lowercase() }
                                .forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            newProvider = provider
                                            newModel = ""  // Reset model when provider changes
                                            providerExpanded = false
                                        }
                                    )
                                }
                        }
                    }

                    // Model dropdown
                    Text("Model", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                    Box {
                        OutlinedButton(
                            onClick = { modelExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = newModel.ifBlank { "Select model..." },
                                modifier = Modifier.weight(1f),
                                color = if (newModel.isBlank()) Color(0xFF888888) else Color.White
                            )
                            Text(if (modelExpanded) "▲" else "▼")
                        }
                        DropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model, fontSize = 14.sp) },
                                    onClick = {
                                        newModel = model
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Prices
                    Text("Prices (per 1M tokens)", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newInputPrice,
                            onValueChange = { newInputPrice = it },
                            label = { Text("Input $") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = newOutputPrice,
                            onValueChange = { newOutputPrice = it },
                            label = { Text("Output $") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val inputPerToken = newInputPrice.toDoubleOrNull()?.let { it / 1_000_000 }
                        val outputPerToken = newOutputPrice.toDoubleOrNull()?.let { it / 1_000_000 }
                        if (newModel.isNotBlank() && inputPerToken != null && outputPerToken != null) {
                            com.ai.data.PricingCache.setManualPricing(
                                context, newProvider, newModel,
                                inputPerToken, outputPerToken
                            )
                            showAddDialog = false
                            refreshTrigger++
                        }
                    },
                    enabled = newModel.isNotBlank() &&
                            newInputPrice.toDoubleOrNull() != null &&
                            newOutputPrice.toDoubleOrNull() != null
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CostConfigCard(
    provider: com.ai.data.AiService,
    model: String,
    currentInputPrice: Double?,
    currentOutputPrice: Double?,
    isEditing: Boolean,
    editInputPrice: String,
    editOutputPrice: String,
    onEditInputPriceChange: (String) -> Unit,
    onEditOutputPriceChange: (String) -> Unit,
    onEditClick: () -> Unit,
    onSaveClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Provider and model name
            Column {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF6B9BFF)
                )
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                // Editing mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editInputPrice,
                        onValueChange = onEditInputPriceChange,
                        label = { Text("Input $/1M") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )
                    OutlinedTextField(
                        value = editOutputPrice,
                        onValueChange = onEditOutputPriceChange,
                        label = { Text("Output $/1M") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancelClick) {
                        Text("Cancel", color = Color(0xFFAAAAAA))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSaveClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Save")
                    }
                }
            } else {
                // Display mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column {
                                Text("Input", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                                Text(
                                    text = if (currentInputPrice != null)
                                        String.format("$%.2f", currentInputPrice * 1_000_000)
                                    else "-",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 12.sp
                                )
                            }
                            Column {
                                Text("Output", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                                Text(
                                    text = if (currentOutputPrice != null)
                                        String.format("$%.2f", currentOutputPrice * 1_000_000)
                                    else "-",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = onRemoveClick) {
                            Text("Remove", color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                        TextButton(onClick = onEditClick) {
                            Text("Edit", color = Color(0xFF64B5F6), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
