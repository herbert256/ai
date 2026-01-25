package com.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.TraceFileInfo
import java.text.SimpleDateFormat
import java.util.*

private const val PAGE_SIZE = 25

/**
 * Enum for trace detail sub-screens
 */
enum class TraceDetailSubScreen {
    MAIN,
    POST_DATA,
    RESPONSE_DATA,
    RESPONSE_HEADERS
}

/**
 * Screen showing the list of traced API calls.
 */
@Composable
fun TraceListScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSelectTrace: (String) -> Unit,
    onClearTraces: () -> Unit
) {
    var traceFiles by remember { mutableStateOf(ApiTracer.getTraceFiles()) }
    var currentPage by remember { mutableIntStateOf(0) }

    val totalPages = (traceFiles.size + PAGE_SIZE - 1) / PAGE_SIZE
    val startIndex = currentPage * PAGE_SIZE
    val endIndex = minOf(startIndex + PAGE_SIZE, traceFiles.size)
    val currentPageItems = if (traceFiles.isNotEmpty() && startIndex < traceFiles.size) {
        traceFiles.subList(startIndex, endIndex)
    } else {
        emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "API Trace Log",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Text(
            text = "${traceFiles.size} trace files",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Pagination controls
        if (totalPages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB),
                        disabledContainerColor = Color(0xFF333333)
                    )
                ) {
                    Text("◀ Prev")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Page ${currentPage + 1} of $totalPages",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                    enabled = currentPage < totalPages - 1,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB),
                        disabledContainerColor = Color(0xFF333333)
                    )
                ) {
                    Text("Next ▶")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Table header
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A2A2A)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Hostname",
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = "Date/Time",
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1.2f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Status",
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(0.5f),
                    textAlign = TextAlign.End
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Trace list
        if (traceFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No API traces recorded yet",
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageItems) { traceInfo ->
                    TraceListItem(
                        traceInfo = traceInfo,
                        onClick = { onSelectTrace(traceInfo.filename) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear button
        Button(
            onClick = {
                onClearTraces()
                traceFiles = emptyList()
                currentPage = 0
            },
            enabled = traceFiles.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFCC3333),
                disabledContainerColor = Color(0xFF444444)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear trace container")
        }
    }
}

@Composable
private fun TraceListItem(
    traceInfo: TraceFileInfo,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.US) }
    val statusColor = when {
        traceInfo.statusCode in 200..299 -> Color(0xFF4CAF50)  // Green for success
        traceInfo.statusCode in 400..499 -> Color(0xFFFF9800)  // Orange for client errors
        traceInfo.statusCode >= 500 -> Color(0xFFF44336)       // Red for server errors
        else -> Color.White
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF3A3A3A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = traceInfo.hostname,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.5f)
            )
            Text(
                text = dateFormat.format(Date(traceInfo.timestamp)),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.weight(1.2f),
                textAlign = TextAlign.Center
            )
            Text(
                text = "${traceInfo.statusCode}",
                color = statusColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(0.5f),
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * Screen showing the details of a single API trace.
 */
@Composable
fun TraceDetailScreen(
    filename: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack
) {
    val context = LocalContext.current
    val trace = remember { ApiTracer.readTraceFile(filename) }
    val rawJson = remember { ApiTracer.readTraceFileRaw(filename) ?: "" }
    var currentSubScreen by remember { mutableStateOf(TraceDetailSubScreen.MAIN) }

    if (trace == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Text("Error loading trace file", color = Color.White)
        }
        return
    }

    when (currentSubScreen) {
        TraceDetailSubScreen.MAIN -> {
            TraceDetailMainScreen(
                filename = filename,
                rawJson = rawJson,
                hasPostData = !trace.request.body.isNullOrBlank(),
                hasResponseData = !trace.response.body.isNullOrBlank(),
                hasResponseHeaders = trace.response.headers.isNotEmpty(),
                onBack = onBack,
                onNavigateHome = onNavigateHome,
                onShowPostData = { currentSubScreen = TraceDetailSubScreen.POST_DATA },
                onShowResponseData = { currentSubScreen = TraceDetailSubScreen.RESPONSE_DATA },
                onShowResponseHeaders = { currentSubScreen = TraceDetailSubScreen.RESPONSE_HEADERS },
                onShare = {
                    try {
                        // Write JSON to a temp file
                        val cacheDir = File(context.cacheDir, "shared_traces")
                        cacheDir.mkdirs()
                        val tempFile = File(cacheDir, filename)
                        tempFile.writeText(rawJson)

                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile
                        )

                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "API Trace: ${trace.hostname}")
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share trace data"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        TraceDetailSubScreen.POST_DATA -> {
            DataViewScreen(
                title = "Request Data",
                filename = filename,
                content = trace.request.body ?: "",
                onBack = { currentSubScreen = TraceDetailSubScreen.MAIN }
            )
        }
        TraceDetailSubScreen.RESPONSE_DATA -> {
            DataViewScreen(
                title = "Response Data",
                filename = filename,
                content = trace.response.body ?: "",
                onBack = { currentSubScreen = TraceDetailSubScreen.MAIN }
            )
        }
        TraceDetailSubScreen.RESPONSE_HEADERS -> {
            val headersText = trace.response.headers.entries
                .sortedBy { it.key.lowercase() }
                .joinToString("\n") { "${it.key}: ${it.value}" }
            DataViewScreen(
                title = "Response Headers",
                filename = filename,
                content = headersText,
                onBack = { currentSubScreen = TraceDetailSubScreen.MAIN }
            )
        }
    }
}

@Composable
private fun TraceDetailMainScreen(
    filename: String,
    rawJson: String,
    hasPostData: Boolean,
    hasResponseData: Boolean,
    hasResponseHeaders: Boolean,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onShowPostData: () -> Unit,
    onShowResponseData: () -> Unit,
    onShowResponseHeaders: () -> Unit,
    onShare: () -> Unit
) {
    val prettyJson = remember { ApiTracer.prettyPrintJson(rawJson) }
    val lines = remember(prettyJson) { prettyJson.split("\n") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Trace Detail",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        // Filename display
        Text(
            text = filename,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action buttons row 1: Request data and Response data
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasPostData) {
                Button(
                    onClick = onShowPostData,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Request data", fontSize = 12.sp)
                }
            }
            if (hasResponseData) {
                Button(
                    onClick = onShowResponseData,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Response data", fontSize = 12.sp)
                }
            }
            if (hasResponseHeaders) {
                Button(
                    onClick = onShowResponseHeaders,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Response headers", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Full JSON pretty print - split into lines for LazyColumn to handle large content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line.ifEmpty { " " },  // Empty lines need a space for proper height
                        color = Color(0xFFE0E0E0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Share button
        Button(
            onClick = onShare,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share data")
        }
    }
}

/**
 * Full-screen view for POST or RESPONSE data with copy to clipboard functionality.
 */
@Composable
private fun DataViewScreen(
    title: String,
    filename: String,
    content: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prettyContent = remember { ApiTracer.prettyPrintJson(content) }
    val lines = remember(prettyContent) { prettyContent.split("\n") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = title,
            onBackClick = onBack,
            onAiClick = onBack
        )

        // Filename display
        Text(
            text = filename,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line.ifEmpty { " " },
                        color = Color(0xFFE0E0E0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Copy to clipboard button
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(title, prettyContent)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3366BB)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy to clipboard")
        }
    }
}
