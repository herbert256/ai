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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.launch

@Composable
fun ProviderSettingsScreen(
    service: AppService,
    aiSettings: Settings,
    isLoadingModels: Boolean = false,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onFetchModels: (String) -> Unit = {},
    onTestApiKey: suspend (AppService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {},
    onTestModelWithPrompt: (suspend (String) -> Pair<Boolean, String?>)? = null,
    onNavigateToTrace: ((String) -> Unit)? = null
) {
    BackHandler { onBackToSettings() }
    val scope = rememberCoroutineScope()

    val config = aiSettings.getProvider(service)
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var defaultModel by remember { mutableStateOf(config.model) }
    var modelSource by remember { mutableStateOf(config.modelSource) }
    var models by remember { mutableStateOf(config.models) }
    var adminUrl by remember { mutableStateOf(config.adminUrl) }
    var modelListUrl by remember { mutableStateOf(config.modelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(config.parametersIds) }
    var isInactive by remember { mutableStateOf(aiSettings.getProviderState(service) == "inactive") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }

    // Auto-save config
    LaunchedEffect(apiKey, defaultModel, modelSource, models, adminUrl, modelListUrl, selectedParametersIds) {
        val updated = aiSettings.withProvider(
            service, ProviderConfig(apiKey, defaultModel, modelSource, models, adminUrl, modelListUrl, selectedParametersIds)
        )
        if (updated != aiSettings) onSave(updated)
    }

    if (showParamsDialog) {
        com.ai.ui.chat.ParametersSelectorDialog(
            aiSettings = aiSettings, selectedIds = selectedParametersIds,
            onConfirm = { selectedParametersIds = it; showParamsDialog = false },
            onDismiss = { showParamsDialog = false }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = service.displayName, onBackClick = onBackToSettings, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // State toggle
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Provider inactive", modifier = Modifier.weight(1f), color = Color.White)
                    Switch(
                        checked = isInactive,
                        onCheckedChange = { inactive ->
                            isInactive = inactive
                            if (inactive) {
                                onProviderStateChange("inactive")
                            } else {
                                scope.launch {
                                    if (apiKey.isNotBlank()) {
                                        val error = onTestApiKey(service, apiKey, defaultModel)
                                        onProviderStateChange(if (error == null) "ok" else "error")
                                    } else {
                                        onProviderStateChange("not-used")
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // API Key
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("API Key", fontWeight = FontWeight.Bold, color = Color.White)
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; testResult = null },
                        label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, colors = AppColors.outlinedFieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (apiKey.isNotBlank()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isTesting = true; testResult = null
                                        val error = onTestApiKey(service, apiKey, defaultModel)
                                        testSuccess = error == null
                                        testResult = error ?: "Connection successful"
                                        onProviderStateChange(if (error == null) "ok" else "error")
                                        isTesting = false
                                    }
                                },
                                enabled = !isTesting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                            ) { Text(if (isTesting) "Testing..." else "Test", maxLines = 1, softWrap = false) }
                        }
                        Button(
                            onClick = onCreateAgent,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                        ) { Text("Create Agent", maxLines = 1, softWrap = false) }
                    }
                    testResult?.let {
                        Text(it, color = if (testSuccess) AppColors.Green else AppColors.Red, fontSize = 12.sp)
                    }
                }
            }

            // Default Model
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Default Model", fontWeight = FontWeight.Bold, color = Color.White)
                    OutlinedTextField(
                        value = defaultModel, onValueChange = { defaultModel = it },
                        label = { Text("Model name") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, colors = AppColors.outlinedFieldColors()
                    )
                }
            }

            // Parameters
            val pNames = selectedParametersIds.mapNotNull { aiSettings.getParametersById(it)?.name }
            OutlinedButton(
                onClick = { showParamsDialog = true }, modifier = Modifier.fillMaxWidth(),
                colors = if (pNames.isNotEmpty()) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
            ) { Text(if (pNames.isNotEmpty()) "Parameters: ${pNames.joinToString(", ")}" else "Parameters (none)", maxLines = 1, overflow = TextOverflow.Ellipsis) }

            // Model Source
            CollapsibleCard(title = "Models", summary = "${models.size} models") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = modelSource == ModelSource.API,
                        onClick = { modelSource = ModelSource.API },
                        label = { Text("API") }
                    )
                    FilterChip(
                        selected = modelSource == ModelSource.MANUAL,
                        onClick = { modelSource = ModelSource.MANUAL },
                        label = { Text("Manual") }
                    )
                }
                if (modelSource == ModelSource.API && apiKey.isNotBlank()) {
                    Button(
                        onClick = { onFetchModels(apiKey) },
                        enabled = !isLoadingModels,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                    ) { Text(if (isLoadingModels) "Fetching..." else "Fetch Models", maxLines = 1, softWrap = false) }
                }
                if (models.isNotEmpty()) {
                    Text("${models.size} models available", fontSize = 12.sp, color = AppColors.TextTertiary)
                }
            }

            // Model List URL
            CollapsibleCard(title = "Advanced", summary = null) {
                OutlinedTextField(
                    value = modelListUrl, onValueChange = { modelListUrl = it },
                    label = { Text("Custom model list URL") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = adminUrl, onValueChange = { adminUrl = it },
                    label = { Text("Admin URL") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}
