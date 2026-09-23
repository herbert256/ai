package com.ai.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** Prompt selection stays inside the AI app for instruction-only handoffs. */
@Composable
fun ExternalPromptPickerScreen(
    settings: Settings,
    request: PendingExternalReport,
    onCancel: () -> Unit,
    onSelected: (PendingExternalReport) -> Unit
) {
    // Keyed on the request: a second external request arriving while this
    // picker is open reuses the composable, and must not inherit the first
    // request's chosen prompt.
    var selectedPrompt by remember(request) { mutableStateOf<SavedExternalPrompt?>(null) }
    var search by remember(request) { mutableStateOf("") }
    val back = { if (selectedPrompt == null) onCancel() else { selectedPrompt = null; search = "" } }
    BackHandler { back() }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(
            title = if (selectedPrompt == null) "Choose saved prompt" else "Choose system prompt",
            subject = request.title.orEmpty(), onBackClick = { back() }
        )
        Text("Choose a report prompt stored in this AI app.", color = AppColors.TextSecondary)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val prompt = selectedPrompt
            if (prompt == null) {
                val choices = externalPromptChoices(settings).filter { it.name.contains(search, ignoreCase = true) }
                if (choices.isEmpty()) item { Text("No matching saved prompts. Manage prompts in AI setup.", color = AppColors.TextSecondary) }
                items(choices) { choice ->
                    OutlinedButton(onClick = {
                        if (request.literalSystemPrompt != null) onSelected(selectExternalPrompt(request, settings, choice, null))
                        else { selectedPrompt = choice; search = "" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(choice.name)
                    }
                }
            } else {
                item {
                    OutlinedButton(onClick = {
                        onSelected(selectExternalPrompt(request, settings, prompt,
                            prompt.system.takeIf { request.literalSystemPrompt == null }))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Use configured system prompts") }
                }
                items(settings.systemPrompts.filter { it.name.contains(search, ignoreCase = true) }.sortedBy { it.name.lowercase() }) { system ->
                    OutlinedButton(onClick = {
                        onSelected(selectExternalPrompt(request, settings, prompt, system.id))
                    }, modifier = Modifier.fillMaxWidth()) { Text(system.name) }
                }
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
