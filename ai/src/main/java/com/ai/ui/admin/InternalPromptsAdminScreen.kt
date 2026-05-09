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

/** Housekeeping → Prompts. Two cards: bundled-asset maintenance for
 *  Internal prompts (Load + Reset under one card) and Example prompts
 *  (Load only — examples are user-curated, no Reset that would wipe
 *  the user's set). */
@Composable
fun PromptsAdminScreen(
    onLoadBundledPrompts: () -> Int,
    onResetBundledPrompts: () -> Int,
    onLoadBundledExamples: () -> Int,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var internalStatus by remember { mutableStateOf<String?>(null) }
    var exampleStatus by remember { mutableStateOf<String?>(null) }
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
                        internalStatus = if (loaded > 0) {
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
        TitleBar(helpTopic = "prompts_admin", title = "Prompts", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Internal prompts", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Load merges any prompt in assets/prompts.json that's missing — matched by (category, name); existing rows with the same pair keep your edits. Reset wipes every Internal prompt (including ones you authored) and reloads the bundled set fresh.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = {
                            val added = onLoadBundledPrompts()
                            internalStatus = when {
                                added == 0 -> "No new prompts in assets/prompts.json"
                                added == 1 -> "Added 1 new prompt"
                                else -> "Added $added new prompts"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                    ) { Text("Load new prompts from assets/prompts.json", maxLines = 1, softWrap = false) }
                    Button(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                    ) { Text("Reset Internal Prompts to assets/prompts.json", maxLines = 1, softWrap = false) }
                    internalStatus?.let {
                        Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Example prompts", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Adds any prompt in assets/examples.json that's missing — matched by case-insensitive title. Existing prompts (including ones you authored) are left strictly alone, never overwritten or wiped.",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                    Button(
                        onClick = {
                            val added = onLoadBundledExamples()
                            exampleStatus = when {
                                added == 0 -> "No new prompts in assets/examples.json"
                                added == 1 -> "Added 1 new example prompt"
                                else -> "Added $added new example prompts"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                    ) { Text("Add new prompts from assets/examples.json", maxLines = 1, softWrap = false) }
                    exampleStatus?.let {
                        Text(it, fontSize = 12.sp, color = AppColors.TextTertiary)
                    }
                }
            }
        }
    }
}
