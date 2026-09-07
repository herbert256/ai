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
    val recipients = remember(review.id) { review.plan.recipients.groupBy { ReportWorkLimits.origin(it.endpoint) }.filterKeys { it != null } }
    val origins = remember(review.id) { recipients.keys.filterNotNull().toSet() + review.allowedOrigins.orEmpty() }
    var extraEndpoint by remember(review.id) { mutableStateOf("") }
    var extraOrigins by remember(review.id) { mutableStateOf(emptySet<String>()) }
    var restrict by remember(review.id) { mutableStateOf(review.allowedOrigins != null) }
    var selected by remember(review.id) { mutableStateOf(review.allowedOrigins ?: origins) }
    val count = requests.toIntOrNull()
    val dollars = spend.toDoubleOrNull()
    val valid = count != null && count in 1..ReportWorkLimits.MAX_ITEMS &&
        (spend.isBlank() || dollars != null && dollars.isFinite() && dollars > 0) && (!restrict || selected.isNotEmpty())
    AlertDialog(onDismissRequest = { ReportWorkLimits.decline(review.id) },
        title = { Text("Review report work") },
        text = { Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${review.label}: ${review.items} work items. Fallbacks and HTTP retries may add requests. Token usage and the final dollar cost are not known in advance.")
            review.plan.jobs.forEach { Text("• $it") }
            if (review.plan.primaryLaunch) {
                Row { Checkbox(answersOnly,{answersOnly=it}); Text("Answers only — skip titles, icons, language detection and automatic analyses") }
            }
            if (recipients.isNotEmpty()) {
                Text("Eligible recipients, including configured fallbacks", style=MaterialTheme.typography.titleSmall)
                recipients.forEach { (endpoint, workers) -> Text("${workers.map { it.label }.distinct().joinToString()}\n$endpoint", style=MaterialTheme.typography.bodySmall) }
            }
            if (origins.isNotEmpty() || review.allowedOrigins != null || extraOrigins.isNotEmpty()) {
                Row { Checkbox(restrict,{restrict=it}); Text("Restrict this report to selected endpoints") }
                if (restrict) (origins+extraOrigins).sorted().forEach { endpoint -> Row {
                    Checkbox(endpoint in selected,{ selected=if(it) selected+endpoint else selected-endpoint }); Text(endpoint)
                } }
                Text("Restrictions apply to all later HTTP calls for this report, including auxiliary workers and fallbacks. Excluded recipients fail before sending. On-device models do not send HTTP requests.", style=MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(extraEndpoint,{extraEndpoint=it},label={Text("Add another allowed endpoint (optional)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            TextButton(enabled=ReportWorkLimits.origin(extraEndpoint)!=null,onClick={
                ReportWorkLimits.origin(extraEndpoint)?.let { extraOrigins=extraOrigins+it;selected=selected+it;restrict=true;extraEndpoint="" }
            }) { Text("Add endpoint") }
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
            try { withContext(Dispatchers.IO) { ReportWorkLimits.approve(review,count!!,dollars,answersOnly,if(restrict)selected else null) } }
            catch (e: Exception) { error=e.message ?: "Could not save limits" }
        } }) { Text(if(answersOnly) "Run answers" else "Run") } },
        dismissButton = { TextButton(onClick={ReportWorkLimits.decline(review.id)}) { Text("Cancel") } })
}
