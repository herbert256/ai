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
import com.ai.data.AppService
import com.ai.data.ApiFormat

private val idSanitizeRegex = Regex("[^A-Z0-9_]")
private val prefsKeySanitizeRegex = Regex("[^a-z0-9_]")

/**
 * AI Setup hub screen with navigation cards for Providers, Prompts, and Agents.
 */
@Composable
fun SetupScreen(
    aiSettings: Settings,
    developerMode: Boolean = false,
    huggingFaceApiKey: String = "",
    openRouterApiKey: String = "",
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (Settings) -> Unit = {},
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onTestApiKey: suspend (AppService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToCostConfig: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Refresh All progress state
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshProgressText by remember { mutableStateOf("") }

    // Runs the same Refresh All logic as Housekeeping
    fun runRefreshAll(settings: Settings) {
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
            for (service in com.ai.data.AppService.entries) {
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
            val providersToTest = com.ai.data.AppService.entries.filter { settings.getApiKey(it).isNotBlank() }
            var updatedAgents = settings.agents.toMutableList()
            for (provider in providersToTest) {
                refreshProgressText = "Agents: ${provider.displayName}"
                val testResult = onTestApiKey(provider, settings.getApiKey(provider), provider.defaultModel)
                if (testResult == null) {
                    val idx = updatedAgents.indexOfFirst { it.name == provider.displayName }
                    if (idx >= 0) {
                        updatedAgents[idx] = updatedAgents[idx].copy(model = "", apiKey = "", provider = provider, endpointId = null)
                    } else {
                        updatedAgents.add(Agent(id = java.util.UUID.randomUUID().toString(), name = provider.displayName, provider = provider, model = "", apiKey = "", endpointId = null))
                    }
                }
            }
            val workingCount = providersToTest.count { provider ->
                updatedAgents.any { it.name == provider.displayName }
            }
            if (workingCount > 0) {
                val defaultAgentIds = updatedAgents.filter { agent -> com.ai.data.AppService.entries.any { it.displayName == agent.name } }.map { it.id }
                val updatedFlocks = settings.flocks.toMutableList()
                val flockIdx = updatedFlocks.indexOfFirst { it.name == "default agents" }
                if (flockIdx >= 0) {
                    updatedFlocks[flockIdx] = updatedFlocks[flockIdx].copy(agentIds = defaultAgentIds)
                } else {
                    updatedFlocks.add(Flock(id = java.util.UUID.randomUUID().toString(), name = "default agents", agentIds = defaultAgentIds))
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
                        color = AppColors.Green,
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
        TitleBar(
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
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
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
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
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
        val providerCount = AppService.entries.size
        SetupNavigationCard(
            title = "Providers",
            description = "Configure model sources for each AI service",
            icon = "⚙",
            count = "$providerCount providers",
            onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) }
        )

        // Agents card
        val hasApiKey = aiSettings.hasAnyApiKey()
        SetupNavigationCard(
            title = "Agents",
            description = "Configure agents with provider, model, and API key",
            icon = "🤖",
            count = "$configuredAgents configured",
            onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) },
            enabled = hasApiKey
        )

        // Flocks card
        val configuredFlocks = aiSettings.flocks.size
        SetupNavigationCard(
            title = "Flocks",
            description = "Group agents into flocks for report generation",
            icon = "🦆",
            count = "$configuredFlocks configured",
            onClick = { onNavigate(SettingsSubScreen.AI_SWARMS) },
            enabled = hasApiKey
        )

        // Swarms card
        val configuredSwarms = aiSettings.swarms.size
        SetupNavigationCard(
            title = "Swarms",
            description = "Group provider/model combinations for report generation",
            icon = "🐝",
            count = "$configuredSwarms configured",
            onClick = { onNavigate(SettingsSubScreen.AI_FLOCKS) },
            enabled = hasApiKey
        )

        // Parameters card
        val configuredParameters = aiSettings.parameters.size
        SetupNavigationCard(
            title = "Parameters",
            description = "Reusable parameter presets for agents",
            icon = "🎛",
            count = "$configuredParameters configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PARAMETERS) }
        )

        // System Prompts card
        val configuredSystemPrompts = aiSettings.systemPrompts.size
        SetupNavigationCard(
            title = "System Prompts",
            description = "Reusable system prompts for agents, flocks, and swarms",
            icon = "\uD83D\uDCAC",
            count = "$configuredSystemPrompts configured",
            onClick = { onNavigate(SettingsSubScreen.AI_SYSTEM_PROMPTS) }
        )

        // Internal Prompts card
        val configuredPrompts = aiSettings.prompts.size
        SetupNavigationCard(
            title = "Internal Prompts",
            description = "Internal prompts for AI-powered features",
            icon = "📝",
            count = "$configuredPrompts configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS) }
        )

        // Costs card
        val manualPricingCount = remember(aiSettings) {
            com.ai.data.PricingCache.getAllManualPricing(context).size
        }
        SetupNavigationCard(
            title = "Costs",
            description = "Configure manual price overrides per model",
            icon = "💰",
            count = "$manualPricingCount configured",
            onClick = onNavigateToCostConfig
        )

        // External Services card
        val externalCount = listOf(huggingFaceApiKey, openRouterApiKey).count { it.isNotBlank() }
        SetupNavigationCard(
            title = "External Services",
            description = "Hugging Face and OpenRouter API keys",
            icon = "🔑",
            count = "$externalCount configured",
            onClick = { onNavigate(SettingsSubScreen.AI_EXTERNAL_SERVICES) }
        )

    }
}

/**
 * Navigation card for AI Setup screen.
 */
@Composable
internal fun SetupNavigationCard(
    title: String,
    description: String,
    icon: String,
    count: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else AppColors.DisabledBackground
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
                color = if (enabled) Color.Unspecified else AppColors.TextDisabled
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else AppColors.TextDisabled
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) AppColors.TextSecondary else AppColors.BorderUnfocused
                )
            }
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) AppColors.CountGreen else AppColors.BorderUnfocused
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) AppColors.TextTertiary else AppColors.DividerDark
                )
            }
        }
    }
}

