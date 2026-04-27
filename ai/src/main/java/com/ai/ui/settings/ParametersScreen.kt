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
fun ParametersListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onAddParameters: () -> Unit,
    onEditParameters: (String) -> Unit
) {
    CrudListScreen(
        title = "Parameters",
        items = aiSettings.parameters,
        addLabel = "Add Parameter Preset",
        emptyMessage = "No parameter presets configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { params ->
            val count = listOfNotNull(
                params.temperature, params.maxTokens, params.topP, params.topK,
                params.frequencyPenalty, params.presencePenalty, params.seed,
                params.systemPrompt?.takeIf { it.isNotBlank() }
            ).size + (if (params.searchEnabled) 1 else 0) + (if (params.webSearchTool) 1 else 0)
            "$count parameters configured"
        },
        onAdd = onAddParameters,
        onEdit = { onEditParameters(it.id) },
        onDelete = { params -> onSave(aiSettings.removeParameters(params.id)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Parameter Preset",
        deleteEntityName = { it.name },
        itemKey = { it.id }
    )
}

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

    var name by remember { mutableStateOf(params?.name ?: "") }
    var temperature by remember { mutableStateOf(params?.temperature?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(params?.maxTokens?.toString() ?: "") }
    var topP by remember { mutableStateOf(params?.topP?.toString() ?: "") }
    var topK by remember { mutableStateOf(params?.topK?.toString() ?: "") }
    var frequencyPenalty by remember { mutableStateOf(params?.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember { mutableStateOf(params?.presencePenalty?.toString() ?: "") }
    var seed by remember { mutableStateOf(params?.seed?.toString() ?: "") }
    var systemPrompt by remember { mutableStateOf(params?.systemPrompt ?: "") }
    var responseFormatJson by remember { mutableStateOf(params?.responseFormatJson ?: false) }
    var searchEnabled by remember { mutableStateOf(params?.searchEnabled ?: false) }
    var returnCitations by remember { mutableStateOf(params?.returnCitations ?: true) }
    var searchRecency by remember { mutableStateOf(params?.searchRecency ?: "") }
    var webSearchTool by remember { mutableStateOf(params?.webSearchTool ?: false) }
    var reasoningEffort by remember { mutableStateOf(params?.reasoningEffort ?: "") }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit Parameters" else "Add Parameters", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Preset name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            Text("Parameters", fontWeight = FontWeight.Bold, color = Color.White)
            OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text("Temperature (0.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text("Max tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text("Top P (0.0 - 1.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = topK, onValueChange = { topK = it }, label = { Text("Top K") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = frequencyPenalty, onValueChange = { frequencyPenalty = it }, label = { Text("Frequency penalty (-2.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = presencePenalty, onValueChange = { presencePenalty = it }, label = { Text("Presence penalty (-2.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = seed, onValueChange = { seed = it }, label = { Text("Seed") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())

            Text("System Prompt", fontWeight = FontWeight.Bold, color = Color.White)
            OutlinedTextField(
                value = systemPrompt, onValueChange = { systemPrompt = it },
                label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 6, colors = AppColors.outlinedFieldColors()
            )

            Text("Options", fontWeight = FontWeight.Bold, color = Color.White)
            Row(modifier = Modifier.fillMaxWidth().clickable { responseFormatJson = !responseFormatJson }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = responseFormatJson, onCheckedChange = { responseFormatJson = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Response format: JSON", color = Color.White)
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { searchEnabled = !searchEnabled }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = searchEnabled, onCheckedChange = { searchEnabled = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Enable web search (search:true flag)", color = Color.White)
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { webSearchTool = !webSearchTool }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = webSearchTool, onCheckedChange = { webSearchTool = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Web search tool (Anthropic/Gemini/Responses)", color = Color.White)
            }
            Row(modifier = Modifier.fillMaxWidth().clickable { returnCitations = !returnCitations }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = returnCitations, onCheckedChange = { returnCitations = it })
                Spacer(modifier = Modifier.width(8.dp)); Text("Return citations", color = Color.White)
            }

            Text("Search Recency", fontWeight = FontWeight.Bold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("", "day", "week", "month", "year").forEach { option ->
                    FilterChip(
                        selected = searchRecency == option,
                        onClick = { searchRecency = option },
                        label = { Text(option.ifEmpty { "None" }) }
                    )
                }
            }

            Text("Reasoning Effort", fontWeight = FontWeight.Bold, color = Color.White)
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

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = params?.id ?: java.util.UUID.randomUUID().toString()
                onSave(Parameters(
                    id, name.trim(), temperature.toFloatOrNull(), maxTokens.toIntOrNull(),
                    topP.toFloatOrNull(), topK.toIntOrNull(), frequencyPenalty.toFloatOrNull(),
                    presencePenalty.toFloatOrNull(), systemPrompt.takeIf { it.isNotBlank() },
                    null, seed.toIntOrNull(), responseFormatJson, searchEnabled, returnCitations,
                    searchRecency.takeIf { it.isNotBlank() },
                    webSearchTool,
                    reasoningEffort.takeIf { it.isNotBlank() }
                ))
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
