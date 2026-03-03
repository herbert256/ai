package com.ai.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.FileProvider
import com.ai.data.*
import com.ai.ui.shared.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ===== Trace List =====

@Composable
fun TraceListScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectTrace: (String) -> Unit,
    onClearTraces: () -> Unit,
    reportId: String? = null
) {
    BackHandler { onBack() }
    var traceFiles by remember { mutableStateOf(
        if (reportId != null) ApiTracer.getTraceFilesForReport(reportId) else ApiTracer.getTraceFiles()
    ) }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        val rowHeight = 52
        val overhead = 130
        val pageSize = maxOf(1, ((maxHeight.value - overhead) / rowHeight).toInt())
        val totalPages = if (traceFiles.isEmpty()) 1 else (traceFiles.size + pageSize - 1) / pageSize

        LaunchedEffect(totalPages) { if (currentPage >= totalPages) currentPage = (totalPages - 1).coerceAtLeast(0) }

        val startIndex = currentPage * pageSize
        val pageItems = traceFiles.subList(startIndex.coerceAtMost(traceFiles.size), (startIndex + pageSize).coerceAtMost(traceFiles.size))

        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(title = if (reportId != null) "Report Traces" else "API Traces", onBackClick = onBack, onAiClick = onNavigateHome)

            if (totalPages > 1) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentPage = (currentPage - 1).coerceAtLeast(0) }, enabled = currentPage > 0) { Text("< Prev") }
                    Text("${currentPage + 1} / $totalPages (${traceFiles.size})", fontSize = 12.sp, color = AppColors.TextTertiary)
                    TextButton(onClick = { currentPage = (currentPage + 1).coerceAtMost(totalPages - 1) }, enabled = currentPage < totalPages - 1) { Text("Next >") }
                }
            }

            // Table header
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Host", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                    Text("Date/Time", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("Status", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(0.4f), textAlign = TextAlign.End)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(pageItems, key = { it.filename }) { trace ->
                    TraceListItem(trace = trace, onClick = { onSelectTrace(trace.filename) })
                }
            }

            if (reportId == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    onClearTraces(); traceFiles = emptyList(); currentPage = 0
                }, enabled = traceFiles.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear Traces") }
            }
        }
    }
}

@Composable
private fun TraceListItem(trace: TraceFileInfo, onClick: () -> Unit) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault()) }
    val statusColor = when {
        trace.statusCode in 200..299 -> AppColors.Green
        trace.statusCode in 400..499 -> AppColors.Orange
        trace.statusCode >= 500 -> AppColors.Red
        else -> AppColors.TextTertiary
    }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(trace.hostname, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(dateFormat.format(Instant.ofEpochMilli(trace.timestamp)), fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${trace.statusCode}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor, modifier = Modifier.weight(0.4f), textAlign = TextAlign.End)
        }
    }
}

// ===== Trace Detail =====

private enum class TraceContentView { ALL, REQ_HEADERS, RSP_HEADERS, REQ_DATA, RSP_DATA }

@Composable
fun TraceDetailScreen(filename: String, onBack: () -> Unit, onNavigateHome: () -> Unit, onEditRequest: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var currentFilename by remember { mutableStateOf(filename) }
    var trace by remember { mutableStateOf<ApiTrace?>(null) }
    var rawJson by remember { mutableStateOf("") }
    var currentView by remember { mutableStateOf(TraceContentView.ALL) }

    val traceFiles = remember { ApiTracer.getTraceFiles().map { it.filename } }
    val currentIndex = traceFiles.indexOf(currentFilename)
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex < traceFiles.size - 1 && currentIndex >= 0

    // Load trace data
    LaunchedEffect(currentFilename) {
        trace = ApiTracer.readTraceFile(currentFilename)
        rawJson = ApiTracer.readTraceFileRaw(currentFilename) ?: ""
    }

    val t = trace
    val statusCode = t?.response?.statusCode ?: 0
    val bgColor = if (statusCode >= 300) Color(0xFF4A1515) else MaterialTheme.colorScheme.background

    // Parse JSON trees
    val requestTreeNodes = remember(t?.request?.body) { t?.request?.body?.let { parseJsonTree(it) } }
    val responseTreeNodes = remember(t?.response?.body) { t?.response?.body?.let { parseJsonTree(it) } }

    // Build content for current view
    val displayContent = remember(t, currentView) {
        if (t == null) return@remember ""
        when (currentView) {
            TraceContentView.ALL -> ApiTracer.prettyPrintJson(rawJson)
            TraceContentView.REQ_HEADERS -> t.request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            TraceContentView.RSP_HEADERS -> t.response.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            TraceContentView.REQ_DATA -> ApiTracer.prettyPrintJson(t.request.body)
            TraceContentView.RSP_DATA -> ApiTracer.prettyPrintJson(t.response.body)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor).padding(16.dp)) {
        TitleBar(title = "Trace: $statusCode", onBackClick = onBack, onAiClick = onNavigateHome)

        // URL
        t?.request?.url?.let { url ->
            Text(url, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(8.dp))

        // View selector buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val views = listOf(
                TraceContentView.ALL to "All",
                TraceContentView.REQ_HEADERS to "Req Hdr",
                TraceContentView.RSP_HEADERS to "Rsp Hdr",
                TraceContentView.REQ_DATA to "Req",
                TraceContentView.RSP_DATA to "Rsp"
            )
            views.forEach { (view, label) ->
                val isActive = currentView == view
                OutlinedButton(onClick = { currentView = view }, modifier = Modifier.weight(1f),
                    colors = if (isActive) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF3366BB).copy(alpha = 0.3f)) else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) { Text(label, fontSize = 10.sp, maxLines = 1) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content area - use JSON tree for request/response data if parseable
        val useTree = (currentView == TraceContentView.REQ_DATA && requestTreeNodes != null) ||
            (currentView == TraceContentView.RSP_DATA && responseTreeNodes != null)
        val treeNodes = when (currentView) {
            TraceContentView.REQ_DATA -> requestTreeNodes
            TraceContentView.RSP_DATA -> responseTreeNodes
            else -> null
        }

        Box(modifier = Modifier.weight(1f)) {
            if (useTree && treeNodes != null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(treeNodes.size) { index ->
                        JsonTreeNodeView(node = treeNodes[index], depth = 0)
                    }
                }
            } else {
                LazyColumn {
                    val lines = displayContent.lines()
                    items(lines.size) { index ->
                        Text(lines[index], fontSize = 11.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom buttons: navigation + actions
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                if (hasPrev) { currentFilename = traceFiles[currentIndex - 1]; currentView = TraceContentView.ALL }
            }, enabled = hasPrev, modifier = Modifier.weight(1f)) { Text("<") }
            OutlinedButton(onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("trace", displayContent))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("Copy") }
            OutlinedButton(onClick = {
                // Save provider/model/key to prefs for EditApiRequestScreen
                t?.let { trace ->
                    val prefs = context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putString("last_test_raw_json", trace.request.body)
                        putString("last_test_api_url", trace.request.url)
                        val authHeader = trace.request.headers["Authorization"] ?: ""
                        putString("last_test_api_key", authHeader.removePrefix("Bearer ").trim())
                        putString("last_test_model", trace.model ?: "")
                    }.apply()
                }
                onEditRequest()
            }, modifier = Modifier.weight(1f)) { Text("Edit") }
            OutlinedButton(onClick = { shareTrace(context, rawJson, currentFilename) }, modifier = Modifier.weight(1f)) { Text("Share") }
            OutlinedButton(onClick = {
                if (hasNext) { currentFilename = traceFiles[currentIndex + 1]; currentView = TraceContentView.ALL }
            }, enabled = hasNext, modifier = Modifier.weight(1f)) { Text(">") }
        }
    }
}