/**
 * AI Providers screen - configure model source for each provider.
 */
@Composable
fun ProvidersScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onProviderSelected: (AppService) -> Unit,
    onAddProvider: (AppService) -> Unit = {}
) {
    // Toggle between showing only active ("ok") providers and all providers
    var showAll by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newProviderName by remember { mutableStateOf("") }

    // Provider definitions: service and title, built dynamically from registry
    data class ProviderEntry(
        val service: AppService,
        val title: String
    )

    val allProviders = AppService.entries.map { service ->
        ProviderEntry(service, service.displayName)
    }.sortedBy { it.title.lowercase() }

    val visibleProviders = if (showAll) {
        allProviders
    } else {
        allProviders.filter { aiSettings.getProviderState(it.service) == "ok" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(
            title = "AI Providers",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        // Add Provider button
        OutlinedButton(
            onClick = { newProviderName = ""; showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Green)
        ) {
            Text("+ Add Provider")
        }

        // Provider cards
        visibleProviders.forEach { entry ->
            ServiceNavigationCard(
                title = entry.title,
                providerState = aiSettings.getProviderState(entry.service),
                showStateDetails = showAll,
                adminUrl = entry.service.adminUrl,
                onEdit = { onProviderSelected(entry.service) }
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

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Provider", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newProviderName,
                    onValueChange = { newProviderName = it },
                    label = { Text("Provider name") },
                    placeholder = { Text("e.g. My Local LLM") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProviderName.isNotBlank()) {
                            val id = newProviderName.trim().uppercase().replace(idSanitizeRegex, "_")
                            val prefsKey = newProviderName.trim().lowercase().replace(prefsKeySanitizeRegex, "_")
                            val newService = AppService(
                                id = id,
                                displayName = newProviderName.trim(),
                                baseUrl = "https://",
                                adminUrl = "",
                                defaultModel = "",
                                prefsKey = prefsKey
                            )
                            showAddDialog = false
                            onAddProvider(newService)
                        }
                    },
                    enabled = newProviderName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Editor screen for creating or editing a provider definition (AppService properties).
 * When provider is null, creates a new provider. When non-null, edits existing.
 */
@Composable
fun ProviderDefinitionEditorScreen(
    provider: AppService?,
    onSave: (AppService) -> Unit,
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
    var endpointRules by remember { mutableStateOf(provider?.endpointRules ?: emptyList()) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRuleIndex by remember { mutableIntStateOf(-1) }

    // Validation
    val idValid = id.isNotBlank() && (!isNew || AppService.findById(id) == null)
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
        TitleBar(
            title = if (isNew) "Add Provider" else "Edit Provider",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        // ID field (only editable for new providers)
        OutlinedTextField(
            value = id,
            onValueChange = {
                id = it.uppercase().replace(idSanitizeRegex, "")
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
            colors = AppColors.outlinedFieldColors()
        )

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display Name") },
            placeholder = { Text("My Provider") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = AppColors.outlinedFieldColors()
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.example.com/") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = baseUrl.isNotBlank() && !urlValid,
            colors = AppColors.outlinedFieldColors()
        )

        OutlinedTextField(
            value = defaultModel,
            onValueChange = { defaultModel = it },
            label = { Text("Default Model") },
            placeholder = { Text("model-name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = AppColors.outlinedFieldColors()
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
                colors = AppColors.outlinedFieldColors()
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
            colors = AppColors.outlinedFieldColors()
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
                colors = AppColors.outlinedFieldColors()
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("None", style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
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
            colors = AppColors.outlinedFieldColors()
        )

        OutlinedTextField(
            value = prefsKey,
            onValueChange = { prefsKey = it.lowercase().replace(prefsKeySanitizeRegex, "") },
            label = { Text("Preferences Key") },
            placeholder = { Text("my_provider") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Used for SharedPreferences storage") },
            colors = AppColors.outlinedFieldColors()
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
                colors = AppColors.outlinedFieldColors()
            )

            OutlinedTextField(
                value = seedFieldName,
                onValueChange = { seedFieldName = it },
                label = { Text("Seed Field Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = AppColors.outlinedFieldColors()
            )

            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                label = { Text("Model Filter Regex (optional)") },
                placeholder = { Text("gpt|o1|o3") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = AppColors.outlinedFieldColors()
            )

            OutlinedTextField(
                value = litellmPrefix,
                onValueChange = { litellmPrefix = it },
                label = { Text("LiteLLM Prefix (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = AppColors.outlinedFieldColors()
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
                    colors = AppColors.outlinedFieldColors()
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

            // Endpoint Rules section
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Endpoint Rules", color = Color.White, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { editingRuleIndex = -1; showAddRuleDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("+ Add", fontSize = 12.sp)
                }
            }
            Text(
                "Route models to different API endpoints by prefix",
                color = AppColors.TextTertiary,
                fontSize = 12.sp
            )
            endpointRules.forEachIndexed { index, rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.SurfaceDark, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${rule.modelPrefix}*", color = AppColors.Blue, fontSize = 14.sp)
                        Text("→ ${rule.endpointType}", color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
                    Row {
                        IconButton(onClick = { editingRuleIndex = index; showAddRuleDialog = true }) {
                            Text("✎", color = AppColors.TextTertiary)
                        }
                        IconButton(onClick = {
                            endpointRules = endpointRules.toMutableList().also { it.removeAt(index) }
                        }) {
                            Text("✕", color = Color(0xFFCC4444))
                        }
                    }
                }
            }

            // Add/Edit Rule Dialog
            if (showAddRuleDialog) {
                val editing = editingRuleIndex >= 0
                val existingRule = if (editing) endpointRules[editingRuleIndex] else null
                var rulePrefix by remember(showAddRuleDialog, editingRuleIndex) { mutableStateOf(existingRule?.modelPrefix ?: "") }
                var ruleType by remember(showAddRuleDialog, editingRuleIndex) { mutableStateOf(existingRule?.endpointType ?: "responses") }

                AlertDialog(
                    onDismissRequest = { showAddRuleDialog = false },
                    title = { Text(if (editing) "Edit Rule" else "Add Endpoint Rule") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = rulePrefix,
                                onValueChange = { rulePrefix = it },
                                label = { Text("Model Prefix") },
                                placeholder = { Text("e.g. gpt-5, o3, o4") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text("Endpoint Type:", color = Color.White, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("responses", "chat").forEach { type ->
                                    FilterChip(
                                        selected = ruleType == type,
                                        onClick = { ruleType = type },
                                        label = { Text(type) }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (rulePrefix.isNotBlank()) {
                                    val newRule = com.ai.data.EndpointRule(rulePrefix, ruleType)
                                    endpointRules = if (editing) {
                                        endpointRules.toMutableList().also { it[editingRuleIndex] = newRule }
                                    } else {
                                        endpointRules + newRule
                                    }
                                    showAddRuleDialog = false
                                }
                            }
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddRuleDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Save button
        Button(
            onClick = {
                val effectivePrefsKey = prefsKey.ifBlank { id.lowercase() }
                val service = AppService(
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
                    litellmPrefix = litellmPrefix.ifBlank { null },
                    endpointRules = endpointRules
                )
                onSave(service)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Green,
                disabledContainerColor = AppColors.DividerDark
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

/**
 * External Services screen for Hugging Face and OpenRouter API keys.
 */
@Composable
fun ExternalServicesScreen(
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var hfKey by remember { mutableStateOf(huggingFaceApiKey) }
    var orKey by remember { mutableStateOf(openRouterApiKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(
            title = "External Services",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

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
                OutlinedTextField(
                    value = hfKey,
                    onValueChange = {
                        hfKey = it
                        onSaveHuggingFaceApiKey(it)
                    },
                    label = { Text("Hugging Face API Key") },
                    placeholder = { Text("hf_...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Orange,
                        unfocusedBorderColor = AppColors.BorderUnfocused
                    )
                )
                Text(
                    text = "Used for fetching model info. Get your token at huggingface.co/settings/tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )

                OutlinedTextField(
                    value = orKey,
                    onValueChange = {
                        orKey = it
                        onSaveOpenRouterApiKey(it)
                    },
                    label = { Text("OpenRouter API Key") },
                    placeholder = { Text("sk-or-...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Orange,
                        unfocusedBorderColor = AppColors.BorderUnfocused
                    )
                )
                Text(
                    text = "Used for AI Housekeeping. Get your key at openrouter.ai/settings/keys",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }
        }
    }
}
