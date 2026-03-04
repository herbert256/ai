package com.ai.ui.admin

import android.content.Context
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.shared.*
import com.ai.viewmodel.AppViewModel
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== API Test Screen =====

@Composable
fun ApiTestScreen(
    onBackClick: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToEditRequest: () -> Unit,
    viewModel: AppViewModel
) {
    BackHandler { onBackClick() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val prefs = remember { context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE) }

    var isInitialized by remember { mutableStateOf(false) }
    val activeProviders = remember(uiState.aiSettings) { uiState.aiSettings.getActiveServices() }
    var selectedProvider by remember { mutableStateOf(
        prefs.getString("last_test_provider", null)?.let { AppService.findById(it) } ?: activeProviders.firstOrNull() ?: AppService.entries.first()
    ) }
    var apiUrl by remember { mutableStateOf(prefs.getString("last_test_api_url", selectedProvider.baseUrl) ?: selectedProvider.baseUrl) }
    var apiKey by remember { mutableStateOf(prefs.getString("last_test_api_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("last_test_model", selectedProvider.defaultModel) ?: selectedProvider.defaultModel) }
    var prompt by remember { mutableStateOf(prefs.getString("last_test_prompt", "Hello, how are you?") ?: "Hello, how are you?") }
    var systemPrompt by remember { mutableStateOf(prefs.getString("last_test_system_prompt", "") ?: "") }
    var temperature by remember { mutableStateOf(prefs.getString("last_test_temperature", "") ?: "") }
    var maxTokens by remember { mutableStateOf(prefs.getString("last_test_max_tokens", "") ?: "") }
    var showParamsCard by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }

    // Update fields when provider changes (after init)
    LaunchedEffect(selectedProvider) {
        if (isInitialized) {
            apiUrl = selectedProvider.baseUrl
            apiKey = uiState.aiSettings.getApiKey(selectedProvider)
            model = uiState.aiSettings.getModel(selectedProvider)
        }
        isInitialized = true
    }

    // Model selection dialog
    if (showModelDialog) {
        AlertDialog(onDismissRequest = { showModelDialog = false },
            title = { Text("Select Model") },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (isLoadingModels) { CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally)) }
                availableModels.forEach { m ->
                    TextButton(onClick = { model = m; showModelDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }},
            confirmButton = {}, dismissButton = { TextButton(onClick = { showModelDialog = false }) { Text("Cancel", maxLines = 1, softWrap = false) } })
    }

    // Endpoint selection dialog
    if (showEndpointDialog) {
        val endpoints = uiState.aiSettings.getEndpointsForProvider(selectedProvider)
        AlertDialog(onDismissRequest = { showEndpointDialog = false },
            title = { Text("Select Endpoint") },
            text = { Column {
                // Default endpoint
                TextButton(onClick = { apiUrl = selectedProvider.baseUrl; showEndpointDialog = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("${selectedProvider.baseUrl} (default)", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                endpoints.forEach { ep ->
                    TextButton(onClick = { apiUrl = ep.url; showEndpointDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("${ep.url} (${ep.name})", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    }
                }
            }},
            confirmButton = {}, dismissButton = { TextButton(onClick = { showEndpointDialog = false }) { Text("Cancel", maxLines = 1, softWrap = false) } })
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "API Test", onBackClick = onBackClick, onAiClick = onNavigateHome)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Provider selector
            Text("Provider", fontSize = 12.sp, color = AppColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val idx = AppService.entries.indexOf(selectedProvider)
                    selectedProvider = AppService.entries[(idx + 1) % AppService.entries.size]
                }, modifier = Modifier.weight(1f)) {
                    Text(selectedProvider.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Endpoint
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("Endpoint URL") },
                    modifier = Modifier.weight(1f), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedButton(onClick = { showEndpointDialog = true }) { Text("...", maxLines = 1, softWrap = false) }
            }

            // API Key
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())

            // Model
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") },
                    modifier = Modifier.weight(1f), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedButton(onClick = {
                    scope.launch {
                        isLoadingModels = true
                        availableModels = try {
                            withContext(Dispatchers.IO) { AnalysisRepository().fetchModels(selectedProvider, apiKey) }
                        } catch (_: Exception) { emptyList() }
                        isLoadingModels = false; showModelDialog = true
                    }
                }) { Text("...", maxLines = 1, softWrap = false) }
            }

            // Prompt
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, colors = AppColors.outlinedFieldColors())

            // Parameters card (collapsible)
            CollapsibleCard(title = "API Parameters", defaultExpanded = showParamsCard) {
                OutlinedTextField(value = systemPrompt, onValueChange = { systemPrompt = it }, label = { Text("System Prompt") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text("Temperature") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text("Max Tokens") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {
            prefs.edit().apply {
                putString("last_test_provider", selectedProvider.id)
                putString("last_test_api_url", apiUrl)
                putString("last_test_api_key", apiKey)
                putString("last_test_model", model)
                putString("last_test_prompt", prompt)
                putString("last_test_system_prompt", systemPrompt)
                putString("last_test_temperature", temperature)
                putString("last_test_max_tokens", maxTokens)
                remove("last_test_raw_json") // clear any previous raw JSON
            }.apply()
            onNavigateToEditRequest()
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Build Request", maxLines = 1, softWrap = false) }
    }
}

// ===== Edit API Request Screen =====

@Composable
fun EditApiRequestScreen(
    onBackClick: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceDetail: (String) -> Unit
) {
    BackHandler { onBackClick() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE) }
    val provider = remember { AppService.findById(prefs.getString("last_test_provider", "") ?: "") ?: AppService.entries.first() }
    val apiUrl = remember { prefs.getString("last_test_api_url", provider.baseUrl) ?: provider.baseUrl }
    val apiKey = remember { prefs.getString("last_test_api_key", "") ?: "" }
    val model = remember { prefs.getString("last_test_model", "") ?: "" }
    var isLoading by remember { mutableStateOf(false) }

    // Build or load JSON
    val initialJson = remember {
        val rawJson = prefs.getString("last_test_raw_json", null)
        if (!rawJson.isNullOrBlank()) rawJson
        else {
            val prompt = prefs.getString("last_test_prompt", "") ?: ""
            val systemPrompt = prefs.getString("last_test_system_prompt", "") ?: ""
            val temperature = prefs.getString("last_test_temperature", "")?.toFloatOrNull()
            val maxTokens = prefs.getString("last_test_max_tokens", "")?.toIntOrNull()
            val jsonObj = buildMap<String, Any> {
                put("model", model)
                val msgs = mutableListOf<Map<String, String>>()
                if (systemPrompt.isNotBlank()) msgs.add(mapOf("role" to "system", "content" to systemPrompt))
                msgs.add(mapOf("role" to "user", "content" to prompt))
                put("messages", msgs)
                temperature?.let { put("temperature", it) }
                maxTokens?.let { put("max_tokens", it) }
            }
            GsonBuilder().setPrettyPrinting().create().toJson(jsonObj)
        }
    }
    var editableJson by remember { mutableStateOf(initialJson) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Edit Request", onBackClick = onBackClick, onAiClick = onNavigateHome)

        // Info card
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("${provider.displayName} / $model", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(apiUrl, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // JSON editor
        OutlinedTextField(value = editableJson, onValueChange = { editableJson = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White),
            colors = AppColors.outlinedFieldColors())

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = {
            scope.launch {
                isLoading = true
                try {
                    val traceCountBefore = ApiTracer.getTraceCount()
                    val wasEnabled = ApiTracer.isTracingEnabled
                    ApiTracer.isTracingEnabled = true

                    withContext(Dispatchers.IO) {
                        val repo = AnalysisRepository()
                        repo.testApiConnectionWithJson(provider, apiKey, apiUrl, editableJson)
                    }

                    ApiTracer.isTracingEnabled = wasEnabled
                    val traces = ApiTracer.getTraceFiles()
                    val newTrace = if (ApiTracer.getTraceCount() > traceCountBefore) traces.firstOrNull()?.filename else null

                    if (newTrace != null) {
                        onNavigateToTraceDetail(newTrace)
                    } else {
                        Toast.makeText(context, "Request sent. Check traces.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally { isLoading = false }
            }
        }, enabled = !isLoading, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isLoading) "Sending..." else "Submit", maxLines = 1, softWrap = false) }
    }
}
