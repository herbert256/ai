package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Navigation card for an AI service.
 */
@Composable
fun AiServiceNavigationCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Color indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(accentColor, shape = MaterialTheme.shapes.small)
            )

            Text(
                text = "$title / $subtitle",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF888888)
            )
        }
    }
}

/**
 * Template for AI service settings screens.
 */
@Composable
fun AiServiceSettingsScreenTemplate(
    title: String,
    subtitle: String,
    accentColor: Color,
    onBackToAiSettings: () -> Unit,
    onBackToGame: () -> Unit,
    additionalContent: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EvalTitleBar(
            title = title,
            onBackClick = onBackToAiSettings,
            onEvalClick = onBackToGame
        )

        // Provider info with color indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(accentColor, shape = MaterialTheme.shapes.small)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAAAAAA)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Additional content (model selection, etc.)
        additionalContent()

    }
}

/**
 * Model selection section with fetch button (for APIs that support listing models).
 */
@Composable
fun ModelSelectionSection(
    selectedModel: String,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Model Selection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Button(
                onClick = onFetchModels,
                enabled = !isLoadingModels
            ) {
                if (isLoadingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading...")
                } else {
                    Text("Retrieve models")
                }
            }

            Text(
                text = "List of models:",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )

            var expanded by remember { mutableStateOf(false) }
            val modelsToShow = if (availableModels.isNotEmpty()) {
                availableModels
            } else {
                listOf(selectedModel)
            }

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selectedModel,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (expanded) "▲" else "▼",
                        color = Color.White
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    modelsToShow.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                onModelChange(model)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Model selection section with hardcoded list (for APIs that don't support listing models).
 */
@Composable
fun HardcodedModelSelectionSection(
    selectedModel: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Model Selection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Text(
                text = "List of models:",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )

            var expanded by remember { mutableStateOf(false) }

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selectedModel,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (expanded) "▲" else "▼",
                        color = Color.White
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                onModelChange(model)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * API key input section for provider settings.
 */
@Composable
fun ApiKeyInputSection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }

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
                text = "API Key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(
                            text = if (showApiKey) "Hide" else "Show",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
    }
}

/**
 * Unified model selection section with source toggle (API vs Manual).
 */
@Composable
fun UnifiedModelSelectionSection(
    modelSource: ModelSource,
    manualModels: List<String>,
    availableApiModels: List<String>,
    isLoadingModels: Boolean,
    onModelSourceChange: (ModelSource) -> Unit,
    onManualModelsChange: (List<String>) -> Unit,
    onFetchModels: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    var newModelName by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Models",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            // Model source toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model source:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
                FilterChip(
                    selected = modelSource == ModelSource.API,
                    onClick = { onModelSourceChange(ModelSource.API) },
                    label = { Text("API") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = modelSource == ModelSource.MANUAL,
                    onClick = { onModelSourceChange(ModelSource.MANUAL) },
                    label = { Text("Manual") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }

            // API mode: Fetch button and model list
            if (modelSource == ModelSource.API) {
                Button(
                    onClick = onFetchModels,
                    enabled = !isLoadingModels
                ) {
                    if (isLoadingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading...")
                    } else {
                        Text("Retrieve models")
                    }
                }

                if (availableApiModels.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        availableApiModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color(0xFF2A3A4A),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // Manual mode: Add button and model list management
            if (modelSource == ModelSource.MANUAL) {
                Button(
                    onClick = {
                        newModelName = ""
                        showAddDialog = true
                    }
                ) {
                    Text("+ Add model")
                }

                // Show current manual models with edit/delete
                if (manualModels.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        manualModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color(0xFF2A3A4A),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        editingModel = model
                                        newModelName = model
                                        showAddDialog = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("✎", color = Color(0xFFAAAAAA))
                                }
                                IconButton(
                                    onClick = {
                                        val newList = manualModels.filter { it != model }
                                        onManualModelsChange(newList)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("✕", color = Color(0xFFFF6666))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add/Edit model dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingModel = null
            },
            title = {
                Text(
                    if (editingModel != null) "Edit Model" else "Add Model",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = newModelName,
                    onValueChange = { newModelName = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newModelName.isNotBlank()) {
                            val newList = if (editingModel != null) {
                                manualModels.map { if (it == editingModel) newModelName.trim() else it }
                            } else {
                                manualModels + newModelName.trim()
                            }
                            onManualModelsChange(newList)
                        }
                        showAddDialog = false
                        editingModel = null
                    },
                    enabled = newModelName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editingModel = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Placeholder information card for prompts.
 */
@Composable
fun PromptPlaceholdersInfo() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A3A4A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Placeholder Variables",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = "@FEN@ - Will be replaced by the chess position in FEN notation",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            Text(
                text = "@PLAYER@ - Will be replaced by the player name",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            Text(
                text = "@SERVER@ - Will be replaced by 'lichess.org' or 'chess.com'",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            Text(
                text = "@DATE@ - Will be replaced by the current date (e.g., 'Friday, January 24th')",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
        }
    }
}

/**
 * Single prompt editing card.
 */
@Composable
fun SinglePromptCard(
    title: String,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onResetToDefault: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                TextButton(onClick = onResetToDefault) {
                    Text(
                        text = "Reset",
                        color = Color(0xFF6B9BFF)
                    )
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 300.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF555555)
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            )
        }
    }
}

/**
 * All prompts editing section for AI service settings.
 */
@Composable
fun AllPromptsSection(
    gamePrompt: String,
    serverPlayerPrompt: String,
    otherPlayerPrompt: String,
    onGamePromptChange: (String) -> Unit,
    onServerPlayerPromptChange: (String) -> Unit,
    onOtherPlayerPromptChange: (String) -> Unit,
    onResetGamePrompt: () -> Unit,
    onResetServerPlayerPrompt: () -> Unit,
    onResetOtherPlayerPrompt: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Placeholder info card
        PromptPlaceholdersInfo()

        // Game prompt
        SinglePromptCard(
            title = "Game prompt",
            prompt = gamePrompt,
            onPromptChange = onGamePromptChange,
            onResetToDefault = onResetGamePrompt
        )

        // Server player prompt
        SinglePromptCard(
            title = "Chess server player prompt",
            prompt = serverPlayerPrompt,
            onPromptChange = onServerPlayerPromptChange,
            onResetToDefault = onResetServerPlayerPrompt
        )

        // Other player prompt
        SinglePromptCard(
            title = "Other player prompt",
            prompt = otherPlayerPrompt,
            onPromptChange = onOtherPlayerPromptChange,
            onResetToDefault = onResetOtherPlayerPrompt
        )
    }
}

/**
 * Legacy prompt editing section for AI service settings.
 * @deprecated Use AllPromptsSection instead
 */
@Composable
fun PromptEditSection(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onResetToDefault: () -> Unit
) {
    SinglePromptCard(
        title = "Game prompt",
        prompt = prompt,
        onPromptChange = onPromptChange,
        onResetToDefault = onResetToDefault
    )
}