private fun shareTrace(context: Context, content: String, filename: String) {
    try {
        val file = File(context.cacheDir, filename)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Share Trace"))
    } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
}

// ===== JSON Tree View =====

private enum class JsonNodeType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

private data class JsonTreeNode(val key: String?, val type: JsonNodeType, val value: String? = null, val children: List<JsonTreeNode> = emptyList())

private fun parseJsonTree(json: String): List<JsonTreeNode>? {
    return try {
        @Suppress("DEPRECATION")
        val element = JsonParser().parse(json)
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().map { parseJsonElement(it.key, it.value) }
            element.isJsonArray -> element.asJsonArray.toList().mapIndexed { i: Int, el: JsonElement -> parseJsonElement("[$i]", el) }
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun parseJsonElement(key: String?, element: JsonElement): JsonTreeNode {
    return when {
        element.isJsonObject -> JsonTreeNode(key, JsonNodeType.OBJECT, children = element.asJsonObject.entrySet().map { parseJsonElement(it.key, it.value) })
        element.isJsonArray -> JsonTreeNode(key, JsonNodeType.ARRAY, children = element.asJsonArray.mapIndexed { i, el -> parseJsonElement("[$i]", el) })
        element.isJsonPrimitive -> {
            val p = element.asJsonPrimitive
            when {
                p.isString -> JsonTreeNode(key, JsonNodeType.STRING, "\"${p.asString}\"")
                p.isNumber -> JsonTreeNode(key, JsonNodeType.NUMBER, p.asString)
                p.isBoolean -> JsonTreeNode(key, JsonNodeType.BOOLEAN, p.asString)
                else -> JsonTreeNode(key, JsonNodeType.NULL, "null")
            }
        }
        element.isJsonNull -> JsonTreeNode(key, JsonNodeType.NULL, "null")
        else -> JsonTreeNode(key, JsonNodeType.NULL, "null")
    }
}

@Composable
private fun JsonTreeNodeView(node: JsonTreeNode, depth: Int) {
    var expanded by remember { mutableStateOf(depth < 2) }
    val indent = (depth * 16).dp
    val hasChildren = node.children.isNotEmpty()

    Row(modifier = Modifier.fillMaxWidth().padding(start = indent).then(if (hasChildren) Modifier.clickable { expanded = !expanded } else Modifier).padding(vertical = 2.dp)) {
        if (hasChildren) {
            Text(if (expanded) "▾ " else "▸ ", fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }

        if (node.key != null) {
            Text("${node.key}: ", fontSize = 11.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace)
        }

        when {
            hasChildren -> {
                val bracket = if (node.type == JsonNodeType.OBJECT) "{${node.children.size}}" else "[${node.children.size}]"
                Text(bracket, fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
            else -> {
                val valueColor = when (node.type) {
                    JsonNodeType.STRING -> Color(0xFF6A8759) // green
                    JsonNodeType.NUMBER -> AppColors.Orange
                    JsonNodeType.BOOLEAN -> AppColors.Purple
                    JsonNodeType.NULL -> AppColors.TextDim
                    else -> Color.White
                }
                Text(node.value ?: "", fontSize = 11.sp, color = valueColor, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (hasChildren && expanded) {
        node.children.forEach { child -> JsonTreeNodeView(node = child, depth = depth + 1) }
    }
}
