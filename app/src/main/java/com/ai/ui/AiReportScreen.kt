package com.ai.ui

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Navigation wrapper for AI Reports screen.
 */
@Composable
fun AiReportsScreenNav(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToTrace: (String) -> Unit = {},
    developerMode: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val aiSettings = uiState.aiSettings

    // Reset state when leaving the screen
    val handleDismiss = {
        viewModel.dismissGenericAiReportsDialog()
        onNavigateBack()
    }

    val handleNavigateHome = {
        viewModel.dismissGenericAiReportsDialog()
        onNavigateHome()
    }

    AiReportsScreen(
        uiState = uiState,
        onGenerate = { modelsList, paramsIds, reportType ->
            // Split models into agent-based and model-based
            val agentIds = modelsList.filter { it.agentId != null }.mapNotNull { it.agentId }.toSet()
            val directModelIds = modelsList.filter { it.agentId == null }
                .map { "swarm:${it.provider.id}:${it.model}" }.toSet()

            // Save for persistence
            viewModel.saveAiReportAgents(agentIds)
            viewModel.saveAiReportModels(directModelIds)
            viewModel.saveAiReportFlocks(emptySet())
            viewModel.saveAiReportSwarms(emptySet())

            viewModel.generateGenericAiReports(agentIds, emptySet(), directModelIds, paramsIds, reportType)
        },
        onStop = { viewModel.stopGenericAiReports() },
        onShare = { shareGenericAiReports(context, uiState) },
        onOpenInBrowser = {
            val reportId = uiState.currentReportId
            if (reportId != null) {
                openAiReportInChrome(context, reportId, uiState.generalSettings.developerMode)
            }
        },
        onDismiss = handleDismiss,
        onResetReports = { viewModel.dismissGenericAiReportsDialog() },
        onNavigateHome = handleNavigateHome,
        advancedParameters = uiState.reportAdvancedParameters,
        onAdvancedParametersChange = { viewModel.setReportAdvancedParameters(it) },
        onNavigateToTrace = onNavigateToTrace,
        developerMode = developerMode
    )
}

/**
 * Full-screen AI Reports generation and results screen.
 * Shows model selection list first, then progress and results.
 */

/**
 * Format model pricing as "input / output" per million tokens (e.g. "2.50 / 10.00").
 * Returns the formatted string and whether the pricing is from the default fallback.
 */
internal data class FormattedPricing(val text: String, val isDefault: Boolean)

internal fun formatPricingPerMillion(context: android.content.Context, provider: com.ai.data.AiService, model: String): FormattedPricing {
    val pricing = com.ai.data.PricingCache.getPricing(context, provider, model)
    val input = pricing.promptPrice * 1_000_000
    val output = pricing.completionPrice * 1_000_000
    fun fmt(v: Double): String = when {
        v == 0.0 -> "0.00"
        v < 0.01 -> "0.01"
        else -> "%.2f".format(v)
    }
    return FormattedPricing("${fmt(input)} / ${fmt(output)}", pricing.source == "DEFAULT")
}

