package com.ai.ui.chat

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ai.data.ChatHistoryManager
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hub-level housekeeping for chat sessions. Two actions:
 *
 *  - Delete chats older than N days — bulk prune by age, with a
 *    confirmation step. Pinned chats are always skipped.
 *  - Export all (backup) — zips every chat-history JSON into a
 *    single archive and opens Share. Mirror of the Reports manage
 *    screen; chat history grows fast, so a true backup matters here.
 */
@Composable
fun ChatManageScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var daysText by remember { mutableStateOf("30") }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp)) {
        TitleBar(helpTopic = "chat_manage", title = "Manage chats", subject = "Bulk-delete old chats or export them", onBackClick = onBack)

        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Delete old chats", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Older than (days)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedFieldColors()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Pinned chats are skipped.", fontSize = 11.sp, color = AppColors.TextTertiary)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { confirmDelete = true },
                    enabled = !working && daysText.toIntOrNull()?.let { it > 0 } == true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Delete", maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Export all chats (backup)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Zips every chat-history JSON into a single archive and opens Share.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        working = true
                        status = "Zipping chats…"
                        scope.launch {
                            val (file, count) = withContext(Dispatchers.IO) { zipAllChats(context) }
                            working = false
                            if (file == null) { status = "Nothing to export."; return@launch }
                            status = "Bundled $count chats."
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share backup"))
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                ) { Text(if (working) "Working…" else "Export all", maxLines = 1, softWrap = false) }
            }
        }

        status?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
        }
    }

    if (confirmDelete) {
        val days = daysText.toIntOrNull() ?: 0
        // Freeze the cutoff against a single "now" captured per (open, daysText)
        // rather than recomputing System.currentTimeMillis() every composition:
        // otherwise the displayed count (candidates) and the actual delete set
        // could drift across a minute boundary while the dialog sits open, and
        // the cutoff used for the count wouldn't match the produceState key.
        val cutoff = remember(daysText) {
            System.currentTimeMillis() - days * 24L * 3600L * 1000L
        }
        // Off the UI thread — getAllSessions() reads + parses every
        // chat-history JSON synchronously and could hitch / ANR for
        // long histories.
        val candidates by produceState<List<com.ai.data.ChatSession>?>(initialValue = null, cutoff) {
            value = withContext(Dispatchers.IO) {
                ChatHistoryManager.getAllSessions().filter { !it.pinned && it.updatedAt < cutoff }
            }
        }
        val candidateList = candidates
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    if (candidateList == null) "Loading…"
                    else "Delete ${candidateList.size} chats?"
                )
            },
            text = { Text("Chats older than $days days (pinned excluded). This cannot be undone.") },
            confirmButton = {
                TextButton(
                    enabled = candidateList != null,
                    onClick = {
                        val toDelete = candidateList ?: return@TextButton
                        confirmDelete = false
                        working = true
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                toDelete.forEach { c -> ChatHistoryManager.deleteSession(c.id) }
                                toDelete.size
                            }
                            status = "Deleted $n chats."
                            working = false
                        }
                    }
                ) { Text("Delete", color = AppColors.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private fun zipAllChats(context: android.content.Context): Pair<File?, Int> {
    val ts = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
    val outDir = File(context.cacheDir, "chat_backup").also { it.mkdirs() }
    val outFile = File(outDir, "ai_chats_backup_$ts.zip")
    val historyDir = File(context.filesDir, "chat-history")
    // Only zip .json session files (skip any stray/foreign file a restore
    // would choke on), and count the entries actually written so the
    // "Bundled N chats" status matches the zip rather than the parsed list.
    val files = historyDir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()
    if (files.isEmpty()) return null to 0
    var written = 0
    ZipOutputStream(FileOutputStream(outFile)).use { zip ->
        files.forEach { f ->
            zip.putNextEntry(ZipEntry("chat-history/${f.name}"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            written++
        }
    }
    return outFile to written
}
