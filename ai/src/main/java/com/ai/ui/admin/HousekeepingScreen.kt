package com.ai.ui.admin

import android.content.Context
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
import com.ai.data.*
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*

@Composable
fun HousekeepingScreen(
    onBackToHome: () -> Unit
) {
    BackHandler { onBackToHome() }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Housekeeping", onBackClick = onBackToHome, onAiClick = onBackToHome)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ===== Clean Up Card =====
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clean Up", fontWeight = FontWeight.Bold, color = Color.White)

                    OutlinedButton(onClick = {
                        val deleted = ApiTracer.deleteTracesOlderThan(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                        Toast.makeText(context, "Deleted $deleted old traces", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Delete Traces Older Than 7 Days", maxLines = 1, softWrap = false) }

                    OutlinedButton(onClick = {
                        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                        val reports = ReportStorage.getAllReports(context).filter { it.timestamp < cutoff }
                        reports.forEach { ReportStorage.deleteReport(context, it.id) }
                        Toast.makeText(context, "Deleted ${reports.size} old reports", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Delete Reports Older Than 30 Days", maxLines = 1, softWrap = false) }

                    OutlinedButton(onClick = {
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                        val settingsPrefs = SettingsPreferences(prefs, context.filesDir)
                        settingsPrefs.clearUsageStats()
                        Toast.makeText(context, "Usage statistics cleared", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Clear Usage Statistics", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
