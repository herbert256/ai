package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Developer settings screen for developer-related settings.
 */
@Composable
fun DeveloperSettingsScreen(
    generalSettings: GeneralSettings,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (GeneralSettings) -> Unit,
    onTrackApiCallsChanged: (Boolean) -> Unit = {}
) {
    var developerMode by remember { mutableStateOf(generalSettings.developerMode) }
    var trackApiCalls by remember { mutableStateOf(generalSettings.trackApiCalls) }

    fun saveSettings() {
        onSave(generalSettings.copy(
            developerMode = developerMode,
            trackApiCalls = trackApiCalls
        ))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "Developer settings",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Developer settings card
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
                // Developer mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer mode", color = Color.White)
                        Text(
                            text = "Enable developer features",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                    Switch(
                        checked = developerMode,
                        onCheckedChange = {
                            developerMode = it
                            saveSettings()
                        }
                    )
                }

                HorizontalDivider(color = Color(0xFF404040))

                // Track API calls toggle (disabled when developer mode is off)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Track API calls",
                            color = if (developerMode) Color.White else Color(0xFF666666)
                        )
                        Text(
                            text = "Log all API requests for debugging",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (developerMode) Color(0xFFAAAAAA) else Color(0xFF555555)
                        )
                    }
                    Switch(
                        checked = trackApiCalls,
                        onCheckedChange = {
                            trackApiCalls = it
                            saveSettings()
                            onTrackApiCallsChanged(it)
                        },
                        enabled = developerMode
                    )
                }
            }
        }
    }
}
