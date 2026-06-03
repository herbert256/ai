package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.MetadataDefaults
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.IconLinkCard
import com.ai.ui.shared.TitleBar

/** Test hub (Housekeeping → Test). A hub of diagnostic test flows —
 *  currently just "Test all models". Each card drills into its own
 *  full screen; built so more test cards can be added later. */
@Composable
fun TestScreen(
    onBack: () -> Unit,
    onOpenTestAllModels: () -> Unit,
    onOpenStressTest: () -> Unit = {},
    onSettings: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "test", title = "Test", subject = "Diagnostic test flows for models", onBackClick = onBack, onSettings = onSettings)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconLinkCard(MetadataDefaults.TEST, "Test all models", "Probe every configured model and persist the results", onOpenTestAllModels)
            IconLinkCard(MetadataDefaults.BOLT, "Stress test", "Submit a controlled burst of reports to exercise throttles", onOpenStressTest)
        }
    }
}
