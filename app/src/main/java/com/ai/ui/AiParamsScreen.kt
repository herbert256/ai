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
 * AI Params list screen - shows all parameter presets with add/edit/delete.
 */
@Composable
fun AiParamsListScreen(
    aiSettings: AiSettings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onAddParams: () -> Unit,
    onEditParams: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<AiParams?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Params",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add params button
        Button(
            onClick = onAddParams,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Add Params")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (aiSettings.params.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No parameter presets configured.\nAdd a preset to reuse parameters across agents.",
                    color = Color(0xFF888888),
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
                aiSettings.params.sortedBy { it.name.lowercase() }.forEach { params ->
                    ParamsListItem(
                        params = params,
                        onClick = { onEditParams(params.id) },
                        onDelete = { showDeleteDialog = params }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { params ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Params") },
            text = { Text("Are you sure you want to delete \"${params.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newParams = aiSettings.params.filter { it.id != params.id }
                        onSave(aiSettings.copy(params = newParams))
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * List item for a params preset showing name and configured parameters count.
 */
@Composable
private fun ParamsListItem(
    params: AiParams,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // Count configured parameters
    val configuredCount = listOfNotNull(
        params.temperature,
        params.maxTokens,
        params.topP,
        params.topK,
        params.frequencyPenalty,
        params.presencePenalty,
        params.systemPrompt?.takeIf { it.isNotBlank() },
        params.stopSequences?.takeIf { it.isNotEmpty() },
        params.seed
    ).size + listOf(
        params.responseFormatJson,
        params.searchEnabled,
        params.returnCitations
    ).count { it } + (if (params.searchRecency != null) 1 else 0)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A3A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = params.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$configuredCount parameter${if (configuredCount == 1) "" else "s"} configured",
                    fontSize = 14.sp,
                    color = Color(0xFF888888)
                )
            }
            IconButton(onClick = onDelete) {
                Text("X", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Params edit screen for creating or editing parameter presets.
 */
@Composable
fun ParamsEditScreen(
    params: AiParams?,
    existingNames: Set<String>,
    onSave: (AiParams) -> Unit,
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
            title = if (isEditing) "Edit Params" else "Add Params",
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
            // Params name field
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
                supportingText = nameError?.let { { Text(it, color = Color(0xFFFF6B6B)) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedLabelColor = Color(0xFF8B5CF6),
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            // Parameters section
            Text(
                text = "Parameters",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color(0xFF8B5CF6)
            )

            Text(
                text = "Toggle parameters on/off. Only enabled parameters will be applied.",
                fontSize = 12.sp,
                color = Color(0xFF888888)
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
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
                            Text("System instruction", fontSize = 12.sp, color = Color(0xFF888888))
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
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444),
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
                color = Color(0xFF8B5CF6)
            )

            // Response Format JSON
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
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
                        Text("Request JSON output (OpenAI)", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                    Switch(
                        checked = responseFormatJson,
                        onCheckedChange = { responseFormatJson = it }
                    )
                }
            }

            // Search Enabled
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
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
                        Text("Enable web search (xAI, Perplexity)", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                    Switch(
                        checked = searchEnabled,
                        onCheckedChange = { searchEnabled = it }
                    )
                }
            }

            // Return Citations
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
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
                        Text("Include citations (Perplexity)", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                    Switch(
                        checked = returnCitations,
                        onCheckedChange = { returnCitations = it }
                    )
                }
            }

            // Search Recency
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
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
                            Text("Filter search results by time (Perplexity)", fontSize = 12.sp, color = Color(0xFF888888))
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
                        val newParams = AiParams(
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
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
                    Text(description, fontSize = 12.sp, color = Color(0xFF888888))
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
                        focusedBorderColor = Color(0xFF8B5CF6),
                        unfocusedBorderColor = Color(0xFF444444),
                        cursorColor = Color.White
                    )
                )
            }
        }
    }
}

/**
 * Reusable Parameters selector field with text display and Select button.
 * Can be used in Agent, Flock, Swarm, and Report screens.
 */
@Composable
fun ParametersSelector(
    aiSettings: AiSettings,
    selectedParamsId: String?,
    selectedParamsName: String,
    onParamsSelected: (String?, String) -> Unit,
    label: String = "Parameters"
) {
    var showParamsDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = selectedParamsName,
                    onValueChange = { /* Read-only - use Select button */ },
                    placeholder = { Text("No parameters selected", color = Color(0xFF666666)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF8B5CF6),
                        unfocusedBorderColor = Color(0xFF444444),
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Button(
                    onClick = { showParamsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Select", fontSize = 12.sp)
                }
            }
        }
    }

    // Parameters Selection Dialog
    if (showParamsDialog) {
        AlertDialog(
            onDismissRequest = { showParamsDialog = false },
            title = { Text("Select Parameters", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // None option (clears parameters)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onParamsSelected(null, "")
                                showParamsDialog = false
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedParamsId == null) Color(0xFF6366F1).copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("None", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("No parameters preset", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    // Available params presets
                    if (aiSettings.params.isNotEmpty()) {
                        Text(
                            "Available Presets",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        aiSettings.params.sortedBy { it.name.lowercase() }.forEach { params ->
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

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onParamsSelected(params.id, params.name)
                                        showParamsDialog = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedParamsId == params.id) Color(0xFF6366F1).copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(params.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text("$configuredCount parameter${if (configuredCount == 1) "" else "s"} configured", fontSize = 12.sp, color = Color.Gray)
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
                TextButton(onClick = { showParamsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
