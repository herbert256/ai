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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.ai.data.ApiTracer
import com.ai.data.TraceFileInfo
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enum for trace detail content views
 */
enum class TraceContentView {
    ALL,
    REQUEST_HEADERS,
    RESPONSE_HEADERS,
    REQUEST_DATA,
    RESPONSE_DATA
}

// --- JSON Tree Model & Parser ---

enum class JsonNodeType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

data class JsonTreeNode(
    val key: String?,
    val type: JsonNodeType,
    val value: String? = null,
    val children: List<JsonTreeNode> = emptyList()
)

private fun parseJsonElement(key: String?, element: JsonElement): JsonTreeNode {
    return when {
        element is JsonObject -> {
            val children = element.entrySet().map { entry -> parseJsonElement(entry.key, entry.value) }
            JsonTreeNode(key = key, type = JsonNodeType.OBJECT, children = children)
        }
        element is JsonArray -> {
            val children = mutableListOf<JsonTreeNode>()
            for (i in 0 until element.size()) {
                children.add(parseJsonElement("$i", element.get(i)))
            }
            JsonTreeNode(key = key, type = JsonNodeType.ARRAY, children = children)
        }
        element is JsonNull -> JsonTreeNode(key = key, type = JsonNodeType.NULL, value = "null")
        element is JsonPrimitive && element.isBoolean ->
            JsonTreeNode(key = key, type = JsonNodeType.BOOLEAN, value = element.asBoolean.toString())
        element is JsonPrimitive && element.isNumber ->
            JsonTreeNode(key = key, type = JsonNodeType.NUMBER, value = element.asNumber.toString())
        else -> JsonTreeNode(key = key, type = JsonNodeType.STRING, value = element.asString)
    }
}

