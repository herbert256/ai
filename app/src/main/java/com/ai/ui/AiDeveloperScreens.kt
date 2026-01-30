package com.ai.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import kotlinx.coroutines.launch

/**
 * Developer Options screen with developer tools like API Test and API Traces.
 * Only accessible in developer mode.
 */
@Composable
fun DeveloperOptionsScreen(
    onBackToHome: () -> Unit,
    onNavigateToApiTest: () -> Unit,
    onNavigateToTraces: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "Developer Options",
            onBackClick = onBackToHome,
            onAiClick = onBackToHome
        )

        // API Test card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToApiTest),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🧪",
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "API Test",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Test API calls with custom settings",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }

        // API Traces card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToTraces),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🐞",
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "API Traces",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        text = "View logged API requests and responses",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }
    }
}

/**
 * API Test screen for testing API calls with custom settings.
 * Only available in developer mode.
 */
@Composable
fun ApiTestScreen(
    onBackClick: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToEditRequest: () -> Unit,
    viewModel: AiViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val uiState by viewModel.uiState.collectAsState()
    val aiSettings = uiState.aiSettings

    // Load last test values from persistent storage
    var selectedProvider by remember {
        val lastProvider = prefs.getString("last_test_provider", null)
        val provider = lastProvider?.let {
            try { com.ai.data.AiService.valueOf(it) } catch (e: Exception) { null }
        } ?: com.ai.data.AiService.OPENAI
        mutableStateOf(provider)
    }
    var apiUrl by remember {
        mutableStateOf(prefs.getString("last_test_api_url", null) ?: aiSettings.getEffectiveEndpointUrl(selectedProvider))
    }
    var apiKey by remember {
        mutableStateOf(prefs.getString("last_test_api_key", null) ?: aiSettings.getApiKey(selectedProvider))
    }
    var model by remember {
        mutableStateOf(prefs.getString("last_test_model", null) ?: aiSettings.getModel(selectedProvider))
    }
    var prompt by remember {
        mutableStateOf(prefs.getString("last_test_prompt", null) ?: "Hello, how are you?")
    }
    var isLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(true) }
    var showParameters by remember { mutableStateOf(false) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }

    // API Parameters (all strings, empty = not set)
    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf("") }
    var topP by remember { mutableStateOf("") }
    var topK by remember { mutableStateOf("") }
    var frequencyPenalty by remember { mutableStateOf("") }
    var presencePenalty by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    var stopSequences by remember { mutableStateOf("") }
    var responseFormatJson by remember { mutableStateOf(false) }
    var searchEnabled by remember { mutableStateOf(false) }

    // Update URL, model, and API key when provider changes (only if user manually changes provider)
    LaunchedEffect(selectedProvider) {
        if (!isInitialized) {
            apiUrl = aiSettings.getEffectiveEndpointUrl(selectedProvider)
            model = aiSettings.getModel(selectedProvider)
            apiKey = aiSettings.getApiKey(selectedProvider)
        }
        isInitialized = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "API Test",
            onBackClick = onBackClick,
            onAiClick = onNavigateHome
        )

        // Provider dropdown
        Text("AI Provider", color = Color.Gray, fontSize = 14.sp)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedProvider.displayName)
                Spacer(modifier = Modifier.weight(1f))
                Text("▼")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                com.ai.data.AiService.entries.sortedBy { it.displayName.lowercase() }.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            selectedProvider = provider
                            expanded = false
                        }
                    )
                }
            }
        }

        // API endpoint field with Select Endpoint button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("API endpoint") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
            Button(
                onClick = { showEndpointDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Select", fontSize = 12.sp)
            }
        }

        // API Key field
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Model field with Select button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
            Button(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        isLoadingModels = true
                        scope.launch {
                            try {
                                val repository = com.ai.data.AiAnalysisRepository()
                                availableModels = when (selectedProvider) {
                                    com.ai.data.AiService.OPENAI -> repository.fetchChatGptModels(apiKey)
                                    com.ai.data.AiService.ANTHROPIC -> repository.fetchClaudeModels(apiKey)
                                    com.ai.data.AiService.GOOGLE -> repository.fetchGeminiModels(apiKey)
                                    com.ai.data.AiService.XAI -> repository.fetchGrokModels(apiKey)
                                    com.ai.data.AiService.GROQ -> repository.fetchGroqModels(apiKey)
                                    com.ai.data.AiService.DEEPSEEK -> repository.fetchDeepSeekModels(apiKey)
                                    com.ai.data.AiService.MISTRAL -> repository.fetchMistralModels(apiKey)
                                    com.ai.data.AiService.PERPLEXITY -> repository.fetchPerplexityModels(apiKey)
                                    com.ai.data.AiService.TOGETHER -> repository.fetchTogetherModels(apiKey)
                                    com.ai.data.AiService.OPENROUTER -> repository.fetchOpenRouterModels(apiKey)
                                    com.ai.data.AiService.SILICONFLOW -> repository.fetchSiliconFlowModels(apiKey)
                                    com.ai.data.AiService.ZAI -> repository.fetchZaiModels(apiKey)
                                    com.ai.data.AiService.MOONSHOT -> repository.fetchMoonshotModels(apiKey)
                                    com.ai.data.AiService.COHERE -> repository.fetchCohereModels(apiKey)
                                    com.ai.data.AiService.AI21 -> repository.fetchAi21Models(apiKey)
                                    com.ai.data.AiService.DASHSCOPE -> repository.fetchDashScopeModels(apiKey)
                                    com.ai.data.AiService.FIREWORKS -> repository.fetchFireworksModels(apiKey)
                                    com.ai.data.AiService.CEREBRAS -> repository.fetchCerebrasModels(apiKey)
                                    com.ai.data.AiService.SAMBANOVA -> repository.fetchSambaNovaModels(apiKey)
                                    com.ai.data.AiService.BAICHUAN -> repository.fetchBaichuanModels(apiKey)
                                    com.ai.data.AiService.STEPFUN -> repository.fetchStepFunModels(apiKey)
                                    com.ai.data.AiService.MINIMAX -> repository.fetchMiniMaxModels(apiKey)
                                    com.ai.data.AiService.DUMMY -> repository.fetchDummyModels(apiKey)
                                }
                                isLoadingModels = false
                                if (availableModels.isNotEmpty()) {
                                    showModelDialog = true
                                } else {
                                    android.widget.Toast.makeText(context, "No models found", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                isLoadingModels = false
                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        android.widget.Toast.makeText(context, "Enter API key first", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isLoadingModels,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (isLoadingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Select", fontSize = 12.sp)
                }
            }
        }

        // Prompt field
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Parameters section (expandable)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showParameters = !showParameters },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "API Parameters (optional)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = if (showParameters) "▲" else "▼",
                        color = Color(0xFF8B5CF6)
                    )
                }

                if (showParameters) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // System Prompt
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text("System Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Temperature and Max Tokens row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = { Text("Temperature") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("0.0-2.0", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                        OutlinedTextField(
                            value = maxTokens,
                            onValueChange = { maxTokens = it },
                            label = { Text("Max Tokens") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("e.g. 1024", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Top P and Top K row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = topP,
                            onValueChange = { topP = it },
                            label = { Text("Top P") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("0.0-1.0", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                        OutlinedTextField(
                            value = topK,
                            onValueChange = { topK = it },
                            label = { Text("Top K") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("e.g. 40", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Frequency and Presence Penalty row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = frequencyPenalty,
                            onValueChange = { frequencyPenalty = it },
                            label = { Text("Freq Penalty") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("-2.0 to 2.0", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                        OutlinedTextField(
                            value = presencePenalty,
                            onValueChange = { presencePenalty = it },
                            label = { Text("Pres Penalty") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("-2.0 to 2.0", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Seed
                    OutlinedTextField(
                        value = seed,
                        onValueChange = { seed = it },
                        label = { Text("Seed") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("For reproducibility", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Stop Sequences
                    OutlinedTextField(
                        value = stopSequences,
                        onValueChange = { stopSequences = it },
                        label = { Text("Stop Sequences") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Comma-separated", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Checkboxes row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { responseFormatJson = !responseFormatJson }
                        ) {
                            Checkbox(
                                checked = responseFormatJson,
                                onCheckedChange = { responseFormatJson = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF8B5CF6)
                                )
                            )
                            Text("JSON Mode", color = Color.White, fontSize = 14.sp)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { searchEnabled = !searchEnabled }
                        ) {
                            Checkbox(
                                checked = searchEnabled,
                                onCheckedChange = { searchEnabled = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF8B5CF6)
                                )
                            )
                            Text("Web Search", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Submit button - navigates to Edit API Request screen
        Button(
            onClick = {
                if (prompt.isNotBlank()) {
                    // Save all test configuration to persistent storage
                    prefs.edit()
                        .putString("last_test_provider", selectedProvider.name)
                        .putString("last_test_api_url", apiUrl)
                        .putString("last_test_api_key", apiKey)
                        .putString("last_test_model", model)
                        .putString("last_test_prompt", prompt)
                        .putString("last_test_system_prompt", systemPrompt)
                        .putString("last_test_temperature", temperature)
                        .putString("last_test_max_tokens", maxTokens)
                        .putString("last_test_top_p", topP)
                        .putString("last_test_top_k", topK)
                        .putString("last_test_frequency_penalty", frequencyPenalty)
                        .putString("last_test_presence_penalty", presencePenalty)
                        .putString("last_test_seed", seed)
                        .putString("last_test_stop_sequences", stopSequences)
                        .putBoolean("last_test_response_format_json", responseFormatJson)
                        .putBoolean("last_test_search_enabled", searchEnabled)
                        .apply()

                    // Navigate to Edit API Request screen
                    onNavigateToEditRequest()
                }
            },
            enabled = prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Submit")
        }
    }

    // Endpoint Selection Dialog
    if (showEndpointDialog) {
        val endpoints = aiSettings.getEndpointsForProvider(selectedProvider)

        AlertDialog(
            onDismissRequest = { showEndpointDialog = false },
            title = { Text("Select Endpoint", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Show endpoints only
                    endpoints.forEach { endpoint ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    apiUrl = endpoint.url
                                    showEndpointDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (apiUrl == endpoint.url) Color(0xFF6366F1).copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(endpoint.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    if (endpoint.isDefault) {
                                        Text(
                                            "DEFAULT",
                                            fontSize = 10.sp,
                                            color = Color(0xFF4CAF50),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(endpoint.url, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEndpointDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Model Selection Dialog
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Select Model (${availableModels.size})", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableModels.forEach { modelName ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    model = modelName
                                    showModelDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (model == modelName) Color(0xFF6366F1).copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = modelName,
                                modifier = Modifier.padding(12.dp),
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Edit API Request screen - allows editing the generated JSON request before sending.
 */
@Composable
fun EditApiRequestScreen(
    onBackClick: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    // Load test configuration from SharedPreferences
    val provider = remember {
        val providerName = prefs.getString("last_test_provider", null)
        providerName?.let {
            try { com.ai.data.AiService.valueOf(it) } catch (e: Exception) { null }
        } ?: com.ai.data.AiService.OPENAI
    }
    val apiUrl = remember { prefs.getString("last_test_api_url", "") ?: "" }
    val apiKey = remember { prefs.getString("last_test_api_key", "") ?: "" }
    val model = remember { prefs.getString("last_test_model", "") ?: "" }
    val prompt = remember { prefs.getString("last_test_prompt", "") ?: "" }
    val systemPrompt = remember { prefs.getString("last_test_system_prompt", "") ?: "" }
    val temperature = remember { prefs.getString("last_test_temperature", "")?.toFloatOrNull() }
    val maxTokens = remember { prefs.getString("last_test_max_tokens", "")?.toIntOrNull() }
    val topP = remember { prefs.getString("last_test_top_p", "")?.toFloatOrNull() }
    val topK = remember { prefs.getString("last_test_top_k", "")?.toIntOrNull() }
    val frequencyPenalty = remember { prefs.getString("last_test_frequency_penalty", "")?.toFloatOrNull() }
    val presencePenalty = remember { prefs.getString("last_test_presence_penalty", "")?.toFloatOrNull() }
    val seed = remember { prefs.getString("last_test_seed", "")?.toIntOrNull() }
    val stopSequences = remember {
        prefs.getString("last_test_stop_sequences", "")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }
    }
    val responseFormatJson = remember { prefs.getBoolean("last_test_response_format_json", false) }
    val searchEnabled = remember { prefs.getBoolean("last_test_search_enabled", false) }

    // Generate the JSON request body
    val generatedJson = remember {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val messages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to systemPrompt))
            }
            add(mapOf("role" to "user", "content" to prompt))
        }
        val requestMap = mutableMapOf<String, Any?>(
            "model" to model,
            "messages" to messages
        )
        temperature?.let { requestMap["temperature"] = it }
        maxTokens?.let { requestMap["max_tokens"] = it }
        topP?.let { requestMap["top_p"] = it }
        topK?.let { requestMap["top_k"] = it }
        frequencyPenalty?.let { requestMap["frequency_penalty"] = it }
        presencePenalty?.let { requestMap["presence_penalty"] = it }
        seed?.let { requestMap["seed"] = it }
        stopSequences?.let { requestMap["stop"] = it }
        if (responseFormatJson) {
            requestMap["response_format"] = mapOf("type" to "json_object")
        }
        if (searchEnabled) {
            requestMap["search"] = true
        }
        gson.toJson(requestMap)
    }

    var editableJson by remember { mutableStateOf(generatedJson) }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "Edit API Request",
            onBackClick = onBackClick,
            onAiClick = onNavigateHome
        )

        // Info about the request
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "${provider.displayName} • $model",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = apiUrl,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        // Large editable JSON text field
        OutlinedTextField(
            value = editableJson,
            onValueChange = { editableJson = it },
            label = { Text("Request Body (JSON)") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color.White
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Submit button
        Button(
            onClick = {
                isLoading = true
                scope.launch {
                    // Enable API tracing temporarily
                    val wasTracingEnabled = com.ai.data.ApiTracer.isTracingEnabled
                    com.ai.data.ApiTracer.isTracingEnabled = true

                    // Get trace files before the call
                    val traceDir = java.io.File(context.filesDir, "trace")
                    val traceFilesBefore = traceDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

                    try {
                        // Make the API call with the edited JSON
                        val repository = com.ai.data.AiAnalysisRepository()
                        val response = repository.testApiConnectionWithJson(
                            service = provider,
                            apiKey = apiKey,
                            baseUrl = apiUrl,
                            jsonBody = editableJson
                        )

                        // Find the new trace file
                        val traceFilesAfter = traceDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                        val newTraceFile = (traceFilesAfter - traceFilesBefore).firstOrNull()

                        // Restore tracing state
                        com.ai.data.ApiTracer.isTracingEnabled = wasTracingEnabled

                        isLoading = false

                        // Navigate to trace detail if we found a new trace file
                        if (newTraceFile != null) {
                            onNavigateToTraceDetail(newTraceFile)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                if (response.isSuccess) "Success but no trace file found" else "Error: ${response.error}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        com.ai.data.ApiTracer.isTracingEnabled = wasTracingEnabled
                        isLoading = false
                        android.widget.Toast.makeText(
                            context,
                            "Error: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            enabled = !isLoading && editableJson.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sending...")
            } else {
                Text("Submit")
            }
        }
    }
}
