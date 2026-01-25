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
