package com.ai.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.ReportWorkLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun ReportWorkReviewDialog() {
    val reviews by ReportWorkLimits.reviews.collectAsState()
    val review = reviews.firstOrNull() ?: return
    val scope = rememberCoroutineScope()
    var requests by remember(review.id) { mutableStateOf((review.items.toLong()*4+16).coerceIn(32,5000).toString()) }
    var spend by remember(review.id) { mutableStateOf("") }
    var error by remember(review.id) { mutableStateOf<String?>(null) }
    var answersOnly by remember(review.id) { mutableStateOf(false) }
    var showInstructions by remember(review.id) { mutableStateOf(false) }
    val recipients = remember(review.id) {
        review.plan.recipients.groupBy { recipient ->
            recipient.endpoint.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
        }.filterKeys { it != null }
    }
    val count = requests.toIntOrNull()
    val dollars = spend.toDoubleOrNull()
    val valid = count != null && count in 1..ReportWorkLimits.MAX_ITEMS &&
        (spend.isBlank() || dollars != null && dollars.isFinite() && dollars > 0)
    AlertDialog(onDismissRequest = { ReportWorkLimits.decline(review.id) },
        title = { Text("Review report work") },
        text = { Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${review.label}: ${review.items} work items. Fallbacks and HTTP retries may add requests. Token usage and the final dollar cost are not known in advance.")
            review.plan.jobs.forEach { Text("• $it") }
            if (review.plan.primaryLaunch) {
                Row { Checkbox(answersOnly,{answersOnly=it}); Text("Answers only — skip titles, icons, language detection and automatic analyses") }
            }
            if (recipients.isNotEmpty()) {
                Text("Providers, including configured fallbacks", style=MaterialTheme.typography.titleSmall)
                recipients.forEach { (endpoint, workers) -> Text("${workers.map { it.label }.distinct().joinToString()}\n$endpoint", style=MaterialTheme.typography.bodySmall) }
            }
            if (review.plan.instructions.isNotEmpty()) {
                TextButton(onClick={showInstructions=!showInstructions}) { Text(if(showInstructions) "Hide effective instructions" else "Inspect effective instructions") }
                if(showInstructions) review.plan.instructions.forEach { Text(it, style=MaterialTheme.typography.bodySmall) }
            }
            OutlinedTextField(requests, { requests=it }, label={Text("Additional HTTP requests (1–5000)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            OutlinedTextField(spend, { spend=it }, label={Text("Additional spend stop in USD (optional)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            Text("The spend stop uses recorded costs; calls already in flight can exceed it. Each batch queues at most 64 items at once. Saved settings support later replay.", style=MaterialTheme.typography.bodySmall)
            error?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled=valid, onClick={ scope.launch {
            try { withContext(Dispatchers.IO) { ReportWorkLimits.approve(review,count!!,dollars,answersOnly) } }
            catch (e: Exception) { error=e.message ?: "Could not save limits" }
        } }) { Text(if(answersOnly) "Run answers" else "Run") } },
        dismissButton = { TextButton(onClick={ReportWorkLimits.decline(review.id)}) { Text("Cancel") } })
}
