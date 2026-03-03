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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ai.data.AppService
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
private val JsonNullColor = AppColors.TextTertiary
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
                            JsonNodeType.OBJECT, JsonNodeType.ARRAY -> JsonBraceColor
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
    reportId: String? = null,
    hostnameFilter: String? = null,
    modelFilter: String? = null
) {
    var traceFiles by remember(reportId, hostnameFilter, modelFilter) {
        mutableStateOf(
            when {
                reportId != null -> ApiTracer.getTraceFilesForReport(reportId)
                hostnameFilter != null -> ApiTracer.getTraceFiles().filter {
                    it.hostname == hostnameFilter && (modelFilter == null || it.model == modelFilter)
                }
                else -> ApiTracer.getTraceFiles()
            }
        )
    }
    var currentPage by rememberSaveable { mutableStateOf(0) }

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
            TitleBar(
                title = when {
                    reportId != null -> "Report Traces"
                    modelFilter != null -> "Traces: $modelFilter"
                    hostnameFilter != null -> "Traces: $hostnameFilter"
                    else -> "API Trace Log"
                },
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
                        disabledContainerColor = AppColors.DividerDark
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
                        disabledContainerColor = AppColors.DividerDark
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
                containerColor = AppColors.SurfaceDark
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
                    color = AppColors.Blue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = "Date/Time",
                    color = AppColors.Blue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1.2f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Status",
                    color = AppColors.Blue,
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
                    color = AppColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageItems, key = { it.filename }) { traceInfo ->
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
                    disabledContainerColor = AppColors.BorderUnfocused
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
        traceInfo.statusCode in 200..299 -> AppColors.Green  // Green for success
        traceInfo.statusCode in 400..499 -> AppColors.Orange  // Orange for client errors
        traceInfo.statusCode >= 500 -> AppColors.RedDark       // Red for server errors
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
    onNavigateHome: () -> Unit = onBack,
    onEditRequest: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Get sorted trace file list for prev/next navigation
    val traceFiles = remember { ApiTracer.getTraceFiles().map { it.filename } }
    var currentFilename by remember { mutableStateOf(filename) }
    val currentIndex = traceFiles.indexOf(currentFilename)
    val hasPrevious = currentIndex > 0
    val hasNext = currentIndex < traceFiles.size - 1 && currentIndex >= 0

    val trace = remember(currentFilename) { ApiTracer.readTraceFile(currentFilename) }
    val rawJson = remember(currentFilename) { ApiTracer.readTraceFileRaw(currentFilename) ?: "" }
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
    val prettyJson = remember(currentFilename) { ApiTracer.prettyPrintJson(rawJson) }
    val requestHeadersText = remember(currentFilename) {
        trace.request.headers.entries
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { "${it.key}: ${it.value}" }
    }
    val responseHeadersText = remember(currentFilename) {
        trace.response.headers.entries
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { "${it.key}: ${it.value}" }
    }
    val requestDataText = remember(currentFilename) { ApiTracer.prettyPrintJson(trace.request.body ?: "") }
    val responseDataText = remember(currentFilename) { ApiTracer.prettyPrintJson(trace.response.body ?: "") }

    // Parse JSON trees for interactive view
    val requestTreeNodes = remember(currentFilename) { trace.request.body?.let { parseJsonTree(it) } }
    val responseTreeNodes = remember(currentFilename) { trace.response.body?.let { parseJsonTree(it) } }
    val requestIsArray = remember(currentFilename) {
        try { trace.request.body?.let { JsonParser().parse(it) is JsonArray } ?: false } catch (_: Exception) { false }
    }
    val responseIsArray = remember(currentFilename) {
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

    val activeButtonColor = AppColors.TextDim  // Gray for active
    val inactiveButtonColor = Color(0xFF3366BB)  // Blue for inactive

    // Determine background color based on status code
    val statusCode = trace.response.statusCode
    val isSuccess = statusCode in 200..299
    val backgroundColor = if (isSuccess) MaterialTheme.colorScheme.background else Color(0xFF4A1515)

    val smallButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        TitleBar(
            title = "Trace Detail - $statusCode",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        // Endpoint URL
        Text(
            text = trace.request.url,
            color = AppColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // All 5 view buttons in 2 rows
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { currentView = TraceContentView.ALL },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentView == TraceContentView.ALL) activeButtonColor else inactiveButtonColor
                ),
                contentPadding = smallButtonPadding,
                modifier = Modifier.weight(1f)
            ) { Text("All", fontSize = 11.sp) }
            if (hasRequestHeaders) {
                Button(
                    onClick = { currentView = TraceContentView.REQUEST_HEADERS },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentView == TraceContentView.REQUEST_HEADERS) activeButtonColor else inactiveButtonColor
                    ),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(1f)
                ) { Text("Req Hdr", fontSize = 11.sp) }
            }
            if (hasResponseHeaders) {
                Button(
                    onClick = { currentView = TraceContentView.RESPONSE_HEADERS },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentView == TraceContentView.RESPONSE_HEADERS) activeButtonColor else inactiveButtonColor
                    ),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(1f)
                ) { Text("Rsp Hdr", fontSize = 11.sp) }
            }
            if (hasRequestData) {
                Button(
                    onClick = { currentView = TraceContentView.REQUEST_DATA },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentView == TraceContentView.REQUEST_DATA) activeButtonColor else inactiveButtonColor
                    ),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(1f)
                ) { Text("Req Data", fontSize = 11.sp) }
            }
            if (hasResponseData) {
                Button(
                    onClick = { currentView = TraceContentView.RESPONSE_DATA },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentView == TraceContentView.RESPONSE_DATA) activeButtonColor else inactiveButtonColor
                    ),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(1f)
                ) { Text("Rsp Data", fontSize = 11.sp) }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AppColors.DisabledBackground)
        ) {
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

        Spacer(modifier = Modifier.height(4.dp))

        // Bottom row: Prev, Copy, Share, Next — all on one line
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (traceFiles.size > 1) {
                Button(
                    onClick = { if (hasPrevious) currentFilename = traceFiles[currentIndex - 1] },
                    enabled = hasPrevious,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.TextDisabled,
                        disabledContainerColor = AppColors.DividerDark
                    ),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(1f)
                ) { Text("<", fontSize = 12.sp, color = if (hasPrevious) Color.White else AppColors.TextDim) }
            }
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Trace content", currentContent)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3366BB)),
                contentPadding = smallButtonPadding,
                modifier = Modifier.weight(2f)
            ) { Text("Copy", fontSize = 12.sp) }
            if (hasRequestData) {
                Button(
                    onClick = {
                        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                        val traceUrl = trace.request.url
                        // Detect auth format from trace headers
                        val hasXApiKey = trace.request.headers.keys.any { it.equals("x-api-key", ignoreCase = true) }
                        val hasUrlKeyParam = android.net.Uri.parse(traceUrl).getQueryParameter("key") != null
                        // Find provider: try URL match first, then fall back to auth-format match
                        val matchedProvider = AppService.entries.firstOrNull { service ->
                            traceUrl.startsWith(service.baseUrl.trimEnd('/'))
                        } ?: run {
                            val targetFormat = when {
                                hasXApiKey -> com.ai.data.ApiFormat.ANTHROPIC
                                hasUrlKeyParam -> com.ai.data.ApiFormat.GOOGLE
                                else -> com.ai.data.ApiFormat.OPENAI_COMPATIBLE
                            }
                            AppService.entries.firstOrNull { it.apiFormat == targetFormat }
                                ?: AppService.entries.first()
                        }
                        // Extract API key from request headers
                        val apiKey = trace.request.headers.entries.firstNotNullOfOrNull { (key, value) ->
                            when {
                                key.equals("Authorization", ignoreCase = true) && value.startsWith("Bearer ", ignoreCase = true) ->
                                    value.removePrefix("Bearer ").removePrefix("bearer ").trim()
                                key.equals("x-api-key", ignoreCase = true) -> value.trim()
                                else -> null
                            }
                        } ?: run {
                            // Check for ?key= query parameter (Google)
                            val keyParam = android.net.Uri.parse(traceUrl).getQueryParameter("key")
                            keyParam ?: ""
                        }
                        // Extract model from request body JSON
                        val traceModel = try {
                            val bodyJson = JsonParser().parse(trace.request.body ?: "{}") as? JsonObject
                            bodyJson?.get("model")?.asString ?: ""
                        } catch (_: Exception) { "" }

                        prefs.edit().apply {
                            putString("last_test_provider", matchedProvider.id)
                            putString("last_test_api_url", traceUrl)
                            putString("last_test_api_key", apiKey)
                            putString("last_test_model", traceModel)
                            putString("last_test_raw_json", trace.request.body ?: "")
                            apply()
                        }
                        (onEditRequest ?: onBack)()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(2f)
                ) { Text("Edit", fontSize = 12.sp) }
            }
            Button(
                onClick = {
                    try {
                        val cacheDir = File(context.cacheDir, "shared_traces")
                        cacheDir.mkdirs()
                        val tempFile = File(cacheDir, currentFilename)
                        tempFile.writeText(rawJson)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
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
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                contentPadding = smallButtonPadding,
                modifier = Modifier.weight(2f)
            ) { Text("Share", fontSize = 12.sp) }
            if (traceFiles.size > 1) {
                Button(
                    onClick = { if (hasNext) currentFilename = traceFiles[currentIndex + 1] },
                    enabled = hasNext,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.TextDisabled,
                        disabledContainerColor = AppColors.DividerDark
                    ),
                    contentPadding = smallButtonPadding,
                    modifier = Modifier.weight(1f)
                ) { Text(">", fontSize = 12.sp, color = if (hasNext) Color.White else AppColors.TextDim) }
            }
        }
    }
}
