package com.ai.ui.report.manage.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

internal data class ResponseChangeAction(
    val icon: String,
    val title: String,
    val description: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
internal fun ResponseChangeActionsScreen(
    title: String,
    subject: String,
    actions: List<ResponseChangeAction>,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_single_result",
            title = title,
            subject = subject,
            onBackClick = onBack
        )
        Column(
            modifier = Modifier.fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions.forEach { action ->
                ResponseChangeActionRow(action)
            }
        }
    }
}

@Composable
private fun ResponseChangeActionRow(action: ResponseChangeAction) {
    val textColor = if (action.enabled) AppColors.TextPrimary else AppColors.TextTertiary
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = action.enabled, onClick = action.onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = action.onClick,
                enabled = action.enabled,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                modifier = Modifier.size(width = 64.dp, height = 48.dp)
            ) {
                Text(action.icon, fontSize = 22.sp, maxLines = 1, softWrap = false)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    action.title,
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    action.description,
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
