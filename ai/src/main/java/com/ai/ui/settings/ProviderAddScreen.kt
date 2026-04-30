package com.ai.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.ProviderRegistry
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar

/**
 * Edit form for creating a new provider entry. Mirrors every field on
 * `ProviderDefinition` so a user-defined provider behaves identically to
 * the ones that ship in `assets/setup.json`.
 */
@Composable
fun ProviderAddScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSaved: (AppService) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var defaultModel by remember { mutableStateOf("") }
    var apiFormat by remember { mutableStateOf(ApiFormat.OPENAI_COMPATIBLE) }
    var chatPath by remember { mutableStateOf("v1/chat/completions") }
    var modelsPath by remember { mutableStateOf("v1/models") }
    var modelListFormat by remember { mutableStateOf("object") }
    var defaultModelSource by remember { mutableStateOf("API") }
    var adminUrl by remember { mutableStateOf("") }
    var openRouterName by remember { mutableStateOf("") }
    var litellmPrefix by remember { mutableStateOf("") }
    var modelFilter by remember { mutableStateOf("") }
    var seedFieldName by remember { mutableStateOf("seed") }
    var prefsKey by remember { mutableStateOf("") }
    var extractApiCost by remember { mutableStateOf(false) }
    var supportsCitations by remember { mutableStateOf(false) }
    var supportsSearchRecency by remember { mutableStateOf(false) }
    var costTicksDivisor by remember { mutableStateOf("") }
    var hardcodedModelsText by remember { mutableStateOf("") }

    val normalizedId = id.trim().uppercase()
    val idTaken = normalizedId.isNotBlank() && AppService.findById(normalizedId) != null
    val canSave = normalizedId.isNotBlank() && !idTaken &&
        displayName.isNotBlank() && baseUrl.isNotBlank() && defaultModel.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Add provider", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            SectionCard("Basics") {
                OutlinedTextField(
                    value = id, onValueChange = { id = it.uppercase().replace(Regex("[^A-Z0-9_]"), "_") },
                    label = { Text("ID (uppercase, unique)") }, supportingText = {
                        if (idTaken) Text("Already in use", color = AppColors.Red, fontSize = 11.sp)
                    },
                    isError = idTaken, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                    label = { Text("Display name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL (https://…/)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defaultModel, onValueChange = { defaultModel = it },
                    label = { Text("Default model") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = adminUrl, onValueChange = { adminUrl = it },
                    label = { Text("Admin URL (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }

            SectionCard("API") {
                Text("Format", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiFormat.entries.forEach { fmt ->
                        FilterChip(selected = apiFormat == fmt, onClick = { apiFormat = fmt }, label = { Text(fmt.name, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = chatPath, onValueChange = { chatPath = it },
                    label = { Text("Chat path") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = modelsPath, onValueChange = { modelsPath = it },
                    label = { Text("Models path") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                Text("Model list format", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("object", "array").forEach {
                        FilterChip(selected = modelListFormat == it, onClick = { modelListFormat = it }, label = { Text(it) })
                    }
                }
                OutlinedTextField(value = seedFieldName, onValueChange = { seedFieldName = it },
                    label = { Text("Seed field name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }

            SectionCard("Models") {
                Text("Default source", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("API", "MANUAL").forEach {
                        FilterChip(selected = defaultModelSource == it, onClick = { defaultModelSource = it }, label = { Text(it) })
                    }
                }
                OutlinedTextField(value = modelFilter, onValueChange = { modelFilter = it },
                    label = { Text("Model filter regex (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = hardcodedModelsText, onValueChange = { hardcodedModelsText = it },
                    label = { Text("Hardcoded models (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }

            SectionCard("Pricing & cost") {
                OutlinedTextField(value = openRouterName, onValueChange = { openRouterName = it },
                    label = { Text("OpenRouter name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = litellmPrefix, onValueChange = { litellmPrefix = it },
                    label = { Text("LiteLLM prefix (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = costTicksDivisor, onValueChange = { costTicksDivisor = it },
                    label = { Text("Cost ticks divisor (optional double)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Extract API cost", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = extractApiCost, onCheckedChange = { extractApiCost = it })
                }
            }

            SectionCard("Features") {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Supports citations", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = supportsCitations, onCheckedChange = { supportsCitations = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Supports search recency", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = supportsSearchRecency, onCheckedChange = { supportsSearchRecency = it })
                }
            }

            CollapsibleCard(title = "Storage", summary = prefsKey.ifBlank { "auto" }) {
                OutlinedTextField(value = prefsKey, onValueChange = { prefsKey = it },
                    label = { Text("Prefs key (blank = id.lowercase())") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                val hardcoded = hardcodedModelsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val service = AppService(
                    id = normalizedId,
                    displayName = displayName.trim(),
                    baseUrl = baseUrl.trim(),
                    adminUrl = adminUrl.trim(),
                    defaultModel = defaultModel.trim(),
                    openRouterName = openRouterName.trim().ifBlank { null },
                    apiFormat = apiFormat,
                    typePaths = chatPath.trim().ifBlank { null }?.let { mapOf(com.ai.data.ModelType.CHAT to it) } ?: emptyMap(),
                    modelsPath = modelsPath.trim().ifBlank { null },
                    prefsKey = prefsKey.trim().ifBlank { normalizedId.lowercase() },
                    seedFieldName = seedFieldName.trim().ifBlank { "seed" },
                    supportsCitations = supportsCitations,
                    supportsSearchRecency = supportsSearchRecency,
                    extractApiCost = extractApiCost,
                    costTicksDivisor = costTicksDivisor.trim().toDoubleOrNull(),
                    modelListFormat = modelListFormat,
                    modelFilter = modelFilter.trim().ifBlank { null },
                    litellmPrefix = litellmPrefix.trim().ifBlank { null },
                    hardcodedModels = hardcoded.ifEmpty { null },
                    defaultModelSource = defaultModelSource
                )
                ProviderRegistry.add(service)
                Toast.makeText(context, "Provider added", Toast.LENGTH_SHORT).show()
                onSaved(service)
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Add provider", maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White)
            content()
        }
    }
}
