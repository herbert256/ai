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
    val completionPrice: Double, // Price per token
    val source: String = "unknown"  // Where pricing came from
)

/**
 * Hardcoded fallback pricing for common models (per token, not per million).
 * Prices as of January 2025.
 */
private val FALLBACK_PRICING = mapOf(
    // DeepSeek
    "deepseek-chat" to ModelPricing("deepseek-chat", 0.14e-6, 0.28e-6, "fallback"),
    "deepseek-coder" to ModelPricing("deepseek-coder", 0.14e-6, 0.28e-6, "fallback"),
    "deepseek-reasoner" to ModelPricing("deepseek-reasoner", 0.55e-6, 2.19e-6, "fallback"),
    // Groq
    "llama-3.3-70b-versatile" to ModelPricing("llama-3.3-70b-versatile", 0.59e-6, 0.79e-6, "fallback"),
    "llama-3.1-8b-instant" to ModelPricing("llama-3.1-8b-instant", 0.05e-6, 0.08e-6, "fallback"),
    "llama3-70b-8192" to ModelPricing("llama3-70b-8192", 0.59e-6, 0.79e-6, "fallback"),
    "llama3-8b-8192" to ModelPricing("llama3-8b-8192", 0.05e-6, 0.08e-6, "fallback"),
    "mixtral-8x7b-32768" to ModelPricing("mixtral-8x7b-32768", 0.24e-6, 0.24e-6, "fallback"),
    "gemma2-9b-it" to ModelPricing("gemma2-9b-it", 0.20e-6, 0.20e-6, "fallback"),
    // xAI Grok
    "grok-3" to ModelPricing("grok-3", 3.0e-6, 15.0e-6, "fallback"),
    "grok-3-mini" to ModelPricing("grok-3-mini", 0.30e-6, 0.50e-6, "fallback"),
    "grok-2" to ModelPricing("grok-2", 2.0e-6, 10.0e-6, "fallback"),
    "grok-beta" to ModelPricing("grok-beta", 5.0e-6, 15.0e-6, "fallback"),
    // Mistral
    "mistral-small-latest" to ModelPricing("mistral-small-latest", 0.1e-6, 0.3e-6, "fallback"),
    "mistral-medium-latest" to ModelPricing("mistral-medium-latest", 0.4e-6, 2.0e-6, "fallback"),
    "mistral-large-latest" to ModelPricing("mistral-large-latest", 2.0e-6, 6.0e-6, "fallback"),
    "open-mistral-7b" to ModelPricing("open-mistral-7b", 0.25e-6, 0.25e-6, "fallback"),
    "open-mixtral-8x7b" to ModelPricing("open-mixtral-8x7b", 0.7e-6, 0.7e-6, "fallback"),
    "open-mixtral-8x22b" to ModelPricing("open-mixtral-8x22b", 2.0e-6, 6.0e-6, "fallback"),
    "codestral-latest" to ModelPricing("codestral-latest", 0.3e-6, 0.9e-6, "fallback"),
    // Perplexity
    "sonar" to ModelPricing("sonar", 1.0e-6, 1.0e-6, "fallback"),
    "sonar-pro" to ModelPricing("sonar-pro", 3.0e-6, 15.0e-6, "fallback"),
    "sonar-reasoning" to ModelPricing("sonar-reasoning", 1.0e-6, 5.0e-6, "fallback"),
    // SiliconFlow (estimated based on similar models)
    "Qwen/Qwen2.5-7B-Instruct" to ModelPricing("Qwen/Qwen2.5-7B-Instruct", 0.35e-6, 0.35e-6, "fallback"),
    "Qwen/Qwen2.5-72B-Instruct" to ModelPricing("Qwen/Qwen2.5-72B-Instruct", 1.26e-6, 1.26e-6, "fallback"),
    "deepseek-ai/DeepSeek-V3" to ModelPricing("deepseek-ai/DeepSeek-V3", 0.5e-6, 2.0e-6, "fallback"),
    "deepseek-ai/DeepSeek-R1" to ModelPricing("deepseek-ai/DeepSeek-R1", 0.55e-6, 2.19e-6, "fallback"),
    // Z.AI / ZhipuAI GLM
    "glm-4" to ModelPricing("glm-4", 1.4e-6, 1.4e-6, "fallback"),
    "glm-4-plus" to ModelPricing("glm-4-plus", 0.7e-6, 0.7e-6, "fallback"),
    "glm-4-flash" to ModelPricing("glm-4-flash", 0.007e-6, 0.007e-6, "fallback"),
    "glm-4-long" to ModelPricing("glm-4-long", 0.14e-6, 0.14e-6, "fallback"),
    "glm-4.5-flash" to ModelPricing("glm-4.5-flash", 0.007e-6, 0.007e-6, "fallback"),
    "glm-4.7-flash" to ModelPricing("glm-4.7-flash", 0.007e-6, 0.007e-6, "fallback")
)

/**
 * Parse LiteLLM pricing JSON from assets.
 */
private fun parseLiteLLMPricing(context: android.content.Context): Map<String, ModelPricing> {
    return try {
        val json = context.assets.open("model_prices_and_context_window.json").bufferedReader().use { it.readText() }
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Map<String, Any>>>() {}.type
        val data: Map<String, Map<String, Any>> = gson.fromJson(json, type)

        data.mapNotNull { (modelId, info) ->
            val inputCost = (info["input_cost_per_token"] as? Number)?.toDouble()
            val outputCost = (info["output_cost_per_token"] as? Number)?.toDouble()
            if (inputCost != null && outputCost != null) {
                modelId to ModelPricing(modelId, inputCost, outputCost, "litellm")
            } else null
        }.toMap()
    } catch (e: Exception) {
        android.util.Log.w("AiCosts", "Failed to parse LiteLLM pricing: ${e.message}")
        emptyMap()
    }
}