private fun parseJsonTree(jsonString: String): List<JsonTreeNode>? {
    return try {
        val element = JsonParser().parse(jsonString)
        when {
            element is JsonObject -> {
                element.entrySet().map { entry -> parseJsonElement(entry.key, entry.value) }
            }
            element is JsonArray -> {
                val result = mutableListOf<JsonTreeNode>()
                for (i in 0 until element.size()) {
                    result.add(parseJsonElement("$i", element.get(i)))
                }
                result
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

// --- JSON Tree View Composable ---

private val JsonKeyColor = Color(0xFF82AAFF)
private val JsonStringColor = Color(0xFFC3E88D)
private val JsonNumberColor = Color(0xFFF78C6C)
private val JsonBooleanColor = Color(0xFFC792EA)
private val JsonNullColor = Color(0xFF888888)
private val JsonBraceColor = Color(0xFFE0E0E0)

@Composable
private fun JsonTreeView(nodes: List<JsonTreeNode>, isRootArray: Boolean = false) {
    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (isRootArray) {
            item { Text("[", color = JsonBraceColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        } else {
            item { Text("{", color = JsonBraceColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
        jsonTreeItems(this, nodes, depth = 1, pathPrefix = "", expandedPaths = expandedPaths)
        if (isRootArray) {
            item { Text("]", color = JsonBraceColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        } else {
            item { Text("}", color = JsonBraceColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

private fun jsonTreeItems(
    listScope: androidx.compose.foundation.lazy.LazyListScope,
    nodes: List<JsonTreeNode>,
    depth: Int,
    pathPrefix: String,
    expandedPaths: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>
) {
    nodes.forEachIndexed { index, node ->
        val path = if (pathPrefix.isEmpty()) (node.key ?: "$index") else "$pathPrefix.${node.key ?: "$index"}"
        val isLast = index == nodes.lastIndex

        when (node.type) {
            JsonNodeType.OBJECT, JsonNodeType.ARRAY -> {
                val isExpanded = expandedPaths.getOrPut(path) { true }
                val bracket = if (node.type == JsonNodeType.OBJECT) "{" else "["
                val closeBracket = if (node.type == JsonNodeType.OBJECT) "}" else "]"
                val count = node.children.size
                val comma = if (isLast) "" else ","

                listScope.item(key = path) {
                    val indent = "  ".repeat(depth)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedPaths[path] = !isExpanded },
                        verticalAlignment = Alignment.Top
                    ) {
                        if (isExpanded) {
                            val text = buildAnnotatedString {
                                append(indent)
                                withStyle(SpanStyle(color = JsonBraceColor)) { append("▼ ") }
                                if (node.key != null && !node.key.all { it.isDigit() }) {
                                    withStyle(SpanStyle(color = JsonKeyColor)) { append("\"${node.key}\"") }
                                    withStyle(SpanStyle(color = JsonBraceColor)) { append(": ") }
                                }
                                withStyle(SpanStyle(color = JsonBraceColor)) { append(bracket) }
                            }
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        } else {
                            val text = buildAnnotatedString {
                                append(indent)
                                withStyle(SpanStyle(color = JsonBraceColor)) { append("▶ ") }
                                if (node.key != null && !node.key.all { it.isDigit() }) {
                                    withStyle(SpanStyle(color = JsonKeyColor)) { append("\"${node.key}\"") }
                                    withStyle(SpanStyle(color = JsonBraceColor)) { append(": ") }
                                }
                                withStyle(SpanStyle(color = JsonBraceColor)) {
                                    append("$bracket...$closeBracket ")
                                }
                                withStyle(SpanStyle(color = JsonNullColor)) {
                                    append("($count ${if (count == 1) "item" else "items"})$comma")
                                }
                            }
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }

                if (isExpanded) {
                    jsonTreeItems(listScope, node.children, depth + 1, path, expandedPaths)
                    listScope.item(key = "$path-close") {
                        Text(
                            text = "${"  ".repeat(depth)}$closeBracket$comma",
                            color = JsonBraceColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            else -> {
                listScope.item(key = path) {
                    val indent = "  ".repeat(depth)
                    val comma = if (isLast) "" else ","
                    val text = buildAnnotatedString {
                        append(indent)
                        if (node.key != null && !node.key.all { it.isDigit() }) {
                            withStyle(SpanStyle(color = JsonKeyColor)) { append("\"${node.key}\"") }
                            withStyle(SpanStyle(color = JsonBraceColor)) { append(": ") }
                        }
                        val valueColor = when (node.type) {
                            JsonNodeType.STRING -> JsonStringColor
                            JsonNodeType.NUMBER -> JsonNumberColor
                            JsonNodeType.BOOLEAN -> JsonBooleanColor
                            JsonNodeType.NULL -> JsonNullColor
                            else -> JsonBraceColor
                        }
                        withStyle(SpanStyle(color = valueColor)) {
                            if (node.type == JsonNodeType.STRING) {
                                append("\"${node.value}\"$comma")
                            } else {
                                append("${node.value}$comma")
                            }
                        }
                    }
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Screen showing the list of traced API calls.
 * @param reportId Optional filter to show only traces for a specific report
 */
@Composable
fun TraceListScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSelectTrace: (String) -> Unit,
    onClearTraces: () -> Unit,
    reportId: String? = null
) {
    var traceFiles by remember(reportId) {
        mutableStateOf(
            if (reportId != null) ApiTracer.getTraceFilesForReport(reportId)
            else ApiTracer.getTraceFiles()
        )
    }
    var currentPage by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Calculate page size based on available height
        // Title bar ~48dp, pagination ~48dp, header ~40dp, clear button ~48dp, spacing ~40dp = ~224dp overhead
        // Each row is approximately 48dp
        val availableHeight = maxHeight - 224.dp
        val rowHeight = 48.dp
        val pageSize = maxOf(1, (availableHeight / rowHeight).toInt())

        val totalPages = (traceFiles.size + pageSize - 1) / pageSize
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, traceFiles.size)
        val currentPageItems = if (traceFiles.isNotEmpty() && startIndex < traceFiles.size) {
            traceFiles.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Reset to valid page if needed
        LaunchedEffect(pageSize, traceFiles.size) {
            if (currentPage >= totalPages && totalPages > 0) {
                currentPage = totalPages - 1
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AiTitleBar(
                title = if (reportId != null) "Report Traces" else "API Trace Log",
                onBackClick = onBack,
                onAiClick = onNavigateHome
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
                    text = if (reportId != null) "No traces recorded for this report" else "No API traces recorded yet",
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

        // Clear button (only shown when not filtering by reportId)
        if (reportId == null) {
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
    var currentView by remember { mutableStateOf(TraceContentView.RESPONSE_DATA) }

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

    // Prepare content for each view
    val prettyJson = remember { ApiTracer.prettyPrintJson(rawJson) }
    val requestHeadersText = remember {
        trace.request.headers.entries
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { "${it.key}: ${it.value}" }
    }
    val responseHeadersText = remember {
        trace.response.headers.entries
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { "${it.key}: ${it.value}" }
    }
    val requestDataText = remember { ApiTracer.prettyPrintJson(trace.request.body ?: "") }
    val responseDataText = remember { ApiTracer.prettyPrintJson(trace.response.body ?: "") }

    // Parse JSON trees for interactive view
    val requestTreeNodes = remember { trace.request.body?.let { parseJsonTree(it) } }
    val responseTreeNodes = remember { trace.response.body?.let { parseJsonTree(it) } }
    val requestIsArray = remember {
        try { trace.request.body?.let { JsonParser().parse(it) is JsonArray } ?: false } catch (_: Exception) { false }
    }
    val responseIsArray = remember {
        try { trace.response.body?.let { JsonParser().parse(it) is JsonArray } ?: false } catch (_: Exception) { false }
    }

    val hasRequestHeaders = trace.request.headers.isNotEmpty()
    val hasResponseHeaders = trace.response.headers.isNotEmpty()
    val hasRequestData = !trace.request.body.isNullOrBlank()
    val hasResponseData = !trace.response.body.isNullOrBlank()

    // Get current content based on view
    val currentContent = when (currentView) {
        TraceContentView.ALL -> prettyJson
        TraceContentView.REQUEST_HEADERS -> requestHeadersText
        TraceContentView.RESPONSE_HEADERS -> responseHeadersText
        TraceContentView.REQUEST_DATA -> requestDataText
        TraceContentView.RESPONSE_DATA -> responseDataText
    }
    val lines = remember(currentContent) { currentContent.split("\n") }

    val activeButtonColor = Color(0xFF666666)  // Gray for active
    val inactiveButtonColor = Color(0xFF3366BB)  // Blue for inactive

    // Determine background color based on status code
    val statusCode = trace.response.statusCode
    val isSuccess = statusCode in 200..299
    val backgroundColor = if (isSuccess) MaterialTheme.colorScheme.background else Color(0xFF4A1515)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Trace Detail - $statusCode",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        // Centered endpoint URL display
        Text(
            text = trace.request.url,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Centered "All" button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { currentView = TraceContentView.ALL },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentView == TraceContentView.ALL) activeButtonColor else inactiveButtonColor
                )
            ) {
                Text("All", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 1: Request headers, Response headers (Headers before Data)
        if (hasRequestHeaders || hasResponseHeaders) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasRequestHeaders) {
                    Button(
                        onClick = { currentView = TraceContentView.REQUEST_HEADERS },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentView == TraceContentView.REQUEST_HEADERS) activeButtonColor else inactiveButtonColor
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Request headers", fontSize = 12.sp)
                    }
                }
                if (hasResponseHeaders) {
                    Button(
                        onClick = { currentView = TraceContentView.RESPONSE_HEADERS },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentView == TraceContentView.RESPONSE_HEADERS) activeButtonColor else inactiveButtonColor
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Response headers", fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Row 2: Request data, Response data (Data after Headers)
        if (hasRequestData || hasResponseData) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasRequestData) {
                    Button(
                        onClick = { currentView = TraceContentView.REQUEST_DATA },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentView == TraceContentView.REQUEST_DATA) activeButtonColor else inactiveButtonColor
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Request data", fontSize = 12.sp)
                    }
                }
                if (hasResponseData) {
                    Button(
                        onClick = { currentView = TraceContentView.RESPONSE_DATA },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentView == TraceContentView.RESPONSE_DATA) activeButtonColor else inactiveButtonColor
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Response data", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
        ) {
            // Use JSON tree view for request/response data when parseable
            val treeNodes = when (currentView) {
                TraceContentView.REQUEST_DATA -> requestTreeNodes
                TraceContentView.RESPONSE_DATA -> responseTreeNodes
                else -> null
            }
            val isArray = when (currentView) {
                TraceContentView.REQUEST_DATA -> requestIsArray
                TraceContentView.RESPONSE_DATA -> responseIsArray
                else -> false
            }

            if (treeNodes != null) {
                JsonTreeView(nodes = treeNodes, isRootArray = isArray)
            } else {
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom buttons row: Copy and Share
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Trace content", currentContent)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3366BB)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy to clipboard")
            }

            Button(
                onClick = {
                    try {
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
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Share data")
            }
        }
    }
}

