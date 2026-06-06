package com.ai.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.MetadataDefaults
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun FirstLaunchScreen(
    onImportApiKeys: () -> Unit,
    onAiSetup: () -> Unit,
    onHousekeeping: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(
            helpTopic = "first_launch",
            title = "First launch",
            subject = "Start with setup essentials",
            onBackClick = null
        )
        Spacer(modifier = Modifier.height(4.dp))
        HubCard(icon = MetadataDefaults.KEY, title = "Import API keys", onClick = onImportApiKeys)
        HubCard(icon = MetadataDefaults.AGENT, title = "AI Setup", onClick = onAiSetup)
        HubCard(icon = MetadataDefaults.HOUSEKEEPING, title = "Housekeeping", onClick = onHousekeeping)
        HubCard(icon = MetadataDefaults.SETTINGS, title = "Settings", onClick = onSettings)
        HubCard(icon = MetadataDefaults.INFO, title = "About", onClick = onAbout)
    }
}
