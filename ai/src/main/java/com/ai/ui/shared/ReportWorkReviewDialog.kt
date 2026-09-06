package com.ai.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    val count = requests.toIntOrNull()
    val dollars = spend.toDoubleOrNull()
    val valid = count != null && count in 1..ReportWorkLimits.MAX_ITEMS &&
        (spend.isBlank() || dollars != null && dollars.isFinite() && dollars > 0)
    AlertDialog(onDismissRequest = { ReportWorkLimits.decline(review.id) },
        title = { Text("Review report work") },
        text = { Column {
            Text("${review.label}: ${review.items} work items. Workers, fallbacks, HTTP retries and metadata may add requests. Each batch queues at most 64 items at once. A reliable dollar estimate is unavailable until token usage is known.")
            OutlinedTextField(requests, { requests=it }, label={Text("Additional HTTP requests (1–5000)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            OutlinedTextField(spend, { spend=it }, label={Text("Additional spend stop in USD (optional)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
            Text("The request limit covers this report. The spend stop uses recorded costs; calls already in flight can exceed it. Current worker and prompt settings are saved for replay.")
            error?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled=valid, onClick={ scope.launch {
            try { withContext(Dispatchers.IO) { ReportWorkLimits.approve(review,count!!,dollars) } }
            catch (e: Exception) { error=e.message ?: "Could not save limits" }
        } }) { Text("Run") } },
        dismissButton = { TextButton(onClick={ReportWorkLimits.decline(review.id)}) { Text("Cancel") } })
}
