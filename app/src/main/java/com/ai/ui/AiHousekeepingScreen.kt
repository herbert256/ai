package com.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * AI Housekeeping screen with maintenance actions like refresh models, generate agents, import/export.
 */
@Composable
fun HousekeepingScreen(
    aiSettings: AiSettings,
    huggingFaceApiKey: String = "",
    openRouterApiKey: String = "",
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

    // Unified progress dialog
    var progressTitle by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    val isAnyRunning by remember { derivedStateOf { progressTitle.isNotEmpty() } }

    // State for refresh all
    var isRefreshingAll by remember { mutableStateOf(false) }

    // State for refresh model lists
    var refreshResults by remember { mutableStateOf<Map<String, Int>?>(null) }
    var showResultsDialog by remember { mutableStateOf(false) }

    // State for generate default agents
    var generationResults by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var showGenerationResultsDialog by remember { mutableStateOf(false) }

    // State for refresh OpenRouter data (specs + pricing)
    var openRouterResult by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }  // (pricing, specs pricing, specs params)
    var showOpenRouterResultDialog by remember { mutableStateOf(false) }

    // State for refresh provider states
    var providerStateResults by remember { mutableStateOf<Map<String, String>?>(null) }  // provider name -> "ok"/"error"/"not-used"
    var showProviderStateResultDialog by remember { mutableStateOf(false) }

    // State for import model costs
    var importCostsResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }  // (imported, skipped)
    var showImportCostsResultDialog by remember { mutableStateOf(false) }

    // State for Start clean
    var showStartCleanConfirm by remember { mutableStateOf(false) }
    var taskError by remember { mutableStateOf<String?>(null) }

    fun updateProgress(text: String) {
        scope.launch {
            progressText = text
        }
    }

    fun launchProgressTask(
        title: String,
        initialText: String = "",
        block: suspend () -> Unit
    ) {
        scope.launch {
            progressTitle = title
            progressText = initialText
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("HousekeepingScreen", "$title failed: ${e.message}", e)
                taskError = e.message ?: "$title failed"
            } finally {
                isRefreshingAll = false
                progressTitle = ""
                progressText = ""
            }
        }
    }

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

    // File picker launcher for importing API keys
    val apiKeysPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importApiKeysFromFile(context, it, aiSettings)
            if (result != null) {
                val (updatedSettings, hfKey, orKey) = result
                onSave(updatedSettings)
                hfKey?.let { key -> onSaveHuggingFaceApiKey(key) }
                orKey?.let { key -> onSaveOpenRouterApiKey(key) }
            }
        }
    }

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                onSave(result.aiSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }
                result.openRouterApiKey?.let { key -> onSaveOpenRouterApiKey(key) }
            }
        }
    }

    // Progress dialog - shown when any operation is running
    if (isAnyRunning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(progressTitle, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AiColors.Green,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(progressText.ifEmpty { "Working..." }, color = AiColors.TextSecondary)
                }
            },
            confirmButton = { },
            containerColor = AiColors.SurfaceDark
        )
    }

    taskError?.let { message ->
        AlertDialog(
            onDismissRequest = { taskError = null },
            title = { Text("Operation failed", fontWeight = FontWeight.Bold) },
            text = { Text(message, color = AiColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { taskError = null }) {
                    Text("OK")
                }
            },
            containerColor = AiColors.SurfaceDark,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Results dialog for refresh model lists
    if (showResultsDialog && refreshResults != null) {
        // Only show providers that are set to MANUAL model source
        val manualProviders = buildList {
            for (service in com.ai.data.AiService.entries) {
                if (aiSettings.getApiKey(service).isNotBlank() && aiSettings.getModelSource(service) == ModelSource.MANUAL) {
                    add(service.displayName to aiSettings.getModels(service).size)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showResultsDialog = false },
            title = { Text("Refresh Results") },
            text = {
                Column {
                    Text("Models fetched from API:")
                    refreshResults.orEmpty().forEach { (provider, count) ->
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
                    val results = generationResults.orEmpty()
                    val successCount = results.count { it.second }
                    val failCount = results.count { !it.second }

                    if (successCount > 0) {
                        Text("✅ Created/updated $successCount agent(s)")
                        Text("Added to 'default agents' flock")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (failCount > 0) {
                        Text("❌ Failed: $failCount provider(s)")
                        results.filter { !it.second }.forEach { (provider, _) ->
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
                val orResult = openRouterResult
                if (orResult != null) {
                    Text("Successfully refreshed:\n• ${orResult.first} model prices\n• ${orResult.second} pricing entries\n• ${orResult.third} parameter entries")
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
            colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Refresh",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            launchProgressTask("Refresh All", "Provider state + model lists...") {
                                isRefreshingAll = true
                                supervisorScope {
                                    // Run provider state, model lists, and OpenRouter in parallel
                                    val stateJob = async {
                                        val stateResults = java.util.concurrent.ConcurrentHashMap<String, String>()
                                        val toTest = mutableListOf<AiService>()
                                        for (service in com.ai.data.AiService.entries) {
                                            if (aiSettings.getProviderState(service) == "inactive") {
                                                stateResults[service.displayName] = "inactive"
                                            } else if (aiSettings.getApiKey(service).isBlank()) {
                                                onProviderStateChange(service, "not-used")
                                                stateResults[service.displayName] = "not-used"
                                            } else {
                                                toTest.add(service)
                                            }
                                        }
                                        toTest.map { service ->
                                            async {
                                                val error = onTestApiKey(service, aiSettings.getApiKey(service), aiSettings.getModel(service))
                                                val state = if (error == null) "ok" else "error"
                                                onProviderStateChange(service, state)
                                                stateResults[service.displayName] = state
                                            }
                                        }.awaitAll()
                                        stateResults.toMap()
                                    }

                                    val modelsJob = async {
                                        onRefreshAllModels(aiSettings, true) { provider ->
                                            updateProgress("Models: $provider")
                                        }
                                    }
    
                                    val orJob = if (openRouterApiKey.isNotBlank()) async {
                                        var pricingCount = 0
                                        var specsPricing = 0
                                        var specsParams = 0
                                        val pricing = com.ai.data.PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                                        if (pricing.isNotEmpty()) {
                                            com.ai.data.PricingCache.saveOpenRouterPricing(context, pricing)
                                            pricingCount = pricing.size
                                        }
                                        val specsResult = com.ai.data.PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
                                        if (specsResult != null) {
                                            specsPricing = specsResult.first
                                            specsParams = specsResult.second
                                        }
                                        if (pricingCount > 0 || specsPricing > 0) Triple(pricingCount, specsPricing, specsParams) else null
                                    } else null

                                    providerStateResults = stateJob.await()
                                    refreshResults = modelsJob.await()
                                    openRouterResult = orJob?.await()

                                    // Default agents (depends on provider state results)
                                    progressText = "Default agents..."
                                    val providersToTest = AiService.entries.filter { aiSettings.getApiKey(it).isNotBlank() }
                                    val genResults = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
                                    val updatedAgents = java.util.concurrent.CopyOnWriteArrayList(aiSettings.agents)
                                    providersToTest.map { provider ->
                                        async {
                                            val testResult = onTestApiKey(provider, aiSettings.getApiKey(provider), provider.defaultModel)
                                            val isWorking = testResult == null
                                            if (isWorking) {
                                                synchronized(updatedAgents) {
                                                    val idx = updatedAgents.indexOfFirst { it.name == provider.displayName }
                                                    if (idx >= 0) {
                                                        updatedAgents[idx] = updatedAgents[idx].copy(model = "", apiKey = "", provider = provider, endpointId = null)
                                                    } else {
                                                        updatedAgents.add(AiAgent(id = java.util.UUID.randomUUID().toString(), name = provider.displayName, provider = provider, model = "", apiKey = "", endpointId = null))
                                                    }
                                                }
                                            }
                                            genResults.add(provider.displayName to isWorking)
                                        }
                                    }.awaitAll()
                                    if (genResults.count { it.second } > 0) {
                                        val defaultAgentIds = updatedAgents.filter { agent -> AiService.entries.any { it.displayName == agent.name } }.map { it.id }
                                        val updatedFlocks = aiSettings.flocks.toMutableList()
                                        val flockIdx = updatedFlocks.indexOfFirst { it.name == "default agents" }
                                        if (flockIdx >= 0) {
                                            updatedFlocks[flockIdx] = updatedFlocks[flockIdx].copy(agentIds = defaultAgentIds)
                                        } else {
                                            updatedFlocks.add(AiFlock(id = java.util.UUID.randomUUID().toString(), name = "default agents", agentIds = defaultAgentIds))
                                        }
                                        onSave(aiSettings.copy(agents = updatedAgents.toList(), flocks = updatedFlocks))
                                    }
                                    generationResults = genResults.toList()
                                }
                                android.widget.Toast.makeText(context, "All refreshed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isAnyRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("All", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            launchProgressTask("Refresh Provider State", "Testing providers...") {
                                val results = java.util.concurrent.ConcurrentHashMap<String, String>()
                                // Separate immediate results from async tests
                                val toTest = mutableListOf<AiService>()
                                for (service in com.ai.data.AiService.entries) {
                                    if (aiSettings.getProviderState(service) == "inactive") {
                                        results[service.displayName] = "inactive"
                                    } else if (aiSettings.getApiKey(service).isBlank()) {
                                        onProviderStateChange(service, "not-used")
                                        results[service.displayName] = "not-used"
                                    } else {
                                        toTest.add(service)
                                    }
                                }
                                // Test all providers with API keys in parallel
                                supervisorScope {
                                    toTest.map { service ->
                                        async {
                                            val error = onTestApiKey(service, aiSettings.getApiKey(service), aiSettings.getModel(service))
                                            val state = if (error == null) "ok" else "error"
                                            onProviderStateChange(service, state)
                                            results[service.displayName] = state
                                        }
                                    }.awaitAll()
                                }
                                providerStateResults = results.toMap()
                                showProviderStateResultDialog = true
                            }
                        },
                        enabled = !isAnyRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Provider state", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            launchProgressTask("Refresh Model Lists", "Fetching...") {
                                refreshResults = onRefreshAllModels(aiSettings, true) { provider ->
                                    updateProgress(provider)
                                }
                                showResultsDialog = true
                            }
                        },
                        enabled = !isAnyRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Model lists", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            launchProgressTask("Refresh OpenRouter", "Fetching pricing...") {
                                var pricingCount = 0
                                var specsPricing = 0
                                var specsParams = 0
                                val pricing = com.ai.data.PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                                if (pricing.isNotEmpty()) {
                                    com.ai.data.PricingCache.saveOpenRouterPricing(context, pricing)
                                    pricingCount = pricing.size
                                }
                                updateProgress("Fetching specifications...")
                                val specsResult = com.ai.data.PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
                                if (specsResult != null) {
                                    specsPricing = specsResult.first
                                    specsParams = specsResult.second
                                }
                                openRouterResult = if (pricingCount > 0 || specsPricing > 0) Triple(pricingCount, specsPricing, specsParams) else null
                                showOpenRouterResultDialog = true
                            }
                        },
                        enabled = !isAnyRunning && openRouterApiKey.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("OpenRouter data", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            launchProgressTask("Generate Default Agents") {
                                val results = mutableListOf<Pair<String, Boolean>>()
                                val providersToTest = AiService.entries.filter { aiSettings.getApiKey(it).isNotBlank() }
                                var updatedAgents = aiSettings.agents.toMutableList()

                                for (provider in providersToTest) {
                                    progressText = provider.displayName
                                    val testResult = onTestApiKey(provider, aiSettings.getApiKey(provider), provider.defaultModel)
                                    val isWorking = testResult == null
                                    if (isWorking) {
                                        val idx = updatedAgents.indexOfFirst { it.name == provider.displayName }
                                        if (idx >= 0) {
                                            updatedAgents[idx] = updatedAgents[idx].copy(model = "", apiKey = "", provider = provider, endpointId = null)
                                        } else {
                                            updatedAgents.add(AiAgent(id = java.util.UUID.randomUUID().toString(), name = provider.displayName, provider = provider, model = "", apiKey = "", endpointId = null))
                                        }
                                    }
                                    results.add(provider.displayName to isWorking)
                                }
                                if (results.count { it.second } > 0) {
                                    val defaultAgentIds = updatedAgents.filter { agent -> AiService.entries.any { it.displayName == agent.name } }.map { it.id }
                                    val updatedFlocks = aiSettings.flocks.toMutableList()
                                    val flockIdx = updatedFlocks.indexOfFirst { it.name == "default agents" }
                                    if (flockIdx >= 0) {
                                        updatedFlocks[flockIdx] = updatedFlocks[flockIdx].copy(agentIds = defaultAgentIds)
                                    } else {
                                        updatedFlocks.add(AiFlock(id = java.util.UUID.randomUUID().toString(), name = "default agents", agentIds = defaultAgentIds))
                                    }
                                    onSave(aiSettings.copy(agents = updatedAgents, flocks = updatedFlocks))
                                }
                                generationResults = results
                                showGenerationResultsDialog = true
                            }
                        },
                        enabled = !isAnyRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Default agents", fontSize = 12.sp)
                    }
                }

            }
        }

        // Export card
        val hasApiKeyForExport = aiSettings.hasAnyApiKey()
        val visibleAgentsForExport = aiSettings.agents
        val canExportConfig = hasApiKeyForExport &&
                visibleAgentsForExport.isNotEmpty() &&
                aiSettings.flocks.isNotEmpty()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Export",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            exportAiConfigToFile(context, aiSettings, huggingFaceApiKey, openRouterApiKey)
                        },
                        enabled = canExportConfig,
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("AI configuration", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            exportApiKeysToFile(context, aiSettings, huggingFaceApiKey, openRouterApiKey)
                        },
                        enabled = hasApiKeyForExport,
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("API keys", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            exportModelCostsToCsv(
                                context = context,
                                aiSettings = aiSettings
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Model costs", fontSize = 12.sp)
                    }
                }
            }
        }

        // Import card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Import",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("AI configuration", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            apiKeysPickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("API keys", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            costsCsvPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.Green),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Model costs", fontSize = 12.sp)
                    }
                }
            }
        }

        // Import costs result dialog
        val costsResult = importCostsResult
        if (showImportCostsResultDialog && costsResult != null) {
            val (imported, skipped) = costsResult
            AlertDialog(
                onDismissRequest = { showImportCostsResultDialog = false },
                title = { Text("Import Model Costs", color = Color.White) },
                text = {
                    Text(
                        "Imported $imported model price overrides.\n" +
                        if (skipped > 0) "Skipped $skipped rows (empty or invalid)." else "",
                        color = AiColors.TextSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showImportCostsResultDialog = false }) {
                        Text("OK")
                    }
                },
                containerColor = AiColors.SurfaceDark
            )
        }

        // Clean up card
        var showCleanupDaysDialog by remember { mutableStateOf<String?>(null) }
        var cleanupDaysInput by remember { mutableStateOf("30") }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AiColors.SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Clean up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showStartCleanConfirm = true },
                        enabled = !isAnyRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("All", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "chats" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Chats", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "reports" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Reports", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "statistics" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Statistics", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "prompts" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Prompts", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "traces" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("API Trace", fontSize = 12.sp)
                    }
                }
            }
        }

        // Start clean confirmation dialog
        if (showStartCleanConfirm) {
            AlertDialog(
                onDismissRequest = { showStartCleanConfirm = false },
                title = { Text("Clean Up All", fontWeight = FontWeight.Bold) },
                text = {
                    Text("This will delete all chats, reports, statistics, prompt history, and API traces.\n\nThis cannot be undone.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showStartCleanConfirm = false
                            launchProgressTask("Clean Up") {
                                performStartClean(
                                    context = context,
                                    onProgress = ::updateProgress
                                )
                                android.widget.Toast.makeText(context, "Clean up completed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AiColors.RedDark)
                    ) {
                        Text("All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStartCleanConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Cleanup days dialog
        val cleanupType = showCleanupDaysDialog
        if (cleanupType != null) {
            val title = when (cleanupType) {
                "chats" -> "Clean up Chats"
                "reports" -> "Clean up Reports"
                "statistics" -> "Clean up Statistics"
                "traces" -> "Clean up API Traces"
                "prompts" -> "Clean up Prompts"
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
                                color = AiColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            "Delete data older than how many days?",
                            color = AiColors.TextSecondary
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
                                focusedBorderColor = AiColors.Orange,
                                unfocusedBorderColor = AiColors.TextDisabled,
                                focusedLabelColor = AiColors.Orange
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
                                        SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir).clearUsageStats()
                                        android.widget.Toast.makeText(context, "Cleared all statistics", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Enter 0 to clear statistics (no timestamp data)", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "traces" -> {
                                    deletedCount = com.ai.data.ApiTracer.deleteTracesOlderThan(cutoffTime)
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount trace(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "prompts" -> {
                                    val settingsPrefs = SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir)
                                    val history = settingsPrefs.loadPromptHistory()
                                    if (days == 0) {
                                        settingsPrefs.clearPromptHistory()
                                        android.widget.Toast.makeText(context, "Cleared all ${history.size} prompt(s)", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val kept = history.filter { it.timestamp >= cutoffTime }
                                        deletedCount = history.size - kept.size
                                        if (kept.isEmpty()) {
                                            settingsPrefs.clearPromptHistory()
                                        } else {
                                            settingsPrefs.savePromptHistoryList(kept)
                                        }
                                        android.widget.Toast.makeText(context, "Deleted $deletedCount prompt(s)", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }

                            showCleanupDaysDialog = null
                            cleanupDaysInput = "30"
                        }
                    ) {
                        Text("Delete", color = AiColors.Red)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCleanupDaysDialog = null
                            cleanupDaysInput = "30"
                        }
                    ) {
                        Text("Cancel", color = AiColors.Blue)
                    }
                },
                containerColor = AiColors.SurfaceDark
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
    aiSettings: AiSettings
) {
    val pricingCache = com.ai.data.PricingCache

    // Get all pricing sources
    val overridePricing = pricingCache.getAllManualPricing(context)
    val openRouterPricing = pricingCache.getOpenRouterPricing(context)
    val litellmPricing = pricingCache.getLiteLLMPricing(context)
    val fallbackPricing = pricingCache.getFallbackPricing()

    // Build model list from all providers using unified getModels accessor
    data class ProviderModel(val provider: String, val model: String)
    val allModels = mutableListOf<ProviderModel>()

    for (service in AiService.entries) {
        if (aiSettings.getProviderState(service) != "ok") continue
        val models = aiSettings.getModels(service)
        models.forEach { allModels.add(ProviderModel(service.id, it)) }
    }

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
    csv.appendLine("Provider,Model,Current $/M In,Current $/M Out,Current Source,Override $/M In,Override $/M Out,OpenRouter $/M In,OpenRouter $/M Out,LiteLLM $/M In,LiteLLM $/M Out,Fallback $/M In,Fallback $/M Out")

    for (pm in sortedModels) {
        val provider = com.ai.data.AiService.findById(pm.provider)
        val overrideKey = "${pm.provider}:${pm.model}"
        val override = overridePricing[overrideKey]

        // Current effective pricing (what getPricing returns)
        val current = if (provider != null) pricingCache.getPricing(context, provider, pm.model) else null

        // For OpenRouter, try both the full key and provider/model format
        val openRouterKey = if (pm.provider == "OPENROUTER") pm.model else findOpenRouterKey(pm.provider, pm.model, openRouterPricing)
        val openRouter = openRouterPricing[openRouterKey]

        // Skip OPENROUTER rows where current pricing equals OpenRouter pricing (redundant)
        if (pm.provider == "OPENROUTER" && current != null && openRouter != null &&
            current.promptPrice == openRouter.promptPrice && current.completionPrice == openRouter.completionPrice) {
            continue
        }

        // For LiteLLM, try direct and with prefix
        val litellmKey = findLiteLLMKey(pm.provider, pm.model, litellmPricing)
        val litellm = litellmPricing[litellmKey]

        val fallback = fallbackPricing[pm.model]

        csv.appendLine(buildString {
            append(escapeCsvField(pm.provider))
            append(",")
            append(escapeCsvField(pm.model))
            append(",")
            append(toDollarsPerMillion(current?.promptPrice))
            append(",")
            append(toDollarsPerMillion(current?.completionPrice))
            append(",")
            append(current?.source ?: "")
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
 * CSV format: Provider,Model,Current $/M In,Current $/M Out,Current Source,Override $/M In,Override $/M Out,...
 * Also supports legacy format without Current columns: Provider,Model,Override $/M In,Override $/M Out,...
 * Returns (imported count, skipped count).
 */
private fun importModelCostsFromCsv(context: android.content.Context, uri: android.net.Uri): Pair<Int, Int> {
    var imported = 0
    var skipped = 0

    try {
        val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readLines()
        } ?: return Pair(0, 0)

        if (lines.isEmpty()) return Pair(0, 0)

        // Detect format from header: new format has "Current Source" in column 4
        val header = parseCsvLine(lines[0])
        val hasCurrentColumns = header.size > 4 && header[4].trim().equals("Current Source", ignoreCase = true)
        val overrideInIdx = if (hasCurrentColumns) 5 else 2
        val overrideOutIdx = if (hasCurrentColumns) 6 else 3
        val minFields = overrideOutIdx + 1

        // Skip header row
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                skipped++
                continue
            }

            // Parse CSV line (handling quoted fields)
            val fields = parseCsvLine(line)
            if (fields.size < minFields) {
                skipped++
                continue
            }

            val providerName = fields[0].trim()
            val model = fields[1].trim()
            val overrideInputStr = fields[overrideInIdx].trim()
            val overrideOutputStr = fields[overrideOutIdx].trim()

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
            val provider = com.ai.data.AiService.findById(providerName)
            if (provider == null) {
                skipped++
                continue
            }

            // Compare against current calculated pricing (without overrides)
            // Only add an override if the CSV price differs from what we'd compute anyway
            val currentPricing = com.ai.data.PricingCache.getPricingWithoutOverride(context, provider, model)
            if (currentPricing.promptPrice == promptPricePerToken && currentPricing.completionPrice == completionPricePerToken) {
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
