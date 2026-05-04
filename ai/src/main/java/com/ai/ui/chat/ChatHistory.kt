package com.ai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ChatHistoryManager
import com.ai.data.ChatSession
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ChatHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenTraces: (String) -> Unit = {}
) {
    BackHandler { onNavigateBack() }

    val historyVersion by ChatHistoryManager.historyVersion.collectAsState()
    val allSessions by produceState<List<ChatSession>>(initialValue = emptyList(), historyVersion) {
        value = ChatHistoryManager.getAllSessionsAsync()
    }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    var currentPage by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Chat History", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        if (allSessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No chat history yet", color = AppColors.TextTertiary, fontSize = 16.sp)
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val availableHeight = maxHeight - 176.dp
                val rowHeight = 80.dp
                val pageSize = maxOf(1, (availableHeight / rowHeight).toInt())
                val totalPages = (allSessions.size + pageSize - 1) / pageSize
                val startIndex = currentPage * pageSize
                val endIndex = minOf(startIndex + pageSize, allSessions.size)
                val currentPageSessions = if (startIndex < allSessions.size) allSessions.subList(startIndex, endIndex) else emptyList()

                LaunchedEffect(pageSize, allSessions.size) {
                    if (currentPage >= totalPages && totalPages > 0) currentPage = totalPages - 1
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    PaginationControls(currentPage, totalPages, allSessions.size) { currentPage = it }
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(currentPageSessions, key = { it.id }) { session ->
                            // Per-row trace probe \u2014 gates the \uD83D\uDC1E icon's
                            // visibility on whether any chat-turn trace
                            // was tagged with this sessionId.
                            val hasTraces by produceState(initialValue = false, session.id) {
                                value = withContext(Dispatchers.IO) {
                                    com.ai.data.ApiTracer.getTraceFiles().any { it.reportId == session.id }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectSession(session.id) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.preview.let { if (it.length > 50) "${it.take(50)}..." else it },
                                        fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row {
                                        Text(session.provider.displayName, fontSize = 12.sp, color = AppColors.Blue)
                                        Text(" \u00B7 ${session.model}", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                    Text(dateFormat.format(session.updatedAt), fontSize = 11.sp, color = AppColors.TextDim)
                                }
                                if (com.ai.data.ApiTracer.isTracingEnabled && hasTraces) {
                                    Text("\uD83D\uDC1E", fontSize = 16.sp,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .clickable { onOpenTraces(session.id) })
                                }
                                Text(">", fontSize = 18.sp, color = AppColors.Blue, modifier = Modifier.padding(start = 8.dp))
                            }
                            HorizontalDivider(color = AppColors.DividerDark)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    PaginationControls(currentPage, totalPages, allSessions.size) { currentPage = it }
                }
            }
        }
    }
}

@Composable
private fun PaginationControls(currentPage: Int, totalPages: Int, totalItems: Int, onPageChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onPageChange(currentPage - 1) }, enabled = currentPage > 0) {
            Text("< Previous", color = if (currentPage > 0) AppColors.Blue else AppColors.TextDim, maxLines = 1, softWrap = false)
        }
        Text("Page ${currentPage + 1} of $totalPages ($totalItems chats)", fontSize = 12.sp, color = AppColors.TextTertiary)
        TextButton(onClick = { onPageChange(currentPage + 1) }, enabled = currentPage < totalPages - 1) {
            Text("Next >", color = if (currentPage < totalPages - 1) AppColors.Blue else AppColors.TextDim, maxLines = 1, softWrap = false)
        }
    }
}

// ===== Chat Search =====

private data class ChatSearchResult(
    val sessionId: String,
    val sessionTitle: String,
    val messageRole: String,
    val messagePreview: String,
    val messageTimestamp: Long
)

private suspend fun searchInChats(query: String): List<ChatSearchResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<ChatSearchResult>()
    val sessions = ChatHistoryManager.getAllSessions()
    val lowerQuery = query.lowercase()

    for (session in sessions) {
        for (message in session.messages) {
            val lowerContent = message.content.lowercase()
            if (lowerContent.contains(lowerQuery)) {
                val matchIndex = lowerContent.indexOf(lowerQuery)
                val start = (matchIndex - 40).coerceAtLeast(0)
                val end = (matchIndex + query.length + 40).coerceAtMost(message.content.length)
                val preview = (if (start > 0) "..." else "") +
                    message.content.substring(start, end) +
                    (if (end < message.content.length) "..." else "")
                results.add(
                    ChatSearchResult(
                        sessionId = session.id,
                        sessionTitle = session.preview.ifBlank { "Chat with ${session.provider.displayName}" },
                        messageRole = message.role,
                        messagePreview = preview,
                        messageTimestamp = message.timestamp
                    )
                )
            }
        }
    }
    results.sortedByDescending { it.messageTimestamp }
}

@Composable
fun ChatSearchScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectSession: (String) -> Unit
) {
    BackHandler { onNavigateBack() }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ChatSearchResult>>(emptyList()) }
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val focusRequester = remember { FocusRequester() }
    val historyVersion by ChatHistoryManager.historyVersion.collectAsState()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(historyVersion, searchQuery, hasSearched) {
        if (searchQuery.isNotBlank() && hasSearched) {
            isSearching = true
            searchResults = searchInChats(searchQuery)
            isSearching = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Search Chats", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; if (it.isBlank()) { hasSearched = false; searchResults = emptyList() } },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text("Search in messages...") },
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                TextButton(onClick = { hasSearched = true }, enabled = searchQuery.isNotBlank()) {
                    Text("Search", color = if (searchQuery.isNotBlank()) AppColors.Blue else AppColors.TextDim, maxLines = 1, softWrap = false)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            !hasSearched -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Enter a search term to find messages", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            }
            isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppColors.Blue, modifier = Modifier.size(24.dp))
                }
            }
            searchResults.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matches found for \"$searchQuery\"", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            }
            else -> {
                Text("${searchResults.size} results", fontSize = 12.sp, color = AppColors.TextTertiary)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    items(searchResults, key = { "${it.sessionId}:${it.messageTimestamp}" }) { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelectSession(result.sessionId) },
                            colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(result.sessionTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(result.messageRole.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = AppColors.Blue)
                                Text(result.messagePreview, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(dateFormat.format(result.messageTimestamp), fontSize = 10.sp, color = AppColors.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}
