package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

/**
 * AI Parameters list screen - shows all parameter presets with add/edit/delete.
 */
@Composable
fun AiParametersListScreen(
    aiSettings: AiSettings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onAddParameters: () -> Unit,
    onEditParameters: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<AiParameters?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Parameters",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add parameters button
        Button(
            onClick = onAddParameters,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text("Add Parameters")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (aiSettings.parameters.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No parameter presets configured.\nAdd a preset to reuse parameters across agents.",
                    color = AiColors.TextTertiary,
                    fontSize = 16.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                aiSettings.parameters.sortedBy { it.name.lowercase() }.forEach { params ->
                    val configuredCount = listOfNotNull(
                        params.temperature, params.maxTokens, params.topP, params.topK,
                        params.frequencyPenalty, params.presencePenalty,
                        params.systemPrompt?.takeIf { it.isNotBlank() },
                        params.stopSequences?.takeIf { it.isNotEmpty() }, params.seed
                    ).size + listOf(params.responseFormatJson, params.searchEnabled, params.returnCitations).count { it } +
                        (if (params.searchRecency != null) 1 else 0)
                    SettingsListItemCard(
                        title = params.name,
                        subtitle = "$configuredCount parameter${if (configuredCount == 1) "" else "s"} configured",
                        onClick = { onEditParameters(params.id) },
                        onDelete = { showDeleteDialog = params }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { params ->
        DeleteConfirmationDialog(
            entityType = "Parameters",
            entityName = params.name,
            onConfirm = {
                onSave(aiSettings.removeParameters(params.id))
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}

/**
 * Parameters edit screen for creating or editing parameter presets.
 */
@Composable
fun ParametersEditScreen(
    params: AiParameters?,
    existingNames: Set<String>,
    onSave: (AiParameters) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isEditing = params != null

    var name by remember { mutableStateOf(params?.name ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }

    // Parameter states
    var temperatureEnabled by remember { mutableStateOf(params?.temperature != null) }
    var temperature by remember { mutableStateOf(params?.temperature?.toString() ?: "0.7") }

    var maxTokensEnabled by remember { mutableStateOf(params?.maxTokens != null) }
    var maxTokens by remember { mutableStateOf(params?.maxTokens?.toString() ?: "2048") }

    var topPEnabled by remember { mutableStateOf(params?.topP != null) }
    var topP by remember { mutableStateOf(params?.topP?.toString() ?: "1.0") }

    var topKEnabled by remember { mutableStateOf(params?.topK != null) }
    var topK by remember { mutableStateOf(params?.topK?.toString() ?: "40") }

    var frequencyPenaltyEnabled by remember { mutableStateOf(params?.frequencyPenalty != null) }
    var frequencyPenalty by remember { mutableStateOf(params?.frequencyPenalty?.toString() ?: "0.0") }

    var presencePenaltyEnabled by remember { mutableStateOf(params?.presencePenalty != null) }
    var presencePenalty by remember { mutableStateOf(params?.presencePenalty?.toString() ?: "0.0") }

    var systemPromptEnabled by remember { mutableStateOf(params?.systemPrompt != null) }
    var systemPrompt by remember { mutableStateOf(params?.systemPrompt ?: "") }

    var seedEnabled by remember { mutableStateOf(params?.seed != null) }
    var seed by remember { mutableStateOf(params?.seed?.toString() ?: "") }

    var responseFormatJson by remember { mutableStateOf(params?.responseFormatJson ?: false) }
    var searchEnabled by remember { mutableStateOf(params?.searchEnabled ?: false) }
    var returnCitations by remember { mutableStateOf(params?.returnCitations ?: false) }

    var searchRecencyEnabled by remember { mutableStateOf(params?.searchRecency != null) }
    var searchRecency by remember { mutableStateOf(params?.searchRecency ?: "week") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = if (isEditing) "Edit Parameters" else "Add Parameters",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Parameters name field
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("Preset Name") },
                placeholder = { Text("Enter a name for this preset") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = AiColors.Red) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AiColors.Purple,
                    unfocusedBorderColor = AiColors.BorderUnfocused,
                    focusedLabelColor = AiColors.Purple,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Parameters section
            Text(
                text = "Parameters",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = AiColors.Purple
            )

            Text(
                text = "Toggle parameters on/off. Only enabled parameters will be applied.",
                fontSize = 12.sp,
                color = AiColors.TextTertiary
            )

            // Temperature
            ParameterToggleField(
                label = "Temperature",
                description = "Randomness (0.0-2.0)",
                enabled = temperatureEnabled,
                onEnabledChange = { temperatureEnabled = it },
                value = temperature,
                onValueChange = { temperature = it },
                keyboardType = KeyboardType.Decimal
            )

            // Max Tokens
            ParameterToggleField(
                label = "Max Tokens",
                description = "Maximum response length",
                enabled = maxTokensEnabled,
                onEnabledChange = { maxTokensEnabled = it },
                value = maxTokens,
                onValueChange = { maxTokens = it },
                keyboardType = KeyboardType.Number
            )

            // Top P
            ParameterToggleField(
                label = "Top P",
                description = "Nucleus sampling (0.0-1.0)",
                enabled = topPEnabled,
                onEnabledChange = { topPEnabled = it },
                value = topP,
                onValueChange = { topP = it },
                keyboardType = KeyboardType.Decimal
            )

            // Top K
            ParameterToggleField(
                label = "Top K",
                description = "Vocabulary limit",
                enabled = topKEnabled,
                onEnabledChange = { topKEnabled = it },
                value = topK,
                onValueChange = { topK = it },
                keyboardType = KeyboardType.Number
            )

            // Frequency Penalty
            ParameterToggleField(
                label = "Frequency Penalty",
                description = "Reduces repetition (-2.0 to 2.0)",
                enabled = frequencyPenaltyEnabled,
                onEnabledChange = { frequencyPenaltyEnabled = it },
                value = frequencyPenalty,
                onValueChange = { frequencyPenalty = it },
                keyboardType = KeyboardType.Decimal
            )

            // Presence Penalty
            ParameterToggleField(
                label = "Presence Penalty",
                description = "Encourages new topics (-2.0 to 2.0)",
                enabled = presencePenaltyEnabled,
                onEnabledChange = { presencePenaltyEnabled = it },
                value = presencePenalty,
                onValueChange = { presencePenalty = it },
                keyboardType = KeyboardType.Decimal
            )

            // Seed
            ParameterToggleField(
                label = "Seed",
                description = "For reproducibility",
                enabled = seedEnabled,
                onEnabledChange = { seedEnabled = it },
                value = seed,
                onValueChange = { seed = it },
                keyboardType = KeyboardType.Number
            )

            // System Prompt
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("System Prompt", color = Color.White, fontWeight = FontWeight.Medium)
                            Text("System instruction", fontSize = 12.sp, color = AiColors.TextTertiary)
                        }
                        Switch(
                            checked = systemPromptEnabled,
                            onCheckedChange = { systemPromptEnabled = it }
                        )
                    }
                    if (systemPromptEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text("Enter system prompt...") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AiColors.Purple,
                                unfocusedBorderColor = AiColors.BorderUnfocused,
                                cursorColor = Color.White
                            )
                        )
                    }
                }
            }

            // Boolean options
            Text(
                text = "Options",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = AiColors.Purple
            )

            // Response Format JSON
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("JSON Response Format", color = Color.White)
                        Text("Request JSON output (OpenAI)", fontSize = 12.sp, color = AiColors.TextTertiary)
                    }
                    Switch(
                        checked = responseFormatJson,
                        onCheckedChange = { responseFormatJson = it }
                    )
                }
            }

            // Search Enabled
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Web Search", color = Color.White)
                        Text("Enable web search (xAI, Perplexity)", fontSize = 12.sp, color = AiColors.TextTertiary)
                    }
                    Switch(
                        checked = searchEnabled,
                        onCheckedChange = { searchEnabled = it }
                    )
                }
            }

            // Return Citations
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Return Citations", color = Color.White)
                        Text("Include citations (Perplexity)", fontSize = 12.sp, color = AiColors.TextTertiary)
                    }
                    Switch(
                        checked = returnCitations,
                        onCheckedChange = { returnCitations = it }
                    )
                }
            }

            // Search Recency
            Card(
                colors = CardDefaults.cardColors(containerColor = AiColors.CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Search Recency", color = Color.White, fontWeight = FontWeight.Medium)
                            Text("Filter search results by time (Perplexity)", fontSize = 12.sp, color = AiColors.TextTertiary)
                        }
                        Switch(
                            checked = searchRecencyEnabled,
                            onCheckedChange = { searchRecencyEnabled = it }
                        )
                    }
                    if (searchRecencyEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("day", "week", "month", "year").forEach { option ->
                                FilterChip(
                                    selected = searchRecency == option,
                                    onClick = { searchRecency = option },
                                    label = { Text(option.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Save button
        Button(
            onClick = {
                // Validate
                when {
                    name.isBlank() -> {
                        nameError = "Name is required"
                    }
                    name.trim() in existingNames -> {
                        nameError = "A preset with this name already exists"
                    }
                    else -> {
                        val newParams = AiParameters(
                            id = params?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            temperature = if (temperatureEnabled) temperature.toFloatOrNull() else null,
                            maxTokens = if (maxTokensEnabled) maxTokens.toIntOrNull() else null,
                            topP = if (topPEnabled) topP.toFloatOrNull() else null,
                            topK = if (topKEnabled) topK.toIntOrNull() else null,
                            frequencyPenalty = if (frequencyPenaltyEnabled) frequencyPenalty.toFloatOrNull() else null,
                            presencePenalty = if (presencePenaltyEnabled) presencePenalty.toFloatOrNull() else null,
                            systemPrompt = if (systemPromptEnabled && systemPrompt.isNotBlank()) systemPrompt else null,
                            stopSequences = null,  // Not implemented in this UI
                            seed = if (seedEnabled) seed.toIntOrNull() else null,
                            responseFormatJson = responseFormatJson,
                            searchEnabled = searchEnabled,
                            returnCitations = returnCitations,
                            searchRecency = if (searchRecencyEnabled) searchRecency else null
                        )
                        onSave(newParams)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AiColors.Purple)
        ) {
            Text(if (isEditing) "Save Changes" else "Create Preset")
        }
    }
}

/**
 * Reusable parameter toggle field with switch and text input.
 */
@Composable
private fun ParameterToggleField(
    label: String,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiColors.CardBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, color = Color.White, fontWeight = FontWeight.Medium)
                    Text(description, fontSize = 12.sp, color = AiColors.TextTertiary)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AiColors.Purple,
                        unfocusedBorderColor = AiColors.BorderUnfocused,
                        cursorColor = Color.White
                    )
                )
            }
        }
    }
}

