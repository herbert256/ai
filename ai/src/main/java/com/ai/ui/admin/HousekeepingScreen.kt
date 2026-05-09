package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun HousekeepingScreen(
    onBackToHome: () -> Unit,
    onNavigateToBackupRestore: () -> Unit = {},
    onNavigateToImportExport: () -> Unit = {},
    onNavigateToRefresh: () -> Unit = {},
    onNavigateToTrimByAge: () -> Unit = {},
    onNavigateToUsageStatistics: () -> Unit = {},
    onNavigateToManualCostOverrides: () -> Unit = {},
    onNavigateToInternalPrompts: () -> Unit = {},
    onNavigateToReset: () -> Unit = {}
) {
    BackHandler { onBackToHome() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "housekeeping", title = "Housekeeping", onBackClick = onBackToHome)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavCard("Backup & Restore", onClick = onNavigateToBackupRestore)
            NavCard("Export & Import", onClick = onNavigateToImportExport)
            NavCard("Refresh", onClick = onNavigateToRefresh)
            NavCard("Trim by age", onClick = onNavigateToTrimByAge)
            NavCard("Usage statistics", onClick = onNavigateToUsageStatistics)
            NavCard("Manual cost overrides", onClick = onNavigateToManualCostOverrides)
            NavCard("Internal prompts", onClick = onNavigateToInternalPrompts)
            NavCard("Reset", onClick = onNavigateToReset)
        }
    }
}

// Local LiteRT models + Local LLMs maintenance moved to AI Setup
// — see ui/settings/LocalRuntimeScreens.kt. They're configuration of
// on-device runtimes, not housekeeping, and naturally belong with
// the rest of the AI configuration cards.

/** Card that doesn't expand — clicking the whole row fires [onClick].
 *  Every Housekeeping row is now a NavCard: each one drills into its
 *  own full screen with its own help topic. */
@Composable
private fun NavCard(title: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Text(">", color = AppColors.Blue, fontSize = 16.sp)
        }
    }
}
