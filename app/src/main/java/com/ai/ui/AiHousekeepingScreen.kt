package com.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import kotlinx.coroutines.launch

/**
 * AI Housekeeping screen with maintenance actions like refresh models, generate agents, import/export.
 */
@Composable
fun HousekeepingScreen(
    aiSettings: AiSettings,
    huggingFaceApiKey: String = "",
    openRouterApiKey: String = "",
    developerMode: Boolean = false,
    availableChatGptModels: List<String> = emptyList(),
    availableClaudeModels: List<String> = emptyList(),
    availableGeminiModels: List<String> = emptyList(),
    availableGrokModels: List<String> = emptyList(),
    availableGroqModels: List<String> = emptyList(),
    availableDeepSeekModels: List<String> = emptyList(),
    availableMistralModels: List<String> = emptyList(),
    availablePerplexityModels: List<String> = emptyList(),
    availableTogetherModels: List<String> = emptyList(),
    availableOpenRouterModels: List<String> = emptyList(),
    availableSiliconFlowModels: List<String> = emptyList(),
    availableZaiModels: List<String> = emptyList(),
    availableMoonshotModels: List<String> = emptyList(),
    availableCohereModels: List<String> = emptyList(),
    availableAi21Models: List<String> = emptyList(),
    availableDashScopeModels: List<String> = emptyList(),
    availableFireworksModels: List<String> = emptyList(),
    availableCerebrasModels: List<String> = emptyList(),
    availableSambaNovaModels: List<String> = emptyList(),
    availableBaichuanModels: List<String> = emptyList(),
    availableStepFunModels: List<String> = emptyList(),
    availableMiniMaxModels: List<String> = emptyList(),
    availableNvidiaModels: List<String> = emptyList(),
    availableReplicateModels: List<String> = emptyList(),
    availableHuggingFaceInferenceModels: List<String> = emptyList(),
    availableLambdaModels: List<String> = emptyList(),
    availableLeptonModels: List<String> = emptyList(),
    availableYiModels: List<String> = emptyList(),
    availableDoubaoModels: List<String> = emptyList(),
    availableRekaModels: List<String> = emptyList(),
    availableWriterModels: List<String> = emptyList(),
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onRefreshAllModels: suspend (AiSettings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onTestApiKey: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onProviderStateChange: (AiService, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for refresh model lists
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshResults by remember { mutableStateOf<Map<String, Int>?>(null) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var refreshProgressText by remember { mutableStateOf("") }

    // State for generate default agents
    var isGenerating by remember { mutableStateOf(false) }
    var generationResults by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var showGenerationResultsDialog by remember { mutableStateOf(false) }
    var generatingProgressText by remember { mutableStateOf("") }

    // State for refresh OpenRouter data (specs + pricing)
    var isRefreshingOpenRouter by remember { mutableStateOf(false) }
    var openRouterResult by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }  // (pricing, specs pricing, specs params)
    var showOpenRouterResultDialog by remember { mutableStateOf(false) }

    // State for refresh provider states
    var isRefreshingProviderStates by remember { mutableStateOf(false) }
    var providerStateResults by remember { mutableStateOf<Map<String, String>?>(null) }  // provider name -> "ok"/"error"/"not-used"
    var showProviderStateResultDialog by remember { mutableStateOf(false) }
    var providerStateProgressText by remember { mutableStateOf("") }

    // State for import model costs
    var importCostsResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }  // (imported, skipped)
    var showImportCostsResultDialog by remember { mutableStateOf(false) }

    // State for delete all confirmations
    var showDeleteAllAgentsConfirm by remember { mutableStateOf(false) }
    var showDeleteAllFlocksConfirm by remember { mutableStateOf(false) }

    // File picker launcher for importing model costs CSV
    val costsCsvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importModelCostsFromCsv(context, it)
            importCostsResult = result
            showImportCostsResultDialog = true
        }
    }

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                val importedSettings = result.aiSettings
                onSave(importedSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }
                result.openRouterApiKey?.let { key -> onSaveOpenRouterApiKey(key) }

                // Automatically refresh model lists and generate default agents after import
                if (importedSettings.hasAnyApiKey()) {
                    scope.launch {
                        // 1. Refresh model lists (force refresh after import)
                        isRefreshing = true
                        onRefreshAllModels(importedSettings, true, null)
                        isRefreshing = false

                        // 2. Generate default agents
                        isGenerating = true
                        val results = mutableListOf<Pair<String, Boolean>>()

                        val providersToTest = AiService.entries.filter { provider ->
                            val apiKey = importedSettings.getApiKey(provider)
                            apiKey.isNotBlank()
                        }

                        var updatedAgents = importedSettings.agents.toMutableList()

                        for (provider in providersToTest) {
                            val apiKey = importedSettings.getApiKey(provider)
                            val model = provider.defaultModel

                            val testResult = onTestApiKey(provider, apiKey, model)
                            val isWorking = testResult == null

                            if (isWorking) {
                                // Create/update agent with empty values - uses provider settings at runtime
                                val existingAgentIndex = updatedAgents.indexOfFirst {
                                    it.name == provider.displayName
                                }

                                if (existingAgentIndex >= 0) {
                                    val existingAgent = updatedAgents[existingAgentIndex]
                                    updatedAgents[existingAgentIndex] = existingAgent.copy(
                                        model = "",
                                        apiKey = "",
                                        provider = provider,
                                        endpointId = null
                                    )
                                } else {
                                    val newAgent = AiAgent(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = provider.displayName,
                                        provider = provider,
                                        model = "",
                                        apiKey = "",
                                        endpointId = null,
                                        parameters = AiAgentParameters()
                                    )
                                    updatedAgents.add(newAgent)
                                }
                            }

                            results.add(provider.displayName to isWorking)
                        }

                        val successCount = results.count { it.second }
                        if (successCount > 0) {
                            val defaultAgentIds = updatedAgents
                                .filter { agent ->
                                    AiService.entries.any { it.displayName == agent.name }
                                }
                                .map { it.id }

                            val updatedFlocks = importedSettings.flocks.toMutableList()
                            val existingFlockIndex = updatedFlocks.indexOfFirst {
                                it.name == "default agents"
                            }

                            if (existingFlockIndex >= 0) {
                                updatedFlocks[existingFlockIndex] = updatedFlocks[existingFlockIndex].copy(
                                    agentIds = defaultAgentIds
                                )
                            } else {
                                val newFlock = AiFlock(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = "default agents",
                                    agentIds = defaultAgentIds
                                )
                                updatedFlocks.add(newFlock)
                            }

                            onSave(importedSettings.copy(
                                agents = updatedAgents,
                                flocks = updatedFlocks
                            ))
                        }

                        generationResults = results
                        isGenerating = false
                        showGenerationResultsDialog = true
                    }
                }
            }
        }
    }

    // Results dialog for refresh model lists
    if (showResultsDialog && refreshResults != null) {
        // Only show providers that are set to MANUAL model source
        val manualProviders = buildList {
            if (aiSettings.claudeApiKey.isNotBlank() && aiSettings.claudeModelSource == ModelSource.MANUAL) {
                add("Anthropic" to aiSettings.claudeManualModels.size)
            }
            if (aiSettings.perplexityApiKey.isNotBlank() && aiSettings.perplexityModelSource == ModelSource.MANUAL) {
                add("Perplexity" to aiSettings.perplexityManualModels.size)
            }
            if (aiSettings.siliconFlowApiKey.isNotBlank() && aiSettings.siliconFlowModelSource == ModelSource.MANUAL) {
                add("SiliconFlow" to aiSettings.siliconFlowManualModels.size)
            }
            if (aiSettings.zaiApiKey.isNotBlank() && aiSettings.zaiModelSource == ModelSource.MANUAL) {
                add("Z.AI" to aiSettings.zaiManualModels.size)
            }
        }

        AlertDialog(
            onDismissRequest = { showResultsDialog = false },
            title = { Text("Refresh Results") },
            text = {
                Column {
                    Text("Models fetched from API:")
                    refreshResults!!.forEach { (provider, count) ->
                        Text("• $provider: $count models")
                    }
                    if (manualProviders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Manual model lists (no API):")
                        manualProviders.forEach { (provider, count) ->
                            Text("• $provider: $count models")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showResultsDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Results dialog for generate default agents
    if (showGenerationResultsDialog && generationResults != null) {
        AlertDialog(
            onDismissRequest = { showGenerationResultsDialog = false },
            title = { Text("Generate Default Agents") },
            text = {
                Column {
                    val successCount = generationResults!!.count { it.second }
                    val failCount = generationResults!!.count { !it.second }

                    if (successCount > 0) {
                        Text("✅ Created/updated $successCount agent(s)")
                        Text("Added to 'default agents' flock")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (failCount > 0) {
                        Text("❌ Failed: $failCount provider(s)")
                        generationResults!!.filter { !it.second }.forEach { (provider, _) ->
                            Text("• $provider")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGenerationResultsDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Provider state refresh result dialog
    if (showProviderStateResultDialog) {
        AlertDialog(
            onDismissRequest = { showProviderStateResultDialog = false },
            title = { Text("Provider State Refreshed") },
            text = {
                providerStateResults?.let { results ->
                    val ok = results.filter { it.value == "ok" }
                    val error = results.filter { it.value == "error" }
                    val notUsed = results.filter { it.value == "not-used" }
                    Column {
                        if (ok.isNotEmpty()) {
                            Text("✅ OK (${ok.size}):")
                            ok.forEach { (name, _) -> Text("  • $name") }
                        }
                        if (error.isNotEmpty()) {
                            if (ok.isNotEmpty()) Spacer(modifier = Modifier.height(4.dp))
                            Text("❌ Error (${error.size}):")
                            error.forEach { (name, _) -> Text("  • $name") }
                        }
                        if (notUsed.isNotEmpty()) {
                            if (ok.isNotEmpty() || error.isNotEmpty()) Spacer(modifier = Modifier.height(4.dp))
                            Text("Not configured (${notUsed.size})")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderStateResultDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // OpenRouter refresh result dialog
    if (showOpenRouterResultDialog) {
        AlertDialog(
            onDismissRequest = { showOpenRouterResultDialog = false },
            title = { Text("OpenRouter Data Refreshed") },
            text = {
                if (openRouterResult != null) {
                    Text("Successfully refreshed:\n• ${openRouterResult!!.first} model prices\n• ${openRouterResult!!.second} pricing entries\n• ${openRouterResult!!.third} parameter entries")
                } else {
                    Text("Failed to fetch OpenRouter data.\nPlease check your OpenRouter API key.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenRouterResultDialog = false }) {
                    Text("OK")
                }
            }
        )
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
            title = "AI Housekeeping",
            onBackClick = onBackToHome,
            onAiClick = onBackToHome
        )

        // Refresh card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Refresh",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                refreshProgressText = ""
                                refreshResults = onRefreshAllModels(aiSettings, true) { provider ->
                                    refreshProgressText = provider
                                }
                                refreshProgressText = ""
                                isRefreshing = false
                                showResultsDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRefreshing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Model lists", fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshingOpenRouter = true
                                var pricingCount = 0
                                var specsPricing = 0
                                var specsParams = 0

                                // Fetch and save pricing data
                                val pricing = com.ai.data.PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                                if (pricing.isNotEmpty()) {
                                    com.ai.data.PricingCache.saveOpenRouterPricing(context, pricing)
                                    pricingCount = pricing.size
                                }

                                // Fetch and save model specifications
                                val specsResult = com.ai.data.PricingCache.fetchAndSaveModelSpecifications(
                                    context,
                                    openRouterApiKey
                                )
                                if (specsResult != null) {
                                    specsPricing = specsResult.first
                                    specsParams = specsResult.second
                                }

                                openRouterResult = if (pricingCount > 0 || specsPricing > 0) {
                                    Triple(pricingCount, specsPricing, specsParams)
                                } else {
                                    null
                                }
                                isRefreshingOpenRouter = false
                                showOpenRouterResultDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRefreshingOpenRouter && openRouterApiKey.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        if (isRefreshingOpenRouter) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("OpenRouter data", fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshingProviderStates = true
                                providerStateProgressText = ""
                                val results = mutableMapOf<String, String>()
                                for (service in com.ai.data.AiService.values()) {
                                    providerStateProgressText = service.displayName
                                    val apiKey = aiSettings.getApiKey(service)
                                    if (apiKey.isBlank()) {
                                        onProviderStateChange(service, "not-used")
                                        results[service.displayName] = "not-used"
                                    } else {
                                        val model = aiSettings.getModel(service)
                                        val error = onTestApiKey(service, apiKey, model)
                                        val state = if (error == null) "ok" else "error"
                                        onProviderStateChange(service, state)
                                        results[service.displayName] = state
                                    }
                                }
                                providerStateProgressText = ""
                                providerStateResults = results
                                isRefreshingProviderStates = false
                                showProviderStateResultDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRefreshingProviderStates,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        if (isRefreshingProviderStates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Provider state", fontSize = 12.sp)
                        }
                    }
                }

                // Progress text for refresh actions
                val activeRefreshProgress = when {
                    isRefreshing && refreshProgressText.isNotEmpty() -> refreshProgressText
                    isRefreshingProviderStates && providerStateProgressText.isNotEmpty() -> providerStateProgressText
                    else -> null
                }
                if (activeRefreshProgress != null) {
                    Text(
                        text = activeRefreshProgress,
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        }

        // Generate card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Generate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Button(
                    onClick = {
                        scope.launch {
                            isGenerating = true
                            generatingProgressText = ""
                            val results = mutableListOf<Pair<String, Boolean>>()

                            val providersToTest = AiService.entries.filter { provider ->
                                val apiKey = aiSettings.getApiKey(provider)
                                apiKey.isNotBlank()
                            }

                            var updatedAgents = aiSettings.agents.toMutableList()

                            for (provider in providersToTest) {
                                generatingProgressText = provider.displayName
                                val apiKey = aiSettings.getApiKey(provider)
                                val model = provider.defaultModel

                                val testResult = onTestApiKey(provider, apiKey, model)
                                val isWorking = testResult == null

                                if (isWorking) {
                                    // Create/update agent with empty values - uses provider settings at runtime
                                    val existingAgentIndex = updatedAgents.indexOfFirst {
                                        it.name == provider.displayName
                                    }

                                    if (existingAgentIndex >= 0) {
                                        val existingAgent = updatedAgents[existingAgentIndex]
                                        updatedAgents[existingAgentIndex] = existingAgent.copy(
                                            model = "",
                                            apiKey = "",
                                            provider = provider,
                                            endpointId = null
                                        )
                                    } else {
                                        val newAgent = AiAgent(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = provider.displayName,
                                            provider = provider,
                                            model = "",
                                            apiKey = "",
                                            endpointId = null,
                                            parameters = AiAgentParameters()
                                        )
                                        updatedAgents.add(newAgent)
                                    }
                                }

                                results.add(provider.displayName to isWorking)
                            }

                            val successCount = results.count { it.second }
                            if (successCount > 0) {
                                val defaultAgentIds = updatedAgents
                                    .filter { agent ->
                                        AiService.entries.any { it.displayName == agent.name }
                                    }
                                    .map { it.id }

                                val updatedFlocks = aiSettings.flocks.toMutableList()
                                val existingFlockIndex = updatedFlocks.indexOfFirst {
                                    it.name == "default agents"
                                }

                                if (existingFlockIndex >= 0) {
                                    updatedFlocks[existingFlockIndex] = updatedFlocks[existingFlockIndex].copy(
                                        agentIds = defaultAgentIds
                                    )
                                } else {
                                    val newFlock = AiFlock(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = "default agents",
                                        agentIds = defaultAgentIds
                                    )
                                    updatedFlocks.add(newFlock)
                                }

                                onSave(aiSettings.copy(
                                    agents = updatedAgents,
                                    flocks = updatedFlocks
                                ))
                            }

                            generatingProgressText = ""
                            generationResults = results
                            isGenerating = false
                            showGenerationResultsDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (generatingProgressText.isNotEmpty()) generatingProgressText else "Testing API keys...",
                            fontSize = 12.sp
                        )
                    } else {
                        Text("Default agents", fontSize = 12.sp)
                    }
                }
            }
        }

        // Export card
        val hasApiKeyForExport = aiSettings.chatGptApiKey.isNotBlank() ||
                aiSettings.claudeApiKey.isNotBlank() ||
                aiSettings.geminiApiKey.isNotBlank() ||
                aiSettings.grokApiKey.isNotBlank() ||
                aiSettings.groqApiKey.isNotBlank() ||
                aiSettings.deepSeekApiKey.isNotBlank() ||
                aiSettings.mistralApiKey.isNotBlank() ||
                aiSettings.perplexityApiKey.isNotBlank() ||
                aiSettings.togetherApiKey.isNotBlank() ||
                aiSettings.openRouterApiKey.isNotBlank() ||
                aiSettings.siliconFlowApiKey.isNotBlank() ||
                aiSettings.zaiApiKey.isNotBlank()
        val visibleAgentsForExport = aiSettings.agents
        val canExportConfig = hasApiKeyForExport &&
                visibleAgentsForExport.isNotEmpty() &&
                aiSettings.flocks.isNotEmpty()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Export",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            exportAiConfigToFile(context, aiSettings, huggingFaceApiKey, openRouterApiKey)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = canExportConfig,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("AI configuration", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            exportModelCostsToCsv(
                                context = context,
                                aiSettings = aiSettings,
                                developerMode = developerMode,
                                availableChatGptModels = availableChatGptModels,
                                availableClaudeModels = availableClaudeModels,
                                availableGeminiModels = availableGeminiModels,
                                availableGrokModels = availableGrokModels,
                                availableGroqModels = availableGroqModels,
                                availableDeepSeekModels = availableDeepSeekModels,
                                availableMistralModels = availableMistralModels,
                                availablePerplexityModels = availablePerplexityModels,
                                availableTogetherModels = availableTogetherModels,
                                availableOpenRouterModels = availableOpenRouterModels,
                                availableSiliconFlowModels = availableSiliconFlowModels,
                                availableZaiModels = availableZaiModels,
                                availableMoonshotModels = availableMoonshotModels,
                                availableCohereModels = availableCohereModels,
                                availableAi21Models = availableAi21Models,
                                availableDashScopeModels = availableDashScopeModels,
                                availableFireworksModels = availableFireworksModels,
                                availableCerebrasModels = availableCerebrasModels,
                                availableSambaNovaModels = availableSambaNovaModels,
                                availableBaichuanModels = availableBaichuanModels,
                                availableStepFunModels = availableStepFunModels,
                                availableMiniMaxModels = availableMiniMaxModels,
                                availableNvidiaModels = availableNvidiaModels,
                                availableReplicateModels = availableReplicateModels,
                                availableHuggingFaceInferenceModels = availableHuggingFaceInferenceModels,
                                availableLambdaModels = availableLambdaModels,
                                availableLeptonModels = availableLeptonModels,
                                availableYiModels = availableYiModels,
                                availableDoubaoModels = availableDoubaoModels,
                                availableRekaModels = availableRekaModels,
                                availableWriterModels = availableWriterModels
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Model costs", fontSize = 12.sp)
                    }
                }
            }
        }

        // Import card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Import",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("AI configuration", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            costsCsvPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Model costs", fontSize = 12.sp)
                    }
                }
            }
        }

        // Import costs result dialog
        if (showImportCostsResultDialog && importCostsResult != null) {
            val (imported, skipped) = importCostsResult!!
            AlertDialog(
                onDismissRequest = { showImportCostsResultDialog = false },
                title = { Text("Import Model Costs", color = Color.White) },
                text = {
                    Text(
                        "Imported $imported model price overrides.\n" +
                        if (skipped > 0) "Skipped $skipped rows (empty or invalid)." else "",
                        color = Color(0xFFAAAAAA)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showImportCostsResultDialog = false }) {
                        Text("OK")
                    }
                },
                containerColor = Color(0xFF2A2A2A)
            )
        }

        // Delete card - agents and flocks
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Delete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showDeleteAllAgentsConfirm = true },
                        modifier = Modifier.weight(1f),
                        enabled = aiSettings.agents.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("All agents", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showDeleteAllFlocksConfirm = true },
                        modifier = Modifier.weight(1f),
                        enabled = aiSettings.flocks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("All flocks", fontSize = 12.sp)
                    }
                }
            }
        }

        // Delete all agents confirmation dialog
        if (showDeleteAllAgentsConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteAllAgentsConfirm = false },
                title = { Text("Delete All Agents", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete all ${aiSettings.agents.size} agents? This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            onSave(aiSettings.copy(agents = emptyList()))
                            showDeleteAllAgentsConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                    ) {
                        Text("Delete All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllAgentsConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete all flocks confirmation dialog
        if (showDeleteAllFlocksConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteAllFlocksConfirm = false },
                title = { Text("Delete All Flocks", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete all ${aiSettings.flocks.size} flocks? This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            onSave(aiSettings.copy(flocks = emptyList()))
                            showDeleteAllFlocksConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                    ) {
                        Text("Delete All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllFlocksConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Clean up card
        var showCleanupDaysDialog by remember { mutableStateOf<String?>(null) }
        var cleanupDaysInput by remember { mutableStateOf("30") }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Clean up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCleanupDaysDialog = "chats" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("Chats", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "reports" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("Reports", fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCleanupDaysDialog = "statistics" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("Statistics", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "traces" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("API Trace", fontSize = 12.sp)
                    }
                }
            }
        }

        // Cleanup days dialog
        if (showCleanupDaysDialog != null) {
            val cleanupType = showCleanupDaysDialog!!
            val title = when (cleanupType) {
                "chats" -> "Clean up Chats"
                "reports" -> "Clean up Reports"
                "statistics" -> "Clean up Statistics"
                "traces" -> "Clean up API Traces"
                else -> "Clean up"
            }
            AlertDialog(
                onDismissRequest = {
                    showCleanupDaysDialog = null
                    cleanupDaysInput = "30"
                },
                title = { Text(title, color = Color.White) },
                text = {
                    Column {
                        if (cleanupType == "statistics") {
                            Text(
                                "Statistics don't have timestamps. Enter 0 to clear all statistics.",
                                color = Color(0xFFAAAAAA),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            "Delete data older than how many days?",
                            color = Color(0xFFAAAAAA)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cleanupDaysInput,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() }) {
                                    cleanupDaysInput = newValue
                                }
                            },
                            label = { Text("Days to keep") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color(0xFF555555),
                                focusedLabelColor = Color(0xFFFF9800)
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val days = cleanupDaysInput.toIntOrNull() ?: 30
                            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
                            var deletedCount = 0

                            when (cleanupType) {
                                "chats" -> {
                                    val sessions = com.ai.data.ChatHistoryManager.getAllSessions()
                                    sessions.forEach { session ->
                                        if (session.updatedAt < cutoffTime) {
                                            if (com.ai.data.ChatHistoryManager.deleteSession(session.id)) {
                                                deletedCount++
                                            }
                                        }
                                    }
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount chat(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "reports" -> {
                                    val reports = com.ai.data.AiReportStorage.getAllReports(context)
                                    reports.forEach { report ->
                                        if (report.timestamp < cutoffTime) {
                                            com.ai.data.AiReportStorage.deleteReport(context, report.id)
                                            deletedCount++
                                        }
                                    }
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount report(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "statistics" -> {
                                    if (days == 0) {
                                        SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)).clearUsageStats()
                                        android.widget.Toast.makeText(context, "Cleared all statistics", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Enter 0 to clear statistics (no timestamp data)", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "traces" -> {
                                    deletedCount = com.ai.data.ApiTracer.deleteTracesOlderThan(cutoffTime)
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount trace(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }

                            showCleanupDaysDialog = null
                            cleanupDaysInput = "30"
                        }
                    ) {
                        Text("Delete", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCleanupDaysDialog = null
                            cleanupDaysInput = "30"
                        }
                    ) {
                        Text("Cancel", color = Color(0xFF6B9BFF))
                    }
                },
                containerColor = Color(0xFF2A2A2A)
            )
        }
    }
}

/**
 * Export model costs to CSV file.
 * Only includes models that can be used for agents (matching Model Search).
 * Prices are in USD ticks (one millionth of a dollar) per million tokens.
 */
private fun exportModelCostsToCsv(
    context: android.content.Context,
    aiSettings: AiSettings,
    developerMode: Boolean,
    availableChatGptModels: List<String>,
    availableClaudeModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    availableSiliconFlowModels: List<String>,
    availableZaiModels: List<String>,
    availableMoonshotModels: List<String>,
    availableCohereModels: List<String>,
    availableAi21Models: List<String>,
    availableDashScopeModels: List<String>,
    availableFireworksModels: List<String>,
    availableCerebrasModels: List<String>,
    availableSambaNovaModels: List<String>,
    availableBaichuanModels: List<String>,
    availableStepFunModels: List<String>,
    availableMiniMaxModels: List<String>,
    availableNvidiaModels: List<String>,
    availableReplicateModels: List<String>,
    availableHuggingFaceInferenceModels: List<String>,
    availableLambdaModels: List<String>,
    availableLeptonModels: List<String>,
    availableYiModels: List<String>,
    availableDoubaoModels: List<String>,
    availableRekaModels: List<String>,
    availableWriterModels: List<String>
) {
    val pricingCache = com.ai.data.PricingCache

    // Get all pricing sources
    val overridePricing = pricingCache.getAllManualPricing(context)
    val openRouterPricing = pricingCache.getOpenRouterPricing(context)
    val litellmPricing = pricingCache.getLiteLLMPricing(context)
    val fallbackPricing = pricingCache.getFallbackPricing()

    // Build model list the same way as Model Search - only models that can be used for agents
    data class ProviderModel(val provider: String, val model: String)
    val allModels = mutableListOf<ProviderModel>()

    // OpenAI models (API or fallback to manual)
    val chatGptModels = availableChatGptModels.ifEmpty { aiSettings.chatGptManualModels }
    chatGptModels.forEach { allModels.add(ProviderModel("OPENAI", it)) }

    // Anthropic models (API or fallback to manual)
    val claudeModels = availableClaudeModels.ifEmpty { aiSettings.claudeManualModels }
    claudeModels.forEach { allModels.add(ProviderModel("ANTHROPIC", it)) }

    // Google models
    availableGeminiModels.forEach { allModels.add(ProviderModel("GOOGLE", it)) }

    // xAI models
    availableGrokModels.forEach { allModels.add(ProviderModel("XAI", it)) }

    // Groq models
    availableGroqModels.forEach { allModels.add(ProviderModel("GROQ", it)) }

    // DeepSeek models
    availableDeepSeekModels.forEach { allModels.add(ProviderModel("DEEPSEEK", it)) }

    // Mistral models
    availableMistralModels.forEach { allModels.add(ProviderModel("MISTRAL", it)) }

    // Perplexity models (hardcoded - no API, use manual models)
    val perplexityModels = availablePerplexityModels.ifEmpty { aiSettings.perplexityManualModels }
    perplexityModels.forEach { allModels.add(ProviderModel("PERPLEXITY", it)) }

    // Together models
    availableTogetherModels.forEach { allModels.add(ProviderModel("TOGETHER", it)) }

    // OpenRouter models
    availableOpenRouterModels.forEach { allModels.add(ProviderModel("OPENROUTER", it)) }

    // SiliconFlow models (API or fallback to manual)
    val siliconFlowModels = availableSiliconFlowModels.ifEmpty { aiSettings.siliconFlowManualModels }
    siliconFlowModels.forEach { allModels.add(ProviderModel("SILICONFLOW", it)) }

    // Z.AI models (API or fallback to manual)
    val zaiModels = availableZaiModels.ifEmpty { aiSettings.zaiManualModels }
    zaiModels.forEach { allModels.add(ProviderModel("ZAI", it)) }

    // Moonshot models (API or fallback to manual)
    val moonshotModels = availableMoonshotModels.ifEmpty { aiSettings.moonshotManualModels }
    moonshotModels.forEach { allModels.add(ProviderModel("MOONSHOT", it)) }

    // Cohere models (API or fallback to manual)
    val cohereModelsC = availableCohereModels.ifEmpty { aiSettings.cohereManualModels }
    cohereModelsC.forEach { allModels.add(ProviderModel("COHERE", it)) }

    // AI21 models (API or fallback to manual)
    val ai21ModelsC = availableAi21Models.ifEmpty { aiSettings.ai21ManualModels }
    ai21ModelsC.forEach { allModels.add(ProviderModel("AI21", it)) }

    // DashScope models (API or fallback to manual)
    val dashScopeModelsC = availableDashScopeModels.ifEmpty { aiSettings.dashScopeManualModels }
    dashScopeModelsC.forEach { allModels.add(ProviderModel("DASHSCOPE", it)) }

    // Fireworks models (API or fallback to manual)
    val fireworksModelsC = availableFireworksModels.ifEmpty { aiSettings.fireworksManualModels }
    fireworksModelsC.forEach { allModels.add(ProviderModel("FIREWORKS", it)) }

    // Cerebras models (API or fallback to manual)
    val cerebrasModelsC = availableCerebrasModels.ifEmpty { aiSettings.cerebrasManualModels }
    cerebrasModelsC.forEach { allModels.add(ProviderModel("CEREBRAS", it)) }

    // SambaNova models (API or fallback to manual)
    val sambaNovaModelsC = availableSambaNovaModels.ifEmpty { aiSettings.sambaNovaManualModels }
    sambaNovaModelsC.forEach { allModels.add(ProviderModel("SAMBANOVA", it)) }

    // Baichuan models (API or fallback to manual)
    val baichuanModelsC = availableBaichuanModels.ifEmpty { aiSettings.baichuanManualModels }
    baichuanModelsC.forEach { allModels.add(ProviderModel("BAICHUAN", it)) }

    // StepFun models (API or fallback to manual)
    val stepFunModelsC = availableStepFunModels.ifEmpty { aiSettings.stepFunManualModels }
    stepFunModelsC.forEach { allModels.add(ProviderModel("STEPFUN", it)) }

    // MiniMax models (API or fallback to manual)
    val miniMaxModelsC = availableMiniMaxModels.ifEmpty { aiSettings.miniMaxManualModels }
    miniMaxModelsC.forEach { allModels.add(ProviderModel("MINIMAX", it)) }

    // NVIDIA models (API or fallback to manual)
    val nvidiaModelsC = availableNvidiaModels.ifEmpty { aiSettings.nvidiaManualModels }
    nvidiaModelsC.forEach { allModels.add(ProviderModel("NVIDIA", it)) }

    // Replicate models (API or fallback to manual)
    val replicateModelsC = availableReplicateModels.ifEmpty { aiSettings.replicateManualModels }
    replicateModelsC.forEach { allModels.add(ProviderModel("REPLICATE", it)) }

    // Hugging Face models (API or fallback to manual)
    val huggingFaceModelsC = availableHuggingFaceInferenceModels.ifEmpty { aiSettings.huggingFaceInferenceManualModels }
    huggingFaceModelsC.forEach { allModels.add(ProviderModel("HUGGINGFACE", it)) }

    // Lambda models (API or fallback to manual)
    val lambdaModelsC = availableLambdaModels.ifEmpty { aiSettings.lambdaManualModels }
    lambdaModelsC.forEach { allModels.add(ProviderModel("LAMBDA", it)) }

    // Lepton models (API or fallback to manual)
    val leptonModelsC = availableLeptonModels.ifEmpty { aiSettings.leptonManualModels }
    leptonModelsC.forEach { allModels.add(ProviderModel("LEPTON", it)) }

    // 01.AI (Yi) models (API or fallback to manual)
    val yiModelsC = availableYiModels.ifEmpty { aiSettings.yiManualModels }
    yiModelsC.forEach { allModels.add(ProviderModel("YI", it)) }

    // Doubao models (API or fallback to manual)
    val doubaoModelsC = availableDoubaoModels.ifEmpty { aiSettings.doubaoManualModels }
    doubaoModelsC.forEach { allModels.add(ProviderModel("DOUBAO", it)) }

    // Reka models (API or fallback to manual)
    val rekaModelsC = availableRekaModels.ifEmpty { aiSettings.rekaManualModels }
    rekaModelsC.forEach { allModels.add(ProviderModel("REKA", it)) }

    // Writer models (API or fallback to manual)
    val writerModelsC = availableWriterModels.ifEmpty { aiSettings.writerManualModels }
    writerModelsC.forEach { allModels.add(ProviderModel("WRITER", it)) }

    // Sort by provider then model
    val sortedModels = allModels.sortedWith(compareBy({ it.provider }, { it.model }))

    // Helper to convert price per token to USD per million tokens
    // Price per token * 1,000,000 (for million tokens) = dollars per million
    fun toDollarsPerMillion(pricePerToken: Double?): String {
        if (pricePerToken == null) return ""
        val dollars = pricePerToken * 1e6
        return String.format(java.util.Locale.US, "%.2f", dollars)
    }

    // Build CSV - prices in USD per million tokens
    val csv = StringBuilder()
    csv.appendLine("Provider,Model,Override $/M In,Override $/M Out,OpenRouter $/M In,OpenRouter $/M Out,LiteLLM $/M In,LiteLLM $/M Out,Fallback $/M In,Fallback $/M Out")

    for (pm in sortedModels) {
        val overrideKey = "${pm.provider}:${pm.model}"
        val override = overridePricing[overrideKey]

        // For OpenRouter, try both the full key and provider/model format
        val openRouterKey = if (pm.provider == "OPENROUTER") pm.model else findOpenRouterKey(pm.provider, pm.model, openRouterPricing)
        val openRouter = openRouterPricing[openRouterKey]

        // For LiteLLM, try direct and with prefix
        val litellmKey = findLiteLLMKey(pm.provider, pm.model, litellmPricing)
        val litellm = litellmPricing[litellmKey]

        val fallback = fallbackPricing[pm.model]

        csv.appendLine(buildString {
            append(escapeCsvField(pm.provider))
            append(",")
            append(escapeCsvField(pm.model))
            append(",")
            append(toDollarsPerMillion(override?.promptPrice))
            append(",")
            append(toDollarsPerMillion(override?.completionPrice))
            append(",")
            append(toDollarsPerMillion(openRouter?.promptPrice))
            append(",")
            append(toDollarsPerMillion(openRouter?.completionPrice))
            append(",")
            append(toDollarsPerMillion(litellm?.promptPrice))
            append(",")
            append(toDollarsPerMillion(litellm?.completionPrice))
            append(",")
            append(toDollarsPerMillion(fallback?.promptPrice))
            append(",")
            append(toDollarsPerMillion(fallback?.completionPrice))
        })
    }

    // Save and share CSV file
    try {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "model_costs_$timestamp.csv"
        val exportDir = java.io.File(context.cacheDir ?: return, "exports")
        exportDir.mkdirs()
        val file = java.io.File(exportDir, fileName)
        file.writeText(csv.toString())

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, "Export Model Costs"))
    } catch (e: Exception) {
        android.util.Log.e("HousekeepingScreen", "Failed to export model costs: ${e.message}")
        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * Import model costs from CSV file.
 * Reads the Override $/M In and Override $/M Out columns and sets them as manual pricing overrides.
 * CSV format: Provider,Model,Override $/M In,Override $/M Out,...
 * Returns (imported count, skipped count).
 */
private fun importModelCostsFromCsv(context: android.content.Context, uri: android.net.Uri): Pair<Int, Int> {
    var imported = 0
    var skipped = 0

    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val reader = inputStream?.bufferedReader() ?: return Pair(0, 0)

        val lines = reader.readLines()
        reader.close()

        if (lines.isEmpty()) return Pair(0, 0)

        // Skip header row
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                skipped++
                continue
            }

            // Parse CSV line (handling quoted fields)
            val fields = parseCsvLine(line)
            if (fields.size < 4) {
                skipped++
                continue
            }

            val providerName = fields[0].trim()
            val model = fields[1].trim()
            val overrideInputStr = fields[2].trim()
            val overrideOutputStr = fields[3].trim()

            // Skip if override columns are empty
            if (overrideInputStr.isBlank() || overrideOutputStr.isBlank()) {
                skipped++
                continue
            }

            // Parse prices (in dollars per million tokens)
            val overrideInputDollarsPerM = overrideInputStr.toDoubleOrNull()
            val overrideOutputDollarsPerM = overrideOutputStr.toDoubleOrNull()

            if (overrideInputDollarsPerM == null || overrideOutputDollarsPerM == null) {
                skipped++
                continue
            }

            // Convert from dollars per million to price per token
            val promptPricePerToken = overrideInputDollarsPerM / 1_000_000.0
            val completionPricePerToken = overrideOutputDollarsPerM / 1_000_000.0

            // Map provider name to AiService
            val provider = try {
                com.ai.data.AiService.valueOf(providerName)
            } catch (e: IllegalArgumentException) {
                skipped++
                continue
            }

            // Set manual pricing
            com.ai.data.PricingCache.setManualPricing(
                context = context,
                provider = provider,
                model = model,
                promptPrice = promptPricePerToken,
                completionPrice = completionPricePerToken
            )
            imported++
        }

        android.util.Log.d("HousekeepingScreen", "Imported $imported model cost overrides, skipped $skipped")
    } catch (e: Exception) {
        android.util.Log.e("HousekeepingScreen", "Failed to import model costs: ${e.message}")
        android.widget.Toast.makeText(context, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }

    return Pair(imported, skipped)
}

/**
 * Parse a CSV line handling quoted fields.
 */
private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false

    for (char in line) {
        when {
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                fields.add(current.toString())
                current = StringBuilder()
            }
            else -> current.append(char)
        }
    }
    fields.add(current.toString())

    return fields
}

private fun mapOpenRouterPrefixToProvider(prefix: String): String? {
    return when (prefix) {
        "openai" -> "OPENAI"
        "anthropic" -> "ANTHROPIC"
        "google" -> "GOOGLE"
        "x-ai" -> "XAI"
        "deepseek" -> "DEEPSEEK"
        "mistralai" -> "MISTRAL"
        "perplexity" -> "PERPLEXITY"
        "meta-llama", "meta" -> "TOGETHER"
        else -> null
    }
}

private fun mapLiteLLMPrefixToProvider(prefix: String): String? {
    return when (prefix) {
        "openai" -> "OPENAI"
        "anthropic", "claude" -> "ANTHROPIC"
        "gemini" -> "GOOGLE"
        "xai" -> "XAI"
        "groq" -> "GROQ"
        "deepseek" -> "DEEPSEEK"
        "mistral" -> "MISTRAL"
        "perplexity" -> "PERPLEXITY"
        "together_ai", "together" -> "TOGETHER"
        else -> null
    }
}

private fun findOpenRouterKey(provider: String, model: String, pricing: Map<String, com.ai.data.PricingCache.ModelPricing>): String? {
    // Try direct match first
    if (pricing.containsKey(model)) return model

    // Try with provider prefix
    val prefix = when (provider) {
        "OPENAI" -> "openai"
        "ANTHROPIC" -> "anthropic"
        "GOOGLE" -> "google"
        "XAI" -> "x-ai"
        "DEEPSEEK" -> "deepseek"
        "MISTRAL" -> "mistralai"
        "PERPLEXITY" -> "perplexity"
        else -> null
    }
    if (prefix != null) {
        val key = "$prefix/$model"
        if (pricing.containsKey(key)) return key
    }

    // Try to find a partial match
    return pricing.keys.find { it.endsWith("/$model") }
}

private fun findLiteLLMKey(provider: String, model: String, pricing: Map<String, com.ai.data.PricingCache.ModelPricing>): String? {
    // Try direct match first
    if (pricing.containsKey(model)) return model

    // Try with provider prefix
    val prefix = when (provider) {
        "GOOGLE" -> "gemini"
        "XAI" -> "xai"
        "GROQ" -> "groq"
        "DEEPSEEK" -> "deepseek"
        "TOGETHER" -> "together_ai"
        else -> null
    }
    if (prefix != null) {
        val key = "$prefix/$model"
        if (pricing.containsKey(key)) return key
    }

    return null
}

private fun escapeCsvField(value: String): String {
    return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
}