/**
 * Reusable Parameters selector with multi-select support.
 * Shows a "Parameters" button that opens a multi-select dialog with checkboxes.
 * A paperclip icon is shown when parameters are connected.
 * Can be used in Agent, Flock, Swarm, Provider settings, and Report screens.
 */
@Composable
fun ParametersSelector(
    aiSettings: AiSettings,
    selectedParametersIds: List<String>,
    onParamsSelected: (List<String>) -> Unit,
    label: String = "Parameters"
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val selectedParams = selectedParametersIds.mapNotNull { id ->
        aiSettings.parameters.find { it.id == id }
    }
    val availableParams = aiSettings.parameters
        .filter { it.id !in selectedParametersIds }
        .sortedBy { it.name.lowercase() }

    val summary = if (selectedParams.isEmpty()) "none" else "${selectedParams.size} selected"
    CollapsibleCard(title = label, summary = summary) {
        if (selectedParams.isEmpty()) {
            Text(
                text = "No parameter presets assigned",
                style = MaterialTheme.typography.bodySmall,
                color = AiColors.TextTertiary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                selectedParams.forEach { params ->
                    val configuredCount = listOfNotNull(
                        params.temperature, params.maxTokens, params.topP, params.topK,
                        params.frequencyPenalty, params.presencePenalty,
                        params.systemPrompt?.takeIf { it.isNotBlank() }, params.seed
                    ).size + listOf(params.responseFormatJson, params.searchEnabled, params.returnCitations)
                        .count { it } + (if (params.searchRecency != null) 1 else 0)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A3A4A), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(params.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "$configuredCount param${if (configuredCount == 1) "" else "s"}",
                                fontSize = 12.sp, color = Color.Gray
                            )
                        }
                        IconButton(
                            onClick = { onParamsSelected(selectedParametersIds.filter { it != params.id }) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("\u2715", color = Color(0xFFFF6666))
                        }
                    }
                }
            }
        }

        if (availableParams.isNotEmpty()) {
            Button(
                onClick = { showAddDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("+ Add", fontSize = 12.sp)
            }
        } else if (aiSettings.parameters.isEmpty()) {
            Text(
                "No parameter presets configured.\nGo to AI Setup > Parameters to create presets.",
                fontSize = 12.sp, color = Color.Gray
            )
        }
    }

    if (showAddDialog && availableParams.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Parameters", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableParams.forEach { params ->
                        val configuredCount = listOfNotNull(
                            params.temperature, params.maxTokens, params.topP, params.topK,
                            params.frequencyPenalty, params.presencePenalty,
                            params.systemPrompt?.takeIf { it.isNotBlank() }, params.seed
                        ).size + listOf(params.responseFormatJson, params.searchEnabled, params.returnCitations)
                            .count { it } + (if (params.searchRecency != null) 1 else 0)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onParamsSelected(selectedParametersIds + params.id)
                                    showAddDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(params.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(
                                    "$configuredCount param${if (configuredCount == 1) "" else "s"} configured",
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
            confirmButton = {}
        )
    }
}

