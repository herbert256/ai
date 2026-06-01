package com.ai.ui.report.manage.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.WebSearchReplayResult
import com.ai.viewmodel.WebSearchReplayState
import java.util.Locale

@Composable
internal fun WebSearchReplayScreen(
    reportId: String,
    agentId: String,
    modelLabel: String,
    originalResponse: String?,
    state: WebSearchReplayState?,
    onStart: () -> Unit,
    onUseResponse: () -> Unit,
    onTrace: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    LaunchedEffect(reportId, agentId) { onStart() }

    val displayState = state ?: WebSearchReplayState(
        reportId = reportId,
        agentId = agentId,
        result = WebSearchReplayResult.Running,
        isRunning = true
    )

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_single_result",
            title = "Web search replay",
            subject = modelLabel,
            onBackClick = onBack
        )

        displayState.unavailableMessage?.takeIf { it.isNotBlank() }?.let {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
                Text(it, color = AppColors.Red, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    ResponseComparisonPane(
                        title = "Original",
                        badge = "Stored response",
                        body = originalResponse,
                        modifier = Modifier.weight(1f).fillMaxSize()
                    )
                    WebSearchResultPane(
                        result = displayState.result,
                        onUseResponse = onUseResponse,
                        onTrace = onTrace,
                        modifier = Modifier.weight(1f).fillMaxSize()
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    ResponseComparisonPane(
                        title = "Original",
                        badge = "Stored response",
                        body = originalResponse,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    WebSearchResultPane(
                        result = displayState.result,
                        onUseResponse = onUseResponse,
                        onTrace = onTrace,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponseComparisonPane(
    title: String,
    badge: String,
    body: String?,
    modifier: Modifier = Modifier
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(badge, color = AppColors.TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val text = body.orEmpty()
            if (text.isBlank()) {
                Text("No response body.", color = AppColors.TextTertiary, fontSize = 13.sp)
            } else {
                ContentWithThinkSections(analysis = text)
            }
        }
    }
}

@Composable
private fun WebSearchResultPane(
    result: WebSearchReplayResult,
    onUseResponse: () -> Unit,
    onTrace: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${com.ai.ui.shared.LocalMetadataIcons.current.webSearchReplay} Web", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.Blue)
                Spacer(modifier = Modifier.width(8.dp))
                if (result is WebSearchReplayResult.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(webSearchStatus(result), color = AppColors.TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.weight(1f))
                (result as? WebSearchReplayResult.Success)?.cost?.let { cost ->
                    Text("${formatCents(cost)} ¢", color = AppColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                durationMs(result)?.let { ms ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(formatDuration(ms), color = AppColors.TextTertiary, fontSize = 12.sp)
                }
                traceFile(result)?.let { fn ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(com.ai.data.MetadataIconsHolder.current.traces, fontSize = 16.sp, modifier = Modifier.clickable { onTrace(fn) })
                }
            }
            when (result) {
                WebSearchReplayResult.Pending -> Text("Queued…", color = AppColors.TextTertiary, fontSize = 13.sp)
                WebSearchReplayResult.Running -> Text("Running web-search replay…", color = AppColors.TextTertiary, fontSize = 13.sp)
                is WebSearchReplayResult.Error -> Text(result.message, color = AppColors.Red, fontSize = 14.sp)
                is WebSearchReplayResult.Success -> {
                    Button(
                        onClick = onUseResponse,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use this response", maxLines = 1, softWrap = false) }
                    ContentWithThinkSections(analysis = result.response)
                }
            }
        }
    }
}

private fun webSearchStatus(result: WebSearchReplayResult): String = when (result) {
    WebSearchReplayResult.Pending -> "queued"
    WebSearchReplayResult.Running -> "running"
    is WebSearchReplayResult.Success -> "success"
    is WebSearchReplayResult.Error -> "error"
}

private fun durationMs(result: WebSearchReplayResult): Long? = when (result) {
    is WebSearchReplayResult.Success -> result.durationMs
    is WebSearchReplayResult.Error -> result.durationMs
    else -> null
}

private fun traceFile(result: WebSearchReplayResult): String? = when (result) {
    is WebSearchReplayResult.Success -> result.traceFile
    is WebSearchReplayResult.Error -> result.traceFile
    else -> null
}

private fun formatDuration(durationMs: Long): String =
    if (durationMs < 1000) "${durationMs}ms"
    else String.format(Locale.US, "%.1fs", durationMs / 1000.0)
