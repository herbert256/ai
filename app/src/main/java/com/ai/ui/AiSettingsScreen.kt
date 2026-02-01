package com.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ai.data.AiService
import com.ai.data.ApiFormat


/**
 * AI Setup hub screen with navigation cards for Providers, Prompts, and Agents.
 */
@Composable
fun AiSetupScreen(
    aiSettings: AiSettings,
    developerMode: Boolean = false,
    openRouterApiKey: String = "",
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit = {},
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onRefreshAllModels: suspend (AiSettings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onTestApiKey: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AiService, String) -> Unit = { _, _ -> },
    onNavigateToCostConfig: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Refresh All progress state
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshProgressText by remember { mutableStateOf("") }

    // Runs the same Refresh All logic as Housekeeping
    fun runRefreshAll(settings: AiSettings) {
        scope.launch {
            isRefreshing = true
            refreshProgressText = "Refreshing model lists..."
            onRefreshAllModels(settings, true) { provider ->
                refreshProgressText = "Models: $provider"
            }

            if (openRouterApiKey.isNotBlank()) {
                refreshProgressText = "OpenRouter data..."
                val pricing = com.ai.data.PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                if (pricing.isNotEmpty()) {
                    com.ai.data.PricingCache.saveOpenRouterPricing(context, pricing)
                }
                com.ai.data.PricingCache.fetchAndSaveModelSpecifications(context, openRouterApiKey)
            }

            refreshProgressText = "Provider state..."
            for (service in com.ai.data.AiService.entries) {
                if (settings.getProviderState(service) == "inactive") continue
                refreshProgressText = "State: ${service.displayName}"
                val apiKey = settings.getApiKey(service)
                if (apiKey.isBlank()) {
                    onProviderStateChange(service, "not-used")
                } else {
                    val model = settings.getModel(service)
                    val error = onTestApiKey(service, apiKey, model)
                    onProviderStateChange(service, if (error == null) "ok" else "error")
                }
            }

            refreshProgressText = "Default agents..."
            val providersToTest = com.ai.data.AiService.entries.filter { settings.getApiKey(it).isNotBlank() }
            var updatedAgents = settings.agents.toMutableList()
            for (provider in providersToTest) {
                refreshProgressText = "Agents: ${provider.displayName}"
                val testResult = onTestApiKey(provider, settings.getApiKey(provider), provider.defaultModel)
                if (testResult == null) {
                    val idx = updatedAgents.indexOfFirst { it.name == provider.displayName }
                    if (idx >= 0) {
                        updatedAgents[idx] = updatedAgents[idx].copy(model = "", apiKey = "", provider = provider, endpointId = null)
                    } else {
                        updatedAgents.add(AiAgent(id = java.util.UUID.randomUUID().toString(), name = provider.displayName, provider = provider, model = "", apiKey = "", endpointId = null))
                    }
                }
            }
            val workingCount = providersToTest.count { provider ->
                updatedAgents.any { it.name == provider.displayName }
            }
            if (workingCount > 0) {
                val defaultAgentIds = updatedAgents.filter { agent -> com.ai.data.AiService.entries.any { it.displayName == agent.name } }.map { it.id }
                val updatedFlocks = settings.flocks.toMutableList()
                val flockIdx = updatedFlocks.indexOfFirst { it.name == "default agents" }
                if (flockIdx >= 0) {
                    updatedFlocks[flockIdx] = updatedFlocks[flockIdx].copy(agentIds = defaultAgentIds)
                } else {
                    updatedFlocks.add(AiFlock(id = java.util.UUID.randomUUID().toString(), name = "default agents", agentIds = defaultAgentIds))
                }
                onSave(settings.copy(agents = updatedAgents, flocks = updatedFlocks))
            }

            isRefreshing = false
            refreshProgressText = ""
            android.widget.Toast.makeText(context, "All refreshed", android.widget.Toast.LENGTH_SHORT).show()
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
                runRefreshAll(result.aiSettings)
            }
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
                runRefreshAll(updatedSettings)
            }
        }
    }

    // Refresh All progress dialog
    if (isRefreshing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Refresh All", fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFF4CAF50),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(refreshProgressText, color = Color.White)
                }
            },
            confirmButton = { }
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
            title = "AI Setup",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        // Import buttons - only show when no provider has an API key
        val hasAnyKey = aiSettings.hasAnyApiKey()
        val showImportConfig = !hasAnyKey
        val showImportKeys = !hasAnyKey
        if (showImportConfig || showImportKeys) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showImportConfig) {
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Import AI Configuration", fontSize = 12.sp)
                    }
                }
                if (showImportKeys) {
                    Button(
                        onClick = {
                            apiKeysPickerLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Import API Keys", fontSize = 12.sp)
                    }
                }
            }
        }

        // Summary info (count agents with active providers)
        val configuredAgents = aiSettings.agents.count { agent ->
            aiSettings.getEffectiveApiKeyForAgent(agent).isNotBlank() && aiSettings.isProviderActive(agent.provider)
        }

        // Providers card
        val providerCount = AiService.entries.size
        AiSetupNavigationCard(
            title = "Providers",
            description = "Configure model sources for each AI service",
            icon = "⚙",
            count = "$providerCount providers",
            onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) }
        )

        // Agents card
        val hasApiKey = aiSettings.hasAnyApiKey()
        AiSetupNavigationCard(
            title = "Agents",
            description = "Configure agents with provider, model, and API key",
            icon = "🤖",
            count = "$configuredAgents configured",
            onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) },
            enabled = hasApiKey
        )

        // Flocks card
        val configuredFlocks = aiSettings.flocks.size
        AiSetupNavigationCard(
            title = "Flocks",
            description = "Group agents into flocks for report generation",
            icon = "🦆",
            count = "$configuredFlocks configured",
            onClick = { onNavigate(SettingsSubScreen.AI_SWARMS) },
            enabled = hasApiKey
        )

        // Swarms card
        val configuredSwarms = aiSettings.swarms.size
        AiSetupNavigationCard(
            title = "Swarms",
            description = "Group provider/model combinations for report generation",
            icon = "🐝",
            count = "$configuredSwarms configured",
            onClick = { onNavigate(SettingsSubScreen.AI_FLOCKS) },
            enabled = hasApiKey
        )

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = Color(0xFF404040))
        Spacer(modifier = Modifier.height(4.dp))

        // Parameters card
        val configuredParameters = aiSettings.parameters.size
        AiSetupNavigationCard(
            title = "Parameters",
            description = "Reusable parameter presets for agents",
            icon = "🎛",
            count = "$configuredParameters configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PARAMETERS) }
        )

        // Prompts card
        val configuredPrompts = aiSettings.prompts.size
        AiSetupNavigationCard(
            title = "Prompts",
            description = "Internal prompts for AI-powered features",
            icon = "📝",
            count = "$configuredPrompts configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS) }
        )

        // Costs card
        val manualPricingCount = remember(aiSettings) {
            com.ai.data.PricingCache.getAllManualPricing(context).size
        }
        AiSetupNavigationCard(
            title = "Costs",
            description = "Configure manual price overrides per model",
            icon = "💰",
            count = "$manualPricingCount configured",
            onClick = onNavigateToCostConfig
        )

    }
}

