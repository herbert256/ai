package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun InternalPromptsAdminScreen(
    onLoadBundledPrompts: () -> Int,
    onResetBundledPrompts: () -> Int,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var status by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Internal prompts?") },
            text = { Text("This deletes every Internal prompt (including any you customized) and reloads the bundled list from assets/prompts.json.") },
            confirmButton = {
                Button(
                    onClick = {
                        val loaded = onResetBundledPrompts()
                        showResetConfirm = false
                        status = if (loaded > 0) {
                            "Reset complete — loaded $loaded prompt${if (loaded == 1) "" else "s"} from assets/prompts.json"
                        } else {
                            "Reset failed — assets/prompts.json could not be read"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                ) { Text("Reset", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "internal_prompts_admin", title = "Internal prompts", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Load new prompts", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Merges any prompt in the bundled assets/prompts.json that isn't already present. Matched by (category, name); existing rows with the same pair are not overwritten — they keep your edits.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = {
                            val added = onLoadBundledPrompts()
                            status = when {
                                added == 0 -> "No new prompts in assets/prompts.json"
                                added == 1 -> "Added 1 new prompt"
                                else -> "Added $added new prompts"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                    ) { Text("Load new prompts from assets/prompts.json", maxLines = 1, softWrap = false) }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reset", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Drops every Internal prompt (including ones you authored) and reloads the bundled set fresh. Use when your edits diverged badly enough that starting over is cleaner than reconciling.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                    ) { Text("Reset Internal Prompts to assets/prompts.json", maxLines = 1, softWrap = false) }
                }
            }

            status?.let {
                Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
        }
    }
}
