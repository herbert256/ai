package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.*
import com.ai.ui.shared.*

@Composable
fun ParametersEditScreen(
    params: Parameters?,
    existingNames: Set<String>,
    onSave: (Parameters) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val isEditing = params != null
    var resetTick by remember { mutableStateOf(0) }

    var name by remember(resetTick) { mutableStateOf(params?.name ?: "") }
    var temperature by remember(resetTick) { mutableStateOf(params?.temperature?.toString() ?: "") }
    var maxTokens by remember(resetTick) { mutableStateOf(params?.maxTokens?.toString() ?: "") }
    var topP by remember(resetTick) { mutableStateOf(params?.topP?.toString() ?: "") }
    var topK by remember(resetTick) { mutableStateOf(params?.topK?.toString() ?: "") }
    var frequencyPenalty by remember(resetTick) { mutableStateOf(params?.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember(resetTick) { mutableStateOf(params?.presencePenalty?.toString() ?: "") }
    var seed by remember(resetTick) { mutableStateOf(params?.seed?.toString() ?: "") }
    var systemPrompt by remember(resetTick) { mutableStateOf(params?.systemPrompt ?: "") }
    var responseFormatJson by remember(resetTick) { mutableStateOf(params?.responseFormatJson ?: false) }
    var searchEnabled by remember(resetTick) { mutableStateOf(params?.searchEnabled ?: false) }
    var returnCitations by remember(resetTick) { mutableStateOf(params?.returnCitations ?: false) }
    var searchRecency by remember(resetTick) { mutableStateOf(params?.searchRecency ?: "") }
    var webSearchTool by remember(resetTick) { mutableStateOf(params?.webSearchTool ?: false) }
    var reasoningEffort by remember(resetTick) { mutableStateOf(params?.reasoningEffort ?: "") }

    val dup = com.ai.ui.shared.rememberDuplicateMode(
        isEditingExisting = params != null,
        onDuplicate = { name = "$name-copy" }
    )
    val isAddMode = dup.isAddMode
    val effectiveExistingNames = if (isAddMode && params != null) {
        existingNames + params.name.lowercase()
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "parameters_edit",
            title = if (isAddMode) "Add Parameters" else "Edit Parameters",
            subject = name,
            onBackClick = onBack,
            onCopyReport = null,
            onClear = { resetTick++ }
        )
        // Preserve stopSequences from the existing preset (no editor UI yet,
        // but the data model carries them — saving null dropped imported lists).
        fun buildParams(id: String) = Parameters(
            id, name.trim(), temperature.toFloatOrNull(), maxTokens.toIntOrNull(),
            topP.toFloatOrNull(), topK.toIntOrNull(), frequencyPenalty.toFloatOrNull(),
            presencePenalty.toFloatOrNull(), systemPrompt.takeIf { it.isNotBlank() },
            params?.stopSequences,
            seed.toIntOrNull(), responseFormatJson, searchEnabled, returnCitations,
            searchRecency.takeIf { it.isNotBlank() },
            webSearchTool,
            reasoningEffort.takeIf { it.isNotBlank() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isAddMode) {
            OutlinedButton(
                onClick = { onSave(buildParams(java.util.UUID.randomUUID().toString())); onBack() },
                enabled = nameError == null,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Create", maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            // Edit: no Save button — auto-persist while editing and on leave.
            com.ai.ui.shared.AutoSaveOnChange(
                current = if (nameError == null) buildParams(params!!.id) else null,
                onSave = onSave
            )
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Preset name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.DangerAccent) } } else null
            )

            // Numeric fields surface a number-pad keyboard. Decimal
            // for the floats so the user gets a "." key without
            // toggling alpha, and Number for integers (max tokens,
            // top K, seed). Without this every field popped the
            // alphabetic keyboard and the user had to tap toggle to
            // reach digits.
            val decKb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
            val intKb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            Text("Parameters", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text("Temperature (0.0 - 2.0)") }, keyboardOptions = decKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text("Max tokens") }, keyboardOptions = intKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text("Top P (0.0 - 1.0)") }, keyboardOptions = decKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = topK, onValueChange = { topK = it }, label = { Text("Top K") }, keyboardOptions = intKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = frequencyPenalty, onValueChange = { frequencyPenalty = it }, label = { Text("Frequency penalty (-2.0 - 2.0)") }, keyboardOptions = decKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = presencePenalty, onValueChange = { presencePenalty = it }, label = { Text("Presence penalty (-2.0 - 2.0)") }, keyboardOptions = decKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = seed, onValueChange = { seed = it }, label = { Text("Seed") }, keyboardOptions = intKb, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())

            Text("System Prompt", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            OutlinedTextField(
                value = systemPrompt, onValueChange = { systemPrompt = it },
                label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 6, colors = AppColors.outlinedFieldColors()
            )

            Text("Options", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(modifier = Modifier.fillMaxWidth().clickable { responseFormatJson = !responseFormatJson }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = responseFormatJson, onCheckedChange = { responseFormatJson = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Response format: JSON", color = AppColors.TextPrimary)
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { searchEnabled = !searchEnabled }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = searchEnabled, onCheckedChange = { searchEnabled = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Enable web search (search:true flag)", color = AppColors.TextPrimary)
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { webSearchTool = !webSearchTool }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = webSearchTool, onCheckedChange = { webSearchTool = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Web search tool (Anthropic/Gemini/Responses)", color = AppColors.TextPrimary)
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { returnCitations = !returnCitations }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = returnCitations, onCheckedChange = { returnCitations = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Return citations", color = AppColors.TextPrimary)
            }

            Text("Search Recency", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("", "day", "week", "month", "year").forEach { option ->
                    FilterChip(
                        selected = searchRecency == option,
                        onClick = { searchRecency = option },
                        label = { Text(option.ifEmpty { "None" }) }
                    )
                }
            }

            Text("Reasoning Effort", fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text("Only honored on reasoning-capable models (gpt-5/o-series, Gemini thinking, Claude with extended thinking). Ignored elsewhere.",
                fontSize = 11.sp, color = AppColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("", "low", "medium", "high").forEach { option ->
                    FilterChip(
                        selected = reasoningEffort == option,
                        onClick = { reasoningEffort = option },
                        label = { Text(option.ifEmpty { "None" }) }
                    )
                }
            }
        }

    }
}
