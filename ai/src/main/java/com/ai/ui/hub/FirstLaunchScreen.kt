package com.ai.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.MetadataDefaults
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun FirstLaunchScreen(
    onImportApiKeys: () -> Unit,
    onAiSetup: () -> Unit,
    onExampleReports: () -> Unit,
    onMainHelp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Extra breathing room below the home icon bar before the title.
        Spacer(modifier = Modifier.height(40.dp))
        TitleBar(
            helpTopic = "first_launch",
            title = "First launch",
            subject = "Read an example or create your first report",
            onBackClick = null
        )
        Spacer(modifier = Modifier.height(4.dp))
        HubCard(icon = MetadataDefaults.KEY, title = "Import API keys", onClick = onImportApiKeys)
        Text("Pick a JSON file like {\"OpenAI\": \"sk-...\"} to add several provider keys at once. The app then refreshes its catalogs and providers and asks for a restart.")
        HubCard(icon = MetadataDefaults.REPORT_ICON, title = "Try an example", onClick = onExampleReports)
        Text("Open a bundled report without an API key. Read the saved answers, compare them, and try choosing a conclusion.")
        HubCard(icon = MetadataDefaults.AGENT, title = "Connect a provider", onClick = onAiSetup)
        Text("Add one provider key in AI Setup, select a chat model, then open Reports → Create report. Start with one or two models and use Answers only in the work review.")
        HubCard(icon = MetadataDefaults.HELP, title = "Help", onClick = onMainHelp)
    }
}
