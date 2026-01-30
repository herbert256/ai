package com.ai.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Prompt History screen showing previously used prompts with pagination.
 * Layout matches the API Trace Log screen.
 */
@Composable
fun PromptHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onSelectEntry: (PromptHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs) }
    val allHistoryEntries = remember { mutableStateOf(settingsPrefs.loadPromptHistory()) }
    var searchText by remember { mutableStateOf("") }
    var currentPage by remember { mutableIntStateOf(0) }

    // Filter entries based on search text
    val filteredEntries = remember(allHistoryEntries.value, searchText) {
        if (searchText.isBlank()) {
            allHistoryEntries.value
        } else {
            val searchLower = searchText.lowercase()
            allHistoryEntries.value.filter { entry ->
                entry.title.lowercase().contains(searchLower) ||
                entry.prompt.lowercase().contains(searchLower)
            }
        }
    }

    // Reset to first page when search changes
    LaunchedEffect(searchText) {
        currentPage = 0
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Calculate page size based on available height
        // Title bar ~48dp, search ~56dp, pagination ~48dp, header ~40dp, clear button ~48dp, spacing ~40dp = ~280dp overhead
        // Each row is approximately 56dp
        val availableHeight = maxHeight - 280.dp
        val rowHeight = 56.dp
        val pageSize = maxOf(1, (availableHeight / rowHeight).toInt())

        val totalPages = (filteredEntries.size + pageSize - 1) / pageSize
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, filteredEntries.size)
        val currentPageEntries = if (filteredEntries.isNotEmpty() && startIndex < filteredEntries.size) {
            filteredEntries.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Reset to valid page if needed
        LaunchedEffect(pageSize, filteredEntries.size) {
            if (currentPage >= totalPages && totalPages > 0) {
                currentPage = totalPages - 1
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AiTitleBar(
                title = "Prompt History",
                onBackClick = onNavigateBack,
                onAiClick = onNavigateHome
            )

            Spacer(modifier = Modifier.height(8.dp))

        // Search field
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search in title or prompt...", color = Color(0xFF888888)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF555555),
                cursorColor = Color(0xFF6B9BFF)
            ),
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { searchText = "" }) {
                        Text("✕", color = Color(0xFF888888))
                    }
                }
            }
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
                    text = "Title",
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
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // History list
        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (allHistoryEntries.value.isEmpty()) "No prompt history yet" else "No matches found",
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageEntries) { entry ->
                    PromptHistoryRow(
                        entry = entry,
                        onClick = { onSelectEntry(entry) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear button
        Button(
            onClick = {
                settingsPrefs.clearPromptHistory()
                allHistoryEntries.value = emptyList()
                searchText = ""
                currentPage = 0
            },
            enabled = allHistoryEntries.value.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFCC3333),
                disabledContainerColor = Color(0xFF444444)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear history")
        }
        }
    }
}

/**
 * Single row in Prompt History showing title and timestamp.
 * Matches the trace log table row style.
 */
@Composable
private fun PromptHistoryRow(
    entry: PromptHistoryEntry,
    onClick: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US) }
    val formattedDate = remember(entry.timestamp) { dateFormat.format(java.util.Date(entry.timestamp)) }

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
                text = entry.title,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.5f)
            )
            Text(
                text = formattedDate,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}