/**
 * Navigation card for AI Setup screen.
 */
@Composable
internal fun AiSetupNavigationCard(
    title: String,
    description: String,
    icon: String,
    count: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1A1A1A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium,
                color = if (enabled) Color.Unspecified else Color(0xFF555555)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else Color(0xFF555555)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFFAAAAAA) else Color(0xFF444444)
                )
            }
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFF00E676) else Color(0xFF444444)
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Color(0xFF888888) else Color(0xFF333333)
                )
            }
        }
    }
}

/**
 * AI Providers screen - configure model source for each provider.
 */
@Composable
fun AiProvidersScreen(
    aiSettings: AiSettings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onProviderSelected: (AiService) -> Unit,
    onAddProvider: () -> Unit = {},
    onEditProviderDef: (AiService) -> Unit = {},
    onDeleteProvider: (AiService) -> Unit = {}
) {
    // Toggle between showing only active ("ok") providers and all providers
    var showAll by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<AiService?>(null) }

    // Provider definitions: service and title, built dynamically from registry
    data class ProviderEntry(
        val service: AiService,
        val title: String
    )

    val allProviders = AiService.entries.map { service ->
        ProviderEntry(service, service.displayName)
    }.sortedBy { it.title.lowercase() }

    val visibleProviders = if (showAll) {
        allProviders
    } else {
        allProviders.filter { aiSettings.getProviderState(it.service) == "ok" }
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { service ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Provider") },
            text = { Text("Delete \"${service.displayName}\"? This will also remove all agents and swarm members using this provider.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProvider(service)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
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
            title = "AI Providers",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        // Add Provider button
        OutlinedButton(
            onClick = onAddProvider,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50))
        ) {
            Text("+ Add Custom Provider")
        }

        // Provider cards
        visibleProviders.forEach { entry ->
            AiServiceNavigationCard(
                title = entry.title,
                providerState = aiSettings.getProviderState(entry.service),
                showStateDetails = showAll,
                adminUrl = entry.service.adminUrl,
                onEdit = { onProviderSelected(entry.service) },
                onEditDefinition = { onEditProviderDef(entry.service) },
                onDelete = { showDeleteConfirm = entry.service }
            )
        }

        // Toggle button: switch between active-only and all providers
        val activeCount = allProviders.count { aiSettings.getProviderState(it.service) == "ok" }
        OutlinedButton(
            onClick = { showAll = !showAll },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (showAll) "Show active providers ($activeCount)"
                else "Show all providers (${allProviders.size})"
            )
        }
    }
}

