package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun UsageStatisticsScreen(
    onClearUsageStatistics: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "usage_statistics", title = "Usage statistics", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Usage statistics", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Wipes the per-(provider, model) call counts, token totals, and accumulated cost. The AI Usage screen empties out, but reports, chats, traces, configuration, and pricing tiers stay.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = {
                            onClearUsageStatistics()
                            Toast.makeText(context, "Usage statistics cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                    ) { Text("Clear Usage Statistics", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
