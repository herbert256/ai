package com.ai.ui.history

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*
import com.ai.viewmodel.PromptHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PromptHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectEntry: (PromptHistoryEntry) -> Unit
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }
    var allEntries by remember { mutableStateOf(settingsPrefs.loadPromptHistory()) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }

    val filteredEntries = remember(allEntries, searchText) {
        if (searchText.isBlank()) allEntries
        else allEntries.filter { it.title.contains(searchText, ignoreCase = true) || it.prompt.contains(searchText, ignoreCase = true) }
    }

    LaunchedEffect(searchText) { currentPage = 0 }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        val rowHeight = 56
        val overhead = 160
        val pageSize = maxOf(1, ((maxHeight.value - overhead) / rowHeight).toInt())
        val totalPages = if (filteredEntries.isEmpty()) 1 else (filteredEntries.size + pageSize - 1) / pageSize

        LaunchedEffect(totalPages) { if (currentPage >= totalPages) currentPage = (totalPages - 1).coerceAtLeast(0) }

        val startIndex = currentPage * pageSize
        val pageItems = filteredEntries.subList(startIndex.coerceAtMost(filteredEntries.size), (startIndex + pageSize).coerceAtMost(filteredEntries.size))

        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(title = "Prompt History", onBackClick = onNavigateBack, onAiClick = onNavigateHome)

            OutlinedTextField(value = searchText, onValueChange = { searchText = it },
                placeholder = { Text("Search prompts...") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors())

            Spacer(modifier = Modifier.height(8.dp))

            // Pagination
            if (totalPages > 1) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentPage = (currentPage - 1).coerceAtLeast(0) }, enabled = currentPage > 0) { Text("< Prev") }
                    Text("${currentPage + 1} / $totalPages (${filteredEntries.size})", fontSize = 12.sp, color = AppColors.TextTertiary)
                    TextButton(onClick = { currentPage = (currentPage + 1).coerceAtMost(totalPages - 1) }, enabled = currentPage < totalPages - 1) { Text("Next >") }
                }
            }

            // Table header
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Title", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1.5f))
                    Text("Date/Time", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pageItems, key = { it.timestamp }) { entry ->
                    PromptHistoryRow(entry = entry, onClick = { onSelectEntry(entry) })
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { settingsPrefs.clearPromptHistory(); allEntries = emptyList(); currentPage = 0 },
                enabled = allEntries.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear History") }
        }
    }
}

@Composable
private fun PromptHistoryRow(entry: PromptHistoryEntry, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.5f))
            Text(dateFormat.format(Date(entry.timestamp)), fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}
