package com.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Screen displaying AI usage statistics per provider and model combination.
 */
@Composable
fun AiStatisticsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onNavigateToCosts: () -> Unit = {}
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

            // Costs and Clear buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNavigateToCosts,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("💰 Costs")
                }
                OutlinedButton(
                    onClick = {
                        settingsPrefs.clearUsageStats()
                        stats = emptyMap()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Statistics")
                }
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
 * Data class for model pricing info.
 */
data class ModelPricing(
    val modelId: String,
    val promptPrice: Double,     // Price per token
    val completionPrice: Double  // Price per token
)

/**
 * Screen displaying AI usage costs based on OpenRouter pricing.
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

    val stats = remember { settingsPrefs.loadUsageStats() }
    var pricingData by remember { mutableStateOf<Map<String, ModelPricing>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fetch pricing data from OpenRouter
    LaunchedEffect(Unit) {
        if (openRouterApiKey.isBlank()) {
            error = "OpenRouter API key not configured. Set it in AI Setup > AI Providers > OpenRouter."
            isLoading = false
            return@LaunchedEffect
        }

        try {
            val api = com.ai.data.AiApiFactory.createOpenRouterModelsApi()
            val response = api.listModelsDetailed("Bearer $openRouterApiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                pricingData = models.mapNotNull { model ->
                    val promptPrice = model.pricing?.prompt?.toDoubleOrNull()
                    val completionPrice = model.pricing?.completion?.toDoubleOrNull()
                    if (promptPrice != null && completionPrice != null) {
                        model.id to ModelPricing(model.id, promptPrice, completionPrice)
                    } else null
                }.toMap()
            } else {
                error = "Failed to fetch pricing: ${response.code()}"
            }
        } catch (e: Exception) {
            error = "Error fetching pricing: ${e.message}"
        }
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

        Spacer(modifier = Modifier.height(16.dp))

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
            error != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A2A))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⚠️", fontSize = 32.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error ?: "Unknown error",
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodyMedium
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
            else -> {
                // Calculate costs for each stat
                val statsWithCosts = stats.values.map { stat ->
                    val pricing = findPricing(stat.provider, stat.model, pricingData)
                    val inputCost = if (pricing != null) stat.inputTokens * pricing.promptPrice else null
                    val outputCost = if (pricing != null) stat.outputTokens * pricing.completionPrice else null
                    val totalCost = if (inputCost != null && outputCost != null) inputCost + outputCost else null
                    StatWithCost(stat, inputCost, outputCost, totalCost, pricing != null)
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
                        Text(
                            text = "Total Estimated Cost",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = formatCurrency(totalCost),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Input", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                                Text(formatCurrency(totalInputCost), color = Color(0xFFCCCCCC))
                            }
                            Column {
                                Text("Output", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                                Text(formatCurrency(totalOutputCost), color = Color(0xFFCCCCCC))
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

                // Pricing info
                Text(
                    text = "Pricing from OpenRouter • ${pricingData.size} models",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // List of costs per model
                val sortedStats = statsWithCosts.sortedByDescending { it.totalCost ?: 0.0 }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedStats) { statWithCost ->
                        CostCard(statWithCost = statWithCost)
                    }
                }
            }
        }
    }
}

/**
 * Find pricing for a model, trying various name mappings.
 */
private fun findPricing(
    provider: com.ai.data.AiService,
    model: String,
    pricingData: Map<String, ModelPricing>
): ModelPricing? {
    // Direct match
    pricingData[model]?.let { return it }

    // Try with provider prefix (OpenRouter format)
    val providerPrefixes = when (provider) {
        com.ai.data.AiService.OPENAI -> listOf("openai/", "")
        com.ai.data.AiService.ANTHROPIC -> listOf("anthropic/", "")
        com.ai.data.AiService.GOOGLE -> listOf("google/", "")
        com.ai.data.AiService.XAI -> listOf("x-ai/", "xai/", "")
        com.ai.data.AiService.GROQ -> listOf("groq/", "")
        com.ai.data.AiService.DEEPSEEK -> listOf("deepseek/", "")
        com.ai.data.AiService.MISTRAL -> listOf("mistralai/", "mistral/", "")
        com.ai.data.AiService.PERPLEXITY -> listOf("perplexity/", "")
        com.ai.data.AiService.TOGETHER -> listOf("together/", "")
        com.ai.data.AiService.OPENROUTER -> listOf("")
        com.ai.data.AiService.SILICONFLOW -> listOf("siliconflow/", "")
        com.ai.data.AiService.ZAI -> listOf("zhipuai/", "")
        com.ai.data.AiService.DUMMY -> listOf("")
    }

    for (prefix in providerPrefixes) {
        pricingData["$prefix$model"]?.let { return it }
    }

    // Try partial match (model name contained in pricing key)
    pricingData.entries.find { (key, _) ->
        key.contains(model, ignoreCase = true) || model.contains(key.substringAfter("/"), ignoreCase = true)
    }?.let { return it.value }

    return null
}

data class StatWithCost(
    val stat: AiUsageStats,
    val inputCost: Double?,
    val outputCost: Double?,
    val totalCost: Double?,
    val hasPricing: Boolean
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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
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
                color = Color(0xFF4CAF50)
            )
        }
    }
}

private fun formatCurrency(value: Double): String {
    return when {
        value >= 1.0 -> String.format("$%.2f", value)
        value >= 0.01 -> String.format("$%.4f", value)
        value >= 0.0001 -> String.format("$%.6f", value)
        value > 0 -> String.format("$%.8f", value)
        else -> "$0.00"
    }
}