/**
 * Screen displaying AI usage costs based on multi-source pricing.
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

    // Filter out DUMMY provider stats
    val stats = remember {
        settingsPrefs.loadUsageStats().filterValues { it.provider != com.ai.data.AiService.DUMMY }
    }
    var openRouterPricing by remember { mutableStateOf<Map<String, ModelPricing>>(emptyMap()) }
    var litellmPricing by remember { mutableStateOf<Map<String, ModelPricing>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var pricingSource by remember { mutableStateOf("") }

    // Load pricing data from multiple sources
    LaunchedEffect(Unit) {
        // 1. Load LiteLLM pricing from assets (synchronous, fast)
        litellmPricing = parseLiteLLMPricing(context)

        // 2. Try OpenRouter API if key is available
        if (openRouterApiKey.isNotBlank()) {
            try {
                val api = com.ai.data.AiApiFactory.createOpenRouterModelsApi()
                val response = api.listModelsDetailed("Bearer $openRouterApiKey")
                if (response.isSuccessful) {
                    val models = response.body()?.data ?: emptyList()
                    openRouterPricing = models.mapNotNull { model ->
                        val promptPrice = model.pricing?.prompt?.toDoubleOrNull()
                        val completionPrice = model.pricing?.completion?.toDoubleOrNull()
                        if (promptPrice != null && completionPrice != null) {
                            model.id to ModelPricing(model.id, promptPrice, completionPrice, "openrouter")
                        } else null
                    }.toMap()
                }
            } catch (e: Exception) {
                android.util.Log.w("AiCosts", "OpenRouter API error: ${e.message}")
            }
        }

        // Build source description
        val sources = mutableListOf<String>()
        if (openRouterPricing.isNotEmpty()) sources.add("OpenRouter (${openRouterPricing.size})")
        if (litellmPricing.isNotEmpty()) sources.add("LiteLLM (${litellmPricing.size})")
        sources.add("Fallback (${FALLBACK_PRICING.size})")
        pricingSource = sources.joinToString(" • ")

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
                // Calculate costs for each stat using multi-source pricing
                val statsWithCosts = stats.values.map { stat ->
                    val pricing = findPricingMultiSource(
                        stat.provider, stat.model,
                        openRouterPricing, litellmPricing, FALLBACK_PRICING
                    )
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
                    text = "Pricing: $pricingSource",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )

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
                    .padding(16.dp),
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
            }

            // Expanded content
            if (isExpanded) {
                HorizontalDivider(color = Color(0xFF404040))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stat.model,
                style = MaterialTheme.typography.bodyMedium,
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
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
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

/**
 * Find pricing for a model using multiple sources in order of preference:
 * 1. OpenRouter API
 * 2. LiteLLM JSON from assets
 * 3. Hardcoded fallback values
 */
private fun findPricingMultiSource(
    provider: com.ai.data.AiService,
    model: String,
    openRouterPricing: Map<String, ModelPricing>,
    litellmPricing: Map<String, ModelPricing>,
    fallbackPricing: Map<String, ModelPricing>
): ModelPricing? {
    // Provider prefixes for matching
    val providerPrefixes = when (provider) {
        com.ai.data.AiService.OPENAI -> listOf("openai/", "azure/", "")
        com.ai.data.AiService.ANTHROPIC -> listOf("anthropic/", "")
        com.ai.data.AiService.GOOGLE -> listOf("google/", "gemini/", "vertex_ai/", "")
        com.ai.data.AiService.XAI -> listOf("x-ai/", "xai/", "")
        com.ai.data.AiService.GROQ -> listOf("groq/", "")
        com.ai.data.AiService.DEEPSEEK -> listOf("deepseek/", "")
        com.ai.data.AiService.MISTRAL -> listOf("mistralai/", "mistral/", "")
        com.ai.data.AiService.PERPLEXITY -> listOf("perplexity/", "")
        com.ai.data.AiService.TOGETHER -> listOf("together/", "together_ai/", "")
        com.ai.data.AiService.OPENROUTER -> listOf("")
        com.ai.data.AiService.SILICONFLOW -> listOf("siliconflow/", "")
        com.ai.data.AiService.ZAI -> listOf("zhipuai/", "")
        com.ai.data.AiService.DUMMY -> listOf("")
    }

    // Helper to search in a pricing map with various key formats
    fun searchInMap(pricingMap: Map<String, ModelPricing>): ModelPricing? {
        // Direct match
        pricingMap[model]?.let { return it }

        // Try with provider prefixes
        for (prefix in providerPrefixes) {
            pricingMap["$prefix$model"]?.let { return it }
        }

        // Try partial match
        pricingMap.entries.find { (key, _) ->
            key.endsWith("/$model", ignoreCase = true) ||
            key.equals(model, ignoreCase = true) ||
            model.contains(key.substringAfter("/"), ignoreCase = true)
        }?.let { return it.value }

        return null
    }

    // 1. Try OpenRouter first
    searchInMap(openRouterPricing)?.let { return it }

    // 2. Try LiteLLM
    searchInMap(litellmPricing)?.let { return it }

    // 3. Try fallback
    searchInMap(fallbackPricing)?.let { return it }

    return null
}

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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
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
