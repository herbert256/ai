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
import kotlin.math.roundToInt

/**
 * General settings screen for app-wide settings.
 */
@Composable
fun GeneralSettingsScreen(
    generalSettings: GeneralSettings,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (GeneralSettings) -> Unit
) {
    var paginationPageSize by remember { mutableFloatStateOf(generalSettings.paginationPageSize.toFloat()) }

    fun saveSettings() {
        onSave(generalSettings.copy(
            paginationPageSize = paginationPageSize.roundToInt()
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
            title = "General",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // General settings card
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
                Text(
                    text = "Display",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // Pagination page size
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Rows per page when pagination", color = Color.White)
                        Text(
                            text = "${paginationPageSize.roundToInt()}",
                            color = Color(0xFF6B9BFF),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Number of items shown per page (5-50)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                    Slider(
                        value = paginationPageSize,
                        onValueChange = { paginationPageSize = it },
                        onValueChangeFinished = { saveSettings() },
                        valueRange = 5f..50f,
                        steps = 8,  // 5, 10, 15, 20, 25, 30, 35, 40, 45, 50
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