/**
 * Editor screen for creating or editing a provider definition (AiService properties).
 * When provider is null, creates a new provider. When non-null, edits existing.
 */
@Composable
fun ProviderDefinitionEditorScreen(
    provider: AiService?,
    onSave: (AiService) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val isNew = provider == null

    var id by remember { mutableStateOf(provider?.id ?: "") }
    var displayName by remember { mutableStateOf(provider?.displayName ?: "") }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: "https://") }
    var adminUrl by remember { mutableStateOf(provider?.adminUrl ?: "") }
    var defaultModel by remember { mutableStateOf(provider?.defaultModel ?: "") }
    var apiFormat by remember { mutableStateOf(provider?.apiFormat ?: ApiFormat.OPENAI_COMPATIBLE) }
    var chatPath by remember { mutableStateOf(provider?.chatPath ?: "v1/chat/completions") }
    var modelsPath by remember { mutableStateOf(provider?.modelsPath ?: "v1/models") }
    var modelsPathNull by remember { mutableStateOf(provider?.modelsPath == null) }
    var prefsKey by remember { mutableStateOf(provider?.prefsKey ?: "") }
    var openRouterName by remember { mutableStateOf(provider?.openRouterName ?: "") }

    // Advanced fields
    var showAdvanced by remember { mutableStateOf(false) }
    var seedFieldName by remember { mutableStateOf(provider?.seedFieldName ?: "seed") }
    var supportsCitations by remember { mutableStateOf(provider?.supportsCitations ?: false) }
    var supportsSearchRecency by remember { mutableStateOf(provider?.supportsSearchRecency ?: false) }
    var extractApiCost by remember { mutableStateOf(provider?.extractApiCost ?: false) }
    var modelListFormat by remember { mutableStateOf(provider?.modelListFormat ?: "object") }
    var modelFilter by remember { mutableStateOf(provider?.modelFilter ?: "") }
    var litellmPrefix by remember { mutableStateOf(provider?.litellmPrefix ?: "") }

    // Validation
    val idValid = id.isNotBlank() && (!isNew || AiService.findById(id) == null)
    val nameValid = displayName.isNotBlank()
    val urlValid = baseUrl.startsWith("http")
    val canSave = idValid && nameValid && urlValid && defaultModel.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = if (isNew) "Add Provider" else "Edit Provider",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        // ID field (only editable for new providers)
        OutlinedTextField(
            value = id,
            onValueChange = {
                id = it.uppercase().replace(Regex("[^A-Z0-9_]"), "")
                if (prefsKey.isBlank() || prefsKey == id.lowercase().dropLast(1)) {
                    prefsKey = id.lowercase()
                }
            },
            label = { Text("Provider ID") },
            placeholder = { Text("MY_PROVIDER") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = isNew,
            isError = id.isNotBlank() && !idValid,
            supportingText = if (id.isNotBlank() && !idValid) {
                { Text("ID already exists") }
            } else {
                { Text("Uppercase letters, numbers, underscores only") }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display Name") },
            placeholder = { Text("My Provider") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.example.com/") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = baseUrl.isNotBlank() && !urlValid,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        OutlinedTextField(
            value = defaultModel,
            onValueChange = { defaultModel = it },
            label = { Text("Default Model") },
            placeholder = { Text("model-name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // API Format dropdown
        var formatExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = apiFormat.name,
                onValueChange = {},
                label = { Text("API Format") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { formatExpanded = true },
                readOnly = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
            DropdownMenu(
                expanded = formatExpanded,
                onDismissRequest = { formatExpanded = false }
            ) {
                ApiFormat.entries.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.name) },
                        onClick = {
                            apiFormat = format
                            when (format) {
                                ApiFormat.ANTHROPIC -> {
                                    chatPath = "v1/messages"
                                    modelsPath = ""
                                    modelsPathNull = true
                                }
                                ApiFormat.GOOGLE -> {
                                    chatPath = "v1beta/models"
                                    modelsPath = "v1beta/models"
                                }
                                ApiFormat.OPENAI_COMPATIBLE -> {
                                    chatPath = "v1/chat/completions"
                                    modelsPath = "v1/models"
                                    modelsPathNull = false
                                }
                            }
                            formatExpanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = chatPath,
            onValueChange = { chatPath = it },
            label = { Text("Chat Path") },
            placeholder = { Text("v1/chat/completions") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = if (modelsPathNull) "" else modelsPath,
                onValueChange = { modelsPath = it; modelsPathNull = false },
                label = { Text("Models Path") },
                placeholder = { Text("v1/models") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !modelsPathNull,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("None", style = MaterialTheme.typography.bodySmall, color = Color(0xFF888888))
                Switch(
                    checked = modelsPathNull,
                    onCheckedChange = { modelsPathNull = it }
                )
            }
        }

        OutlinedTextField(
            value = adminUrl,
            onValueChange = { adminUrl = it },
            label = { Text("Admin URL (optional)") },
            placeholder = { Text("https://platform.example.com/usage") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        OutlinedTextField(
            value = prefsKey,
            onValueChange = { prefsKey = it.lowercase().replace(Regex("[^a-z0-9_]"), "") },
            label = { Text("Preferences Key") },
            placeholder = { Text("my_provider") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Used for SharedPreferences storage") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Advanced section
        OutlinedButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showAdvanced) "Hide Advanced" else "Show Advanced")
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = openRouterName,
                onValueChange = { openRouterName = it },
                label = { Text("OpenRouter Name (optional)") },
                placeholder = { Text("provider-prefix") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            OutlinedTextField(
                value = seedFieldName,
                onValueChange = { seedFieldName = it },
                label = { Text("Seed Field Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                label = { Text("Model Filter Regex (optional)") },
                placeholder = { Text("gpt|o1|o3") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            OutlinedTextField(
                value = litellmPrefix,
                onValueChange = { litellmPrefix = it },
                label = { Text("LiteLLM Prefix (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            // Model list format
            var listFormatExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = modelListFormat,
                    onValueChange = {},
                    label = { Text("Model List Format") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { listFormatExpanded = true },
                    readOnly = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
                DropdownMenu(
                    expanded = listFormatExpanded,
                    onDismissRequest = { listFormatExpanded = false }
                ) {
                    listOf("object", "array", "google").forEach { fmt ->
                        DropdownMenuItem(
                            text = { Text(fmt) },
                            onClick = { modelListFormat = fmt; listFormatExpanded = false }
                        )
                    }
                }
            }

            // Boolean toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Supports Citations", color = Color.White)
                Switch(checked = supportsCitations, onCheckedChange = { supportsCitations = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Supports Search Recency", color = Color.White)
                Switch(checked = supportsSearchRecency, onCheckedChange = { supportsSearchRecency = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Extract API Cost", color = Color.White)
                Switch(checked = extractApiCost, onCheckedChange = { extractApiCost = it })
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Save button
        Button(
            onClick = {
                val effectivePrefsKey = prefsKey.ifBlank { id.lowercase() }
                val service = AiService(
                    id = id,
                    displayName = displayName,
                    baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
                    adminUrl = adminUrl,
                    defaultModel = defaultModel,
                    openRouterName = openRouterName.ifBlank { null },
                    apiFormat = apiFormat,
                    chatPath = chatPath,
                    modelsPath = if (modelsPathNull) null else modelsPath.ifBlank { null },
                    prefsKey = effectivePrefsKey,
                    seedFieldName = seedFieldName,
                    supportsCitations = supportsCitations,
                    supportsSearchRecency = supportsSearchRecency,
                    extractApiCost = extractApiCost,
                    modelListFormat = modelListFormat,
                    modelFilter = modelFilter.ifBlank { null },
                    litellmPrefix = litellmPrefix.ifBlank { null }
                )
                onSave(service)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50),
                disabledContainerColor = Color(0xFF333333)
            )
        ) {
            Text(if (isNew) "Add Provider" else "Save Changes")
        }

        // Back button
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
