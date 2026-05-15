package com.ai.ui.history

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.ExamplePrompt
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** Picker for the "Start with an example prompt" entry on the
 *  AI Reports hub. Lists every [com.ai.model.ExamplePrompt] in the
 *  user's library with a search field; tapping a row hands the
 *  (title, text) pair back to the caller, which routes it through
 *  AI_NEW_REPORT_WITH_PARAMS to seed the New Report screen. */
@Composable
fun ExamplePromptPickerScreen(
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSelectEntry: (ExamplePrompt) -> Unit
) {
    BackHandler { onNavigateBack() }
    var search by rememberSaveable { mutableStateOf("") }
    val all = aiSettings.examplePrompts
    val filtered = remember(all, search) {
        if (search.isBlank()) all
        else all.filter {
            val q = search.lowercase(java.util.Locale.ROOT)
            it.title.lowercase(java.util.Locale.ROOT).contains(q) ||
                it.text.lowercase(java.util.Locale.ROOT).contains(q)
        }
    }
    val sorted = remember(filtered) { filtered.sortedBy { it.title.lowercase(java.util.Locale.ROOT) } }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "example_prompt_picker", title = "Pick an example prompt", onBackClick = onNavigateBack)

        OutlinedTextField(
            value = search, onValueChange = { search = it },
            placeholder = { Text("Search example prompts...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
        )
        Text("${sorted.size} of ${all.size} prompts", fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(modifier = Modifier.height(8.dp))

        if (all.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No example prompts yet.", color = AppColors.TextTertiary, fontSize = 14.sp)
                    Text("Add some under AI Setup → Prompt management → Example prompts.", color = AppColors.TextDim, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sorted, key = { it.id }) { entry ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectEntry(entry) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            entry.title.ifBlank { "(untitled)" },
                            fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            entry.text.lineSequence().firstOrNull().orEmpty().take(120),
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }
    }
}