@Composable
fun AiReportsScreen(
    uiState: AiUiState,
    initialModels: List<ReportModel> = emptyList(),
    onGenerate: (List<ReportModel>, List<String>, com.ai.data.ReportType) -> Unit,  // models, paramsIds, reportType
    onStop: () -> Unit,
    onShare: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
    onResetReports: () -> Unit = {},
    onNavigateHome: () -> Unit = onDismiss,
    advancedParameters: AiAgentParameters? = null,
    onAdvancedParametersChange: (AiAgentParameters?) -> Unit = {},
    onNavigateToTrace: (String) -> Unit = {},
    developerMode: Boolean = false
) {
    val reportsTotal = uiState.genericAiReportsTotal
    val reportsProgress = uiState.genericAiReportsProgress
    val reportsAgentResults = uiState.genericAiReportsAgentResults
    val reportsSelectedAgents = uiState.genericAiReportsSelectedAgents

    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0

    val context = LocalContext.current

    // Keep screen on while generating reports
    val activity = context as? Activity
    DisposableEffect(isGenerating, isComplete) {
        if (isGenerating && !isComplete) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Viewer state
    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }

    // Share dialog state
    var showShareDialog by remember { mutableStateOf(false) }

    // Email dialog state
    var showEmailDialog by remember { mutableStateOf(false) }
    var emailSent by remember { mutableStateOf(false) }

    // Advanced parameters screen state
    var showAdvancedParameters by remember { mutableStateOf(false) }

    // Show advanced parameters screen
    if (showAdvancedParameters) {
        ReportAdvancedParametersScreen(
            currentParameters = advancedParameters,
            onApply = { params ->
                onAdvancedParametersChange(params)
                showAdvancedParameters = false
            },
            onBack = { showAdvancedParameters = false }
        )
        return
    }

    // Share dialog
    val currentReportId = uiState.currentReportId
    if (showShareDialog && currentReportId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = {
                Text("Share Report", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Choose format to share:", color = Color(0xFFAAAAAA))
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareAiReportAsJson(context, currentReportId)
                        }
                    ) {
                        Text("JSON", color = Color(0xFF6B9BFF))
                    }
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareAiReportAsHtml(context, currentReportId, uiState.generalSettings.developerMode)
                        }
                    ) {
                        Text("HTML", color = Color(0xFF4CAF50))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            },
            containerColor = Color(0xFF2A2A2A),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Email sending dialog
    if (showEmailDialog && currentReportId != null) {
        val emailAddress = uiState.generalSettings.defaultEmail
        LaunchedEffect(currentReportId) {
            emailSent = false
            emailAiReportAsHtml(context, currentReportId, emailAddress, uiState.generalSettings.developerMode)
            emailSent = true
            kotlinx.coroutines.delay(2000)
            showEmailDialog = false
            emailSent = false
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    if (emailSent) "Email sent" else "Sending email",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (!emailSent) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color(0xFF6B9BFF),
                            strokeWidth = 2.dp
                        )
                        Text("Sending to $emailAddress", color = Color(0xFFAAAAAA))
                    }
                } else {
                    Text("Report emailed to $emailAddress", color = Color(0xFF4CAF50))
                }
            },
            confirmButton = { },
            containerColor = Color(0xFF2A2A2A),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Auto-execute <next> action when report completes (or is stopped)
    // Also handles <email> tag (auto-email + finish)
    LaunchedEffect(isComplete, currentReportId) {
        if (isComplete && currentReportId != null) {
            // Handle <email> tag: auto-email + finish
            if (uiState.externalEmail != null) {
                emailAiReportAsHtml(context, currentReportId, uiState.externalEmail, uiState.generalSettings.developerMode)
                kotlinx.coroutines.delay(1000)
                activity?.finish()
                return@LaunchedEffect
            }
            // Handle <next> tag: auto-trigger the matching button action
            val shouldReturn = uiState.externalReturn
            when (uiState.externalNextAction?.lowercase()) {
                "view" -> showViewer = true  // <return> handled in viewer onDismiss
                "share" -> {
                    showShareDialog = true
                    if (shouldReturn) {
                        kotlinx.coroutines.delay(1000)
                        activity?.finish()
                    }
                }
                "browser" -> {
                    onOpenInBrowser()
                    if (shouldReturn) {
                        kotlinx.coroutines.delay(1000)
                        activity?.finish()
                    }
                }
                "email" -> {
                    if (uiState.generalSettings.defaultEmail.isNotBlank()) {
                        showEmailDialog = true
                        if (shouldReturn) {
                            kotlinx.coroutines.delay(2000)
                            activity?.finish()
                        }
                    }
                }
            }
        }
    }

    // Show viewer screen when activated (uses stored AI-REPORT from persistent storage)
    if (showViewer && currentReportId != null) {
        AiReportsViewerScreen(
            reportId = currentReportId,
            initialSelectedAgentId = selectedAgentForViewer,
            onDismiss = {
                showViewer = false
                // <return> tag: finish() when coming back from View
                if (uiState.externalReturn && uiState.externalNextAction != null) {
                    activity?.finish()
                }
            },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Models list (single source of truth for selection)
    val aiSettings = uiState.aiSettings

    // Build external models from intent tags
    val externalModels = remember(uiState.externalAgentNames, uiState.externalFlockNames, uiState.externalSwarmNames, uiState.externalModelSpecs) {
        val extModels = mutableListOf<ReportModel>()

        // Resolve <agent> names
        uiState.externalAgentNames.forEach { name ->
            aiSettings.agents.find { it.name.equals(name, ignoreCase = true) }?.let { agent ->
                expandAgentToModel(agent, aiSettings)?.let { extModels.add(it) }
            }
        }

        // Resolve <flock> names
        uiState.externalFlockNames.forEach { name ->
            aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }?.let { flock ->
                extModels.addAll(expandFlockToModels(flock, aiSettings))
            }
        }

        // Resolve <swarm> names
        uiState.externalSwarmNames.forEach { name ->
            aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }?.let { swarm ->
                extModels.addAll(expandSwarmToModels(swarm, aiSettings))
            }
        }

        // Resolve <model>provider/model</model> specs
        uiState.externalModelSpecs.forEach { spec ->
            val parts = spec.split("/", limit = 2)
            if (parts.size == 2) {
                val providerName = parts[0].trim()
                val modelName = parts[1].trim()
                val provider = com.ai.data.AiService.entries.find {
                    it.id.equals(providerName, ignoreCase = true) ||
                        it.displayName.equals(providerName, ignoreCase = true)
                }
                if (provider != null) {
                    extModels.add(toReportModel(provider, modelName))
                }
            }
        }

        deduplicateModels(extModels)
    }

    val hasExternalSelections = externalModels.isNotEmpty()

    var models by remember {
        mutableStateOf(
            if (hasExternalSelections) deduplicateModels(externalModels)
            else initialModels
        )
    }

    // Overlay states for Select screens
    var showSelectFlock by remember { mutableStateOf(false) }
    var showSelectAgent by remember { mutableStateOf(false) }
    var showSelectSwarm by remember { mutableStateOf(false) }
    var showSelectProvider by remember { mutableStateOf(false) }
    var pendingProvider by remember { mutableStateOf<com.ai.data.AiService?>(null) }
    var showSelectAllModels by remember { mutableStateOf(false) }

    // Parameters preset selection for the report (multi-select)
    var selectedParametersIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // Auto-generate when external API selections + type are present
    var externalAutoGenerated by remember { mutableStateOf(false) }
    LaunchedEffect(hasExternalSelections, uiState.externalReportType) {
        if (hasExternalSelections && uiState.externalReportType != null && !uiState.externalSelect && !isGenerating && !externalAutoGenerated) {
            externalAutoGenerated = true
            val reportType = if (uiState.externalReportType.equals("Table", ignoreCase = true))
                com.ai.data.ReportType.TABLE else com.ai.data.ReportType.CLASSIC
            if (models.isNotEmpty()) {
                onGenerate(models, emptyList(), reportType)
            }
        }
    }

    // Selection screen handlers — popup dialogs or full-screen overlays
    val usePopup = uiState.generalSettings.popupModelSelection

    if (usePopup) {
        if (showSelectFlock) {
            ReportSelectFlockDialog(
                aiSettings = aiSettings,
                onSelectFlock = { flock ->
                    val newModels = expandFlockToModels(flock, aiSettings)
                    models = deduplicateModels(models + newModels)
                    showSelectFlock = false
                },
                onDismiss = { showSelectFlock = false }
            )
        }
        if (showSelectAgent) {
            ReportSelectAgentDialog(
                aiSettings = aiSettings,
                onSelectAgent = { agent ->
                    expandAgentToModel(agent, aiSettings)?.let { entry ->
                        models = deduplicateModels(models + entry)
                    }
                    showSelectAgent = false
                },
                onDismiss = { showSelectAgent = false }
            )
        }
        if (showSelectSwarm) {
            ReportSelectSwarmDialog(
                aiSettings = aiSettings,
                onSelectSwarm = { swarm ->
                    val newModels = expandSwarmToModels(swarm, aiSettings)
                    models = deduplicateModels(models + newModels)
                    showSelectSwarm = false
                },
                onDismiss = { showSelectSwarm = false }
            )
        }
        if (showSelectProvider) {
            ReportSelectProviderDialog(
                aiSettings = aiSettings,
                onSelectProvider = { provider ->
                    showSelectProvider = false
                    pendingProvider = provider
                },
                onDismiss = { showSelectProvider = false }
            )
        }
        if (pendingProvider != null) {
            ReportSelectModelDialog(
                provider = pendingProvider!!,
                aiSettings = aiSettings,
                onSelectModel = { model ->
                    models = deduplicateModels(models + toReportModel(pendingProvider!!, model))
                    pendingProvider = null
                },
                onDismiss = { pendingProvider = null }
            )
        }
        if (showSelectAllModels) {
            ReportSelectAllModelsDialog(
                aiSettings = aiSettings,
                onSelectModel = { provider, model ->
                    models = deduplicateModels(models + toReportModel(provider, model))
                    showSelectAllModels = false
                },
                onDismiss = { showSelectAllModels = false }
            )
        }
    } else {
        // Full-screen overlay mode
        if (showSelectFlock) {
            SelectFlockScreen(
                aiSettings = aiSettings,
                onSelectFlock = { flock ->
                    val newModels = expandFlockToModels(flock, aiSettings)
                    models = deduplicateModels(models + newModels)
                    showSelectFlock = false
                },
                onBack = { showSelectFlock = false },
                onNavigateHome = onNavigateHome
            )
            return
        }
        if (showSelectAgent) {
            SelectAgentScreen(
                aiSettings = aiSettings,
                onSelectAgent = { agent ->
                    expandAgentToModel(agent, aiSettings)?.let { entry ->
                        models = deduplicateModels(models + entry)
                    }
                    showSelectAgent = false
                },
                onBack = { showSelectAgent = false },
                onNavigateHome = onNavigateHome
            )
            return
        }
        if (showSelectSwarm) {
            SelectSwarmScreen(
                aiSettings = aiSettings,
                onSelectSwarm = { swarm ->
                    val newModels = expandSwarmToModels(swarm, aiSettings)
                    models = deduplicateModels(models + newModels)
                    showSelectSwarm = false
                },
                onBack = { showSelectSwarm = false },
                onNavigateHome = onNavigateHome
            )
            return
        }
        if (showSelectProvider) {
            SelectProviderScreen(
                aiSettings = aiSettings,
                onSelectProvider = { provider ->
                    showSelectProvider = false
                    pendingProvider = provider
                },
                onBack = { showSelectProvider = false },
                onNavigateHome = onNavigateHome
            )
            return
        }
        if (pendingProvider != null) {
            SelectModelScreen(
                provider = pendingProvider!!,
                aiSettings = aiSettings,
                currentModel = "",
                onSelectModel = { model ->
                    models = deduplicateModels(models + toReportModel(pendingProvider!!, model))
                    pendingProvider = null
                },
                onBack = { pendingProvider = null },
                onNavigateHome = onNavigateHome
            )
            return
        }
        if (showSelectAllModels) {
            SelectAllModelsScreen(
                aiSettings = aiSettings,
                onSelectModel = { provider, model ->
                    models = deduplicateModels(models + toReportModel(provider, model))
                    showSelectAllModels = false
                },
                onBack = { showSelectAllModels = false },
                onNavigateHome = onNavigateHome
            )
            return
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = when {
                isComplete -> "Reports Ready"
                isGenerating -> "Generating Reports"
                else -> "Select Models"
            },
            onBackClick = if (isComplete) onResetReports else onDismiss,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (!isGenerating) {
            // Action row: Parameters (left), Generate (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Parameters button
                var showParamsDialog by remember { mutableStateOf(false) }
                Button(
                    onClick = { showParamsDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B5CF6)
                    )
                ) {
                    Text(
                        if (selectedParametersIds.isNotEmpty()) "\u2699 Parameters" else "Parameters",
                        fontSize = 13.sp, maxLines = 1
                    )
                }
                if (showParamsDialog) {
                    ParametersSelectorDialog(
                        aiSettings = uiState.aiSettings,
                        selectedParametersIds = selectedParametersIds,
                        onParamsSelected = { ids -> selectedParametersIds = ids },
                        onDismiss = { showParamsDialog = false }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Report type selection popup state
                var showReportTypeDialog by remember { mutableStateOf(false) }

                // Generate button (right-aligned)
                Button(
                    onClick = {
                        val extType = uiState.externalReportType
                        if (extType != null) {
                            val reportType = if (extType.equals("Table", ignoreCase = true))
                                com.ai.data.ReportType.TABLE else com.ai.data.ReportType.CLASSIC
                            onGenerate(models, selectedParametersIds, reportType)
                        } else {
                            showReportTypeDialog = true
                        }
                    },
                    enabled = models.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("Next", fontSize = 13.sp, maxLines = 1)
                }

                // Report type selection dialog
                if (showReportTypeDialog) {
                    AlertDialog(
                        onDismissRequest = { showReportTypeDialog = false },
                        title = {
                            Text("Select report type", fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        showReportTypeDialog = false
                                        onGenerate(models, selectedParametersIds, com.ai.data.ReportType.CLASSIC)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2196F3)
                                    )
                                ) {
                                    Text("Classic")
                                }
                                Button(
                                    onClick = {
                                        showReportTypeDialog = false
                                        onGenerate(models, selectedParametersIds, com.ai.data.ReportType.TABLE)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF8B5CF6)
                                    )
                                ) {
                                    Text("Table")
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showReportTypeDialog = false }) {
                                Text("Cancel", color = Color(0xFF888888))
                            }
                        },
                        containerColor = Color(0xFF2A2A2A),
                        titleContentColor = Color.White,
                        textContentColor = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // "Add" card with buttons
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = { showSelectFlock = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B9BFF))
                        ) {
                            Text("Flocks", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { showSelectAgent = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B9BFF))
                        ) {
                            Text("Agents", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { showSelectSwarm = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B9BFF))
                        ) {
                            Text("Swarms", fontSize = 13.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = { showSelectProvider = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B9BFF))
                        ) {
                            Text("Provider", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { showSelectAllModels = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B9BFF))
                        ) {
                            Text("All Models", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Total row
            if (models.isNotEmpty()) {
                var totalIn = 0.0
                var totalOut = 0.0
                models.forEach { entry ->
                    val p = com.ai.data.PricingCache.getPricing(context, entry.provider, entry.model)
                    totalIn += p.promptPrice * 1_000_000
                    totalOut += p.completionPrice * 1_000_000
                }
                fun fmtTotal(v: Double): String = when {
                    v == 0.0 -> "0.00"
                    v < 0.01 -> "0.01"
                    else -> "%.2f".format(v)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Models selected: ${models.size}",
                        fontSize = 12.sp,
                        color = Color(0xFFAAAAAA)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${fmtTotal(totalIn)} / ${fmtTotal(totalOut)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF6B6B)
                    )
                }
            }

            // Models list
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (models.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No models added yet.\nUse the buttons above to add models.",
                            color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(models.size, key = { index -> "${models[index].deduplicationKey}:$index" }) { index ->
                            val entry = models[index]
                            val pricing = formatPricingPerMillion(context, entry.provider, entry.model)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        models = models.filterIndexed { i, _ -> i != index }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("\u2715", color = Color(0xFFFF6666), fontSize = 14.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.provider.displayName,
                                        fontSize = 11.sp,
                                        color = Color(0xFFAAAAAA),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.model,
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = pricing.text,
                                    color = if (pricing.isDefault) Color(0xFF2A2A2A) else Color(0xFFFF6B6B),
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = 1,
                                    modifier = if (pricing.isDefault) Modifier.background(Color(0xFF666666), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
                                )
                            }
                        }
                    }
                }
            }


        } else {
            // Action buttons at top when complete
            if (isComplete) {
                val compactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    Button(
                        onClick = { showViewer = true },
                        contentPadding = compactButtonPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3)
                        )
                    ) {
                        Text("View")
                    }
                    Button(
                        onClick = { showShareDialog = true },
                        contentPadding = compactButtonPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text("Share")
                    }
                    Button(
                        onClick = onOpenInBrowser,
                        contentPadding = compactButtonPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B5CF6)
                        )
                    ) {
                        Text("Browser")
                    }
                    // Email button - only shown when default email is configured
                    if (uiState.generalSettings.defaultEmail.isNotBlank()) {
                        Button(
                            onClick = { showEmailDialog = true },
                            contentPadding = compactButtonPadding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE91E63)
                            )
                        ) {
                            Text("Email")
                        }
                    }
                    // Trace button - only shown in developer mode
                    if (developerMode && currentReportId != null) {
                        Button(
                            onClick = { onNavigateToTrace(currentReportId) },
                            contentPadding = compactButtonPadding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF9800)
                            )
                        ) {
                            Text("Trace")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Report title (shown both during generation and when complete)
            if (uiState.genericAiPromptTitle.isNotBlank()) {
                Text(
                    text = uiState.genericAiPromptTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Calculate costs for each agent and swarm member
            val agentCosts = remember(reportsAgentResults) {
                reportsAgentResults.mapNotNull { (agentId, result) ->
                    val tokenUsage = result.tokenUsage ?: return@mapNotNull null

                    // Check if it's a swarm member (synthetic ID)
                    if (agentId.startsWith("swarm:")) {
                        val parts = agentId.removePrefix("swarm:").split(":", limit = 2)
                        val providerName = parts.getOrNull(0) ?: return@mapNotNull null
                        val modelName = parts.getOrNull(1) ?: return@mapNotNull null
                        val provider = com.ai.data.AiService.findById(providerName) ?: return@mapNotNull null

                        val cost = tokenUsage.apiCost ?: run {
                            val pricing = com.ai.data.PricingCache.getPricing(context, provider, modelName)
                            val inputCost = tokenUsage.inputTokens * pricing.promptPrice
                            val outputCost = tokenUsage.outputTokens * pricing.completionPrice
                            inputCost + outputCost
                        }
                        return@mapNotNull agentId to cost
                    }

                    // Regular agent
                    val agent = uiState.aiSettings.getAgentById(agentId) ?: return@mapNotNull null
                    // Priority: API cost > OVERRIDE > OPENROUTER > LITELLM > FALLBACK > DEFAULT
                    val cost = tokenUsage.apiCost ?: run {
                        val pricing = com.ai.data.PricingCache.getPricing(context, agent.provider, agent.model)
                        val inputCost = tokenUsage.inputTokens * pricing.promptPrice
                        val outputCost = tokenUsage.outputTokens * pricing.completionPrice
                        inputCost + outputCost
                    }
                    agentId to cost
                }.toMap()
            }
            val totalCost = agentCosts.values.sum()

            // Progress/Results UI
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(start = 4.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Table header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Agent",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Input",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Output",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Cents",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                    HorizontalDivider(color = Color(0xFF404040))

                    // Show all selected agents with their status
                    reportsSelectedAgents.mapNotNull { agentId ->
                        uiState.aiSettings.getAgentById(agentId)
                    }.sortedBy { it.name.lowercase() }.forEach { agent ->
                        val result = reportsAgentResults[agent.id]
                        val cost = agentCosts[agent.id]
                        val tokenUsage = result?.tokenUsage
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status icon first
                            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                when {
                                    result == null -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.Gray
                                        )
                                    }
                                    result.isSuccess -> {
                                        Text("\u2713", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    else -> {
                                        Text("\u2717", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            // Agent name
                            Text(
                                text = agent.name,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            // Input tokens
                            Text(
                                text = tokenUsage?.inputTokens?.toString() ?: "",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFAAAAAA),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(50.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            // Output tokens
                            Text(
                                text = tokenUsage?.outputTokens?.toString() ?: "",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFAAAAAA),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(50.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            // Cost in cents (right-aligned, only show if available)
                            Text(
                                text = if (cost != null) String.format("%.4f", cost * 100) else "",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(70.dp)
                            )
                        }
                    }

                    // Show swarm members (synthetic IDs starting with "swarm:")
                    reportsSelectedAgents.filter { it.startsWith("swarm:") }
                        .sortedBy { it.lowercase() }
                        .forEach { swarmId ->
                            // Parse synthetic ID: "swarm:PROVIDER:model"
                            val parts = swarmId.removePrefix("swarm:").split(":", limit = 2)
                            val providerName = parts.getOrNull(0) ?: ""
                            val modelName = parts.getOrNull(1) ?: ""
                            val provider = com.ai.data.AiService.findById(providerName)

                            val result = reportsAgentResults[swarmId]
                            val cost = agentCosts[swarmId]
                            val tokenUsage = result?.tokenUsage

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Status icon first
                                Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                    when {
                                        result == null -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.Gray
                                            )
                                        }
                                        result.isSuccess -> {
                                            Text("\u2713", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                        else -> {
                                            Text("\u2717", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                                // Swarm member: Provider + Model inline
                                Row(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${provider?.displayName ?: providerName} ",
                                        color = Color(0xFFAAAAAA),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = modelName,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // Input tokens
                                Text(
                                    text = tokenUsage?.inputTokens?.toString() ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(50.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                // Output tokens
                                Text(
                                    text = tokenUsage?.outputTokens?.toString() ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(50.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                // Cost in cents
                                Text(
                                    text = if (cost != null) String.format("%.4f", cost * 100) else "",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF4CAF50),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(70.dp)
                                )
                            }
                        }

                    // Calculate total tokens
                    val totalInputTokens = reportsAgentResults.values.sumOf { it.tokenUsage?.inputTokens ?: 0 }
                    val totalOutputTokens = reportsAgentResults.values.sumOf { it.tokenUsage?.outputTokens ?: 0 }

                    // Total cost row - always show, accumulates as agents complete
                    HorizontalDivider(color = Color(0xFF404040))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Total",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        // Total input tokens
                        Text(
                            text = if (totalInputTokens > 0) totalInputTokens.toString() else "",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        // Total output tokens
                        Text(
                            text = if (totalOutputTokens > 0) totalOutputTokens.toString() else "",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = String.format("%.4f", totalCost * 100),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                }
            }

            // STOP button while generating
            if (isGenerating && !isComplete) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCC3333)
                    )
                ) {
                    Text("STOP", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