/**
 * Standalone multi-select parameters dialog.
 * Can be used directly when managing showDialog state externally.
 */
@Composable
fun ParametersSelectorDialog(
    aiSettings: AiSettings,
    selectedParametersIds: List<String>,
    onParamsSelected: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var localSelection by remember { mutableStateOf(selectedParametersIds.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Parameters", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Clear All option
                TextButton(
                    onClick = { localSelection = emptySet() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear All", color = AiColors.Red)
                }

                if (aiSettings.parameters.isNotEmpty()) {
                    aiSettings.parameters.sortedBy { it.name.lowercase() }.forEach { params ->
                        val configuredCount = listOfNotNull(
                            params.temperature,
                            params.maxTokens,
                            params.topP,
                            params.topK,
                            params.frequencyPenalty,
                            params.presencePenalty,
                            params.systemPrompt?.takeIf { it.isNotBlank() },
                            params.seed
                        ).size + listOf(
                            params.responseFormatJson,
                            params.searchEnabled,
                            params.returnCitations
                        ).count { it } + (if (params.searchRecency != null) 1 else 0)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    localSelection = if (params.id in localSelection) {
                                        localSelection - params.id
                                    } else {
                                        localSelection + params.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = params.id in localSelection,
                                onCheckedChange = { checked ->
                                    localSelection = if (checked) {
                                        localSelection + params.id
                                    } else {
                                        localSelection - params.id
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(params.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(
                                    "$configuredCount parameter${if (configuredCount == 1) "" else "s"} configured",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "No parameter presets configured.\nGo to AI Setup > Parameters to create presets.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Preserve order: keep existing order for items still selected, append new ones
                val orderedIds = selectedParametersIds.filter { it in localSelection } +
                    localSelection.filter { it !in selectedParametersIds }
                onParamsSelected(orderedIds)
            }) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
