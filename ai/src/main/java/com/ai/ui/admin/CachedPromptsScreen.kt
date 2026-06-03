package com.ai.ui.admin

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.MetadataIconsHolder
import com.ai.data.PromptCache
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Housekeeping → Cached prompts. Lists every entry in the on-disk
 * [PromptCache] (responses to Internal-Prompt calls, e.g. the AI
 * Introduction on Model Info, kept 48 h to avoid paying for the same call
 * twice). Each row shows the cached response, its age, on-disk size and a
 * STALE marker once past the TTL; rows can be deleted individually and the
 * 🧽 bar action clears the lot. The original prompt text isn't stored (only
 * its hash key), so the response preview is what identifies an entry.
 */
@Composable
fun CachedPromptsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()

    var refreshTick by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val entries by produceState(initialValue = emptyList<PromptCache.CachedEntry>(), refreshTick) {
        value = withContext(Dispatchers.IO) { PromptCache.list() }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "cached_prompts",
            title = "Cached prompts",
            subject = "Cached internal-prompt responses (48 h)",
            onBackClick = onBack,
            // 🧽 clears every cached entry.
            onClear = if (entries.isNotEmpty()) ({ confirmClear = true }) else null
        )

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No cached prompts.", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Text(
                    "${entries.size} cached ${if (entries.size == 1) "response" else "responses"}, " +
                        "${formatBytes(entries.sumOf { it.sizeBytes })} on disk. Entries expire after 48 h; a stale " +
                        "entry is re-fetched on next use unless deleted here.",
                    fontSize = 12.sp, color = AppColors.TextTertiary, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            items(entries, key = { it.key }) { entry ->
                CachedPromptRow(entry, onDelete = {
                    scope.launch {
                        withContext(Dispatchers.IO) { PromptCache.delete(entry.key) }
                        refreshTick++
                    }
                })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all cached prompts?") },
            text = { Text("Deletes every cached internal-prompt response. They'll be re-fetched (and paid for) on next use.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { PromptCache.clearAll() }
                        refreshTick++
                    }
                }) { Text("Clear all", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

@Composable
private fun CachedPromptRow(entry: PromptCache.CachedEntry, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.response.ifBlank { "(empty)" },
                    fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val age = DateUtils.getRelativeTimeSpanString(
                    entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                ).toString()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$age · ${formatBytes(entry.sizeBytes)}", fontSize = 10.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    if (entry.stale) {
                        Text("STALE", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = AppColors.WarningAccent, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Text(MetadataIconsHolder.current.delete, fontSize = 16.sp)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
