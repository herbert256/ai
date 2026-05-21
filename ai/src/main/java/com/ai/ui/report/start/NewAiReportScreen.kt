package com.ai.ui.report.start

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** Wrapper screen reached from the AI Reports hub's "New AI report"
 *  button. Surfaces the three tap-throughs that used to live in the
 *  hub's "Start" card. Lives in its own screen so the hub itself
 *  stays dashboard-shaped (four list cards + two top buttons + an
 *  All button) without burying the creation entry points two card-
 *  rows deep. */
@Composable
fun NewAiReportScreen(
    onNavigateBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    onNavigateToExamplePrompts: () -> Unit,
    hasExamplePrompts: Boolean
) {
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val hasPromptHistory = remember(refreshTick) {
        SettingsPreferences(
            context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            context.filesDir
        ).loadPromptHistory().isNotEmpty()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            title = "New AI report",
            helpTopic = "new_ai_report_screen",
            onBackClick = onNavigateBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        NewAiReportItem(icon = "🗒", title = "New AI Report",
            enabled = true, onClick = onNavigateToNewReport)
        NewAiReportItem(icon = "🔄", title = "Start with a previous prompt",
            enabled = hasPromptHistory, onClick = onNavigateToPromptHistory)
        if (hasExamplePrompts) {
            NewAiReportItem(icon = "💡", title = "Start with an example prompt",
                enabled = true, onClick = onNavigateToExamplePrompts)
        }
    }
}

@Composable
private fun NewAiReportItem(
    icon: String,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(text = icon, fontSize = 22.sp,
            modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else AppColors.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
