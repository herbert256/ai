package com.ai.ui.report

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
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.delay

// ===== Navigation Wrapper =====

@Composable
fun ReportsScreenNav(
    viewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToTrace: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val agentResults by reportViewModel.agentResults.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSettings = uiState.aiSettings
    // A new report always starts with an empty model selection — no auto-fill from the
    // previous run. Save/load helpers are kept so an explicit "reuse previous" action can
    // be wired later, but they're intentionally not invoked here.
    val initialModels = emptyList<ReportModel>()

    val handleDismiss = {
        reportViewModel.dismissGenericReportsDialog()
        viewModel.clearExternalInstructions()
        onNavigateBack()
    }
    val handleNavigateHome = {
        reportViewModel.dismissGenericReportsDialog()
        viewModel.clearExternalInstructions()
        onNavigateHome()
    }
    val handleContinueInBackground = { reportViewModel.continueReportInBackground(); onNavigateHome() }

    ReportsScreen(
        uiState = uiState,
        reportsAgentResults = agentResults,
        initialModels = initialModels,
        onGenerate = { models, paramsIds, reportType ->
            val agentIds = models.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
            val swarmIds = models.filter { it.sourceType == "swarm" && it.type == "model" }.mapNotNull { it.sourceId }.toSet()
            val directIds = models.filter { it.sourceType == "model" }.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            viewModel.saveReportAgents(agentIds)
            viewModel.saveReportModels(models.map(::encodeSavedReportModelSelection).toSet())
            reportViewModel.generateGenericReports(
                scope = scope, context = context, selectedAgentIds = agentIds, selectedSwarmIds = swarmIds,
                directModelIds = directIds, parametersIds = paramsIds, reportType = reportType
            )
        },
        onStop = { reportViewModel.stopGenericReports(context, scope) },
        onDismiss = handleDismiss,
        onNavigateHome = handleNavigateHome,
        onContinueInBackground = handleContinueInBackground,
        advancedParameters = uiState.reportAdvancedParameters,
        onAdvancedParametersChange = { viewModel.setReportAdvancedParameters(it) },
        onNavigateToTrace = onNavigateToTrace,
        onClearExternalInstructions = viewModel::clearExternalInstructions
    )
}

// ===== Helpers =====

internal data class FormattedPricing(val text: String, val isDefault: Boolean)

internal fun formatPricingPerMillion(context: android.content.Context, provider: AppService, model: String): FormattedPricing {
    val p = PricingCache.getPricing(context, provider, model)
    val fmtIn = if (p.promptPrice * 1_000_000 < 0.01 && p.promptPrice > 0) "<.01" else "%.2f".format(p.promptPrice * 1_000_000)
    val fmtOut = if (p.completionPrice * 1_000_000 < 0.01 && p.completionPrice > 0) "<.01" else "%.2f".format(p.completionPrice * 1_000_000)
    return FormattedPricing("$fmtIn / $fmtOut", p.source == "DEFAULT")
}

private fun loadSavedReportModels(viewModel: AppViewModel, aiSettings: Settings): List<ReportModel> {
    val agentIds = viewModel.loadReportAgents()
    val agentModels = agentIds.mapNotNull { id -> aiSettings.getAgentById(id)?.let { expandAgentToModel(it, aiSettings) } }
    val savedSelections = viewModel.loadReportModels()
    val savedModels = savedSelections.flatMap { decodeSavedReportModelSelection(it, aiSettings) }
    return deduplicateModels(agentModels + savedModels)
}

private fun encodeSavedReportModelSelection(model: ReportModel): String {
    return when (model.sourceType) {
        "swarm" -> model.sourceId?.let { "swarm-id:$it" } ?: "swarm:${model.provider.id}:${model.model}"
        else -> "swarm:${model.provider.id}:${model.model}"
    }
}

private fun decodeSavedReportModelSelection(selection: String, aiSettings: Settings): List<ReportModel> {
    return when {
        selection.startsWith("swarm-id:") -> {
            aiSettings.getSwarmById(selection.removePrefix("swarm-id:"))
                ?.let { expandSwarmToModels(it, aiSettings) }
                ?: emptyList()
        }
        selection.startsWith("swarm:") -> {
            val parts = selection.removePrefix("swarm:").split(":", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: return emptyList()) ?: return emptyList()
            val model = parts.getOrNull(1) ?: return emptyList()
            listOf(toReportModel(provider, model))
        }
        else -> emptyList()
    }
}

// ===== Main Reports Screen =====

@Composable
fun ReportsScreen(
    uiState: UiState,
    reportsAgentResults: Map<String, AnalysisResponse>,
    initialModels: List<ReportModel> = emptyList(),
    onGenerate: (List<ReportModel>, List<String>, ReportType) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss,
    onContinueInBackground: () -> Unit = onNavigateHome,
    advancedParameters: AgentParameters? = null,
    onAdvancedParametersChange: (AgentParameters?) -> Unit = {},
    onNavigateToTrace: (String) -> Unit = {},
    onClearExternalInstructions: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val aiSettings = uiState.aiSettings

    val reportsTotal = uiState.genericReportsTotal
    val reportsProgress = uiState.genericReportsProgress
    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0
    val currentReportId = uiState.currentReportId

    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var emailSent by remember { mutableStateOf(false) }
    var showAdvancedParameters by remember { mutableStateOf(false) }

    var models by remember { mutableStateOf(initialModels) }
    var showSelectFlock by remember { mutableStateOf(false) }
    var showSelectAgent by remember { mutableStateOf(false) }
    var showSelectSwarm by remember { mutableStateOf(false) }
    var showSelectProvider by remember { mutableStateOf(false) }
    var pendingProvider by remember { mutableStateOf<AppService?>(null) }
    var showSelectAllModels by remember { mutableStateOf(false) }
    var selectedParametersIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var externalAutoGenerated by remember { mutableStateOf(false) }

    // Screen keepalive during generation
    DisposableEffect(isGenerating, isComplete) {
        if (isGenerating && !isComplete) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // External model resolution
    val externalModels = remember(uiState.externalAgentNames, uiState.externalFlockNames, uiState.externalSwarmNames, uiState.externalModelSpecs) {
        val result = mutableListOf<ReportModel>()
        uiState.externalAgentNames.forEach { name ->
            aiSettings.agents.find { it.name.equals(name, ignoreCase = true) }?.let { expandAgentToModel(it, aiSettings)?.let(result::add) }
        }
        uiState.externalFlockNames.forEach { name ->
            aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }?.let { result.addAll(expandFlockToModels(it, aiSettings)) }
        }
        uiState.externalSwarmNames.forEach { name ->
            aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }?.let { result.addAll(expandSwarmToModels(it, aiSettings)) }
        }
        uiState.externalModelSpecs.forEach { spec ->
            val parts = spec.split("/", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: "") ?: AppService.entries.find { it.displayName.equals(parts.getOrNull(0), ignoreCase = true) }
            val model = parts.getOrNull(1)
            if (provider != null && model != null) result.add(toReportModel(provider, model))
        }
        deduplicateModels(result)
    }

    // Auto-generate for external models
    LaunchedEffect(externalModels, uiState.externalReportType) {
        if (externalModels.isNotEmpty() && !externalAutoGenerated && !isGenerating && uiState.externalReportType != null && !uiState.externalSelect) {
            externalAutoGenerated = true
            val updatedModels = deduplicateModels(models + externalModels)
            models = updatedModels
            val type = if (uiState.externalReportType.equals("table", ignoreCase = true)) ReportType.TABLE else ReportType.CLASSIC
            onGenerate(updatedModels, selectedParametersIds, type)
        }
    }

    // Apply external models to selection
    LaunchedEffect(externalModels) {
        if (externalModels.isNotEmpty() && !externalAutoGenerated) {
            models = deduplicateModels(models + externalModels)
        }
    }

    // Auto email on completion
    LaunchedEffect(isComplete, currentReportId) {
        if (isComplete && currentReportId != null) {
            val email = uiState.externalEmail
            if (email != null && email.isNotBlank()) {
                emailReportAsHtml(context, currentReportId, email)
                if (uiState.externalReturn) activity?.finish()
            }
            val next = uiState.externalNextAction
            if (next != null) {
                delay(500)
                when (next.lowercase()) {
                    "view" -> showViewer = true
                    "share" -> shareReportAsHtml(context, currentReportId)
                    "browser" -> openReportInChrome(context, currentReportId)
                    "email" -> if (uiState.generalSettings.defaultEmail.isNotBlank()) emailReportAsHtml(context, currentReportId, uiState.generalSettings.defaultEmail)
                }
                if (uiState.externalReturn) { delay(1000); activity?.finish() }
            }
            if (
                uiState.externalEmail != null ||
                uiState.externalNextAction != null ||
                uiState.externalReturn ||
                uiState.externalReportType != null ||
                uiState.externalAgentNames.isNotEmpty() ||
                uiState.externalFlockNames.isNotEmpty() ||
                uiState.externalSwarmNames.isNotEmpty() ||
                uiState.externalModelSpecs.isNotEmpty()
            ) {
                onClearExternalInstructions()
            }
        }
    }

    // Full-screen overlays
    if (showViewer && currentReportId != null) {
        ReportsViewerScreen(reportId = currentReportId, initialSelectedAgentId = selectedAgentForViewer, onDismiss = { showViewer = false }, onNavigateHome = onNavigateHome)
        return
    }
    if (showAdvancedParameters) {
        ReportAdvancedParametersScreen(currentParameters = advancedParameters, onApply = { onAdvancedParametersChange(it); showAdvancedParameters = false }, onBack = { showAdvancedParameters = false })
        return
    }

    // Selection overlay dialogs
    if (showSelectFlock) { ReportSelectFlockDialog(aiSettings, onSelectFlock = { models = deduplicateModels(models + expandFlockToModels(it, aiSettings)); showSelectFlock = false }, onDismiss = { showSelectFlock = false }); return }
    if (showSelectAgent) { ReportSelectAgentDialog(aiSettings, onSelectAgent = { expandAgentToModel(it, aiSettings)?.let { m -> models = deduplicateModels(models + m) }; showSelectAgent = false }, onDismiss = { showSelectAgent = false }); return }
    if (showSelectSwarm) { ReportSelectSwarmDialog(aiSettings, onSelectSwarm = { models = deduplicateModels(models + expandSwarmToModels(it, aiSettings)); showSelectSwarm = false }, onDismiss = { showSelectSwarm = false }); return }
    if (showSelectProvider) { ReportSelectProviderDialog(aiSettings, onSelectProvider = { pendingProvider = it; showSelectProvider = false }, onDismiss = { showSelectProvider = false }); return }
    if (pendingProvider != null) { ReportSelectModelDialog(pendingProvider!!, aiSettings, onSelectModel = { models = deduplicateModels(models + toReportModel(pendingProvider!!, it)); pendingProvider = null }, onDismiss = { pendingProvider = null }); return }
    if (showSelectAllModels) { ReportSelectAllModelsDialog(aiSettings, onSelectModel = { prov, model -> models = deduplicateModels(models + toReportModel(prov, model)); showSelectAllModels = false }, onDismiss = { showSelectAllModels = false }); return }

    // Share dialog
    if (showShareDialog && currentReportId != null) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("Share Report") },
            text = { Text("Choose format:") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { shareReportAsJson(context, currentReportId); showShareDialog = false }) { Text("JSON", maxLines = 1, softWrap = false) }
                    TextButton(onClick = { shareReportAsHtml(context, currentReportId); showShareDialog = false }) { Text("HTML", maxLines = 1, softWrap = false) }
                }
            },
            dismissButton = { TextButton(onClick = { showShareDialog = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    // Email dialog
    if (showEmailDialog && currentReportId != null) {
        val emailAddress = uiState.generalSettings.defaultEmail
        LaunchedEffect(currentReportId) {
            emailSent = false
            emailReportAsHtml(context, currentReportId, emailAddress)
            emailSent = true
            delay(2000)
            showEmailDialog = false
        }
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = { Text(if (emailSent) "Email Sent" else "Sending...") },
            text = { Text(if (emailSent) "Report emailed to $emailAddress" else "Emailing report to $emailAddress...") },
            confirmButton = { TextButton(onClick = { showEmailDialog = false }) { Text("OK", maxLines = 1, softWrap = false) } }
        )
    }

    // Main UI
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // Title reflects the phase: selection (adding models) vs generation (showing results).
        TitleBar(
            title = if (isGenerating) "AI Reports" else "AI Report - Models",
            onBackClick = onDismiss, onAiClick = onNavigateHome
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (!isGenerating) {
            // Selection phase
            SelectionPhase(
                models = models,
                aiSettings = aiSettings,
                selectedParametersIds = selectedParametersIds,
                advancedParameters = advancedParameters,
                onAddFlock = { showSelectFlock = true },
                onAddAgent = { showSelectAgent = true },
                onAddSwarm = { showSelectSwarm = true },
                onAddModel = { showSelectProvider = true },
                onAddAllModels = { showSelectAllModels = true },
                onRemoveModel = { i -> models = models.filterIndexed { idx, _ -> idx != i } },
                onClearAll = { models = emptyList() },
                onAdvancedParams = { showAdvancedParameters = true },
                onParametersChange = { selectedParametersIds = it },
                onGenerate = { type -> if (models.isNotEmpty()) onGenerate(models, selectedParametersIds, type) }
            )
        } else {
            // Generation phase
            GenerationPhase(
                uiState = uiState,
                isComplete = isComplete,
                reportsProgress = reportsProgress,
                reportsTotal = reportsTotal,
                reportsAgentResults = reportsAgentResults,
                currentReportId = currentReportId,
                onStop = onStop,
                onContinueInBackground = onContinueInBackground,
                onView = { agentId -> selectedAgentForViewer = agentId; showViewer = true },
                onShare = { showShareDialog = true },
                onBrowser = { currentReportId?.let { openReportInChrome(context, it) } },
                onEmail = { showEmailDialog = true },
                onTrace = { currentReportId?.let(onNavigateToTrace) },
                hasDefaultEmail = uiState.generalSettings.defaultEmail.isNotBlank()
            )
        }
    }
}

// ===== Selection Phase =====

@Composable
private fun ColumnScope.SelectionPhase(
    models: List<ReportModel>,
    aiSettings: Settings,
    selectedParametersIds: List<String>,
    advancedParameters: AgentParameters?,
    onAddFlock: () -> Unit,
    onAddAgent: () -> Unit,
    onAddSwarm: () -> Unit,
    onAddModel: () -> Unit,
    onAddAllModels: () -> Unit,
    onRemoveModel: (Int) -> Unit,
    onClearAll: () -> Unit,
    onAdvancedParams: () -> Unit,
    onParametersChange: (List<String>) -> Unit,
    onGenerate: (ReportType) -> Unit
) {
    val context = LocalContext.current

    // Add buttons
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Button order: Agent, Flock, Swarm, Provider, Model. Note: onAddModel opens the provider
        // selector (hence "+Provider") and onAddAllModels opens the all-models picker (renamed
        // to the simpler "+Model" label per the new naming scheme).
        listOf(
            "Agent" to onAddAgent,
            "Flock" to onAddFlock,
            "Swarm" to onAddSwarm,
            "Provider" to onAddModel,
            "Model" to onAddAllModels
        ).forEach { (label, action) ->
            OutlinedButton(onClick = action, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                Text("+$label", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Selected models list
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (models.isEmpty()) {
            Text("No models selected", color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            models.forEachIndexed { index, entry ->
                val pricing = formatPricingPerMillion(context, entry.provider, entry.model)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.model, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.provider.displayName}${if (entry.sourceName.isNotBlank()) " via ${entry.sourceName}" else ""}", fontSize = 11.sp, color = AppColors.TextTertiary)
                    }
                    Text(pricing.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (pricing.isDefault) AppColors.SurfaceDark else AppColors.Red,
                        modifier = if (pricing.isDefault) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("\u2715", color = AppColors.Red, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveModel(index) })
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Bottom buttons
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (models.isNotEmpty()) OutlinedButton(onClick = onClearAll, modifier = Modifier.weight(1f)) { Text("Clear", maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = onAdvancedParams, modifier = Modifier.weight(1f)) {
            Text(if (advancedParameters != null) "Params \u2713" else "Params", fontSize = 13.sp, maxLines = 1, softWrap = false)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Generate — the layout ("One by one" vs "All together") is now chosen in the exported
    // HTML via a toggle, so we no longer split this into two buttons. Pass CLASSIC as the
    // default ReportType since both renderers now share the same HTML output.
    Button(
        onClick = { onGenerate(ReportType.CLASSIC) },
        enabled = models.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
    ) { Text("Generate", maxLines = 1, softWrap = false) }
}

// ===== Generation Phase =====

@Composable
private fun ColumnScope.GenerationPhase(
    uiState: UiState,
    isComplete: Boolean,
    reportsProgress: Int,
    reportsTotal: Int,
    reportsAgentResults: Map<String, AnalysisResponse>,
    currentReportId: String?,
    onStop: () -> Unit,
    onContinueInBackground: () -> Unit,
    onView: (String?) -> Unit,
    onShare: () -> Unit,
    onBrowser: () -> Unit,
    onEmail: () -> Unit,
    onTrace: () -> Unit,
    hasDefaultEmail: Boolean
) {
    val context = LocalContext.current
    val aiSettings = uiState.aiSettings

    fun resolveModelForResult(agentId: String, result: AnalysisResponse): String {
        return aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
            ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
            ?: result.service.defaultModel
    }

    // Progress
    Text("$reportsProgress / $reportsTotal complete", color = AppColors.TextSecondary, fontSize = 14.sp)
    LinearProgressIndicator(
        progress = { if (reportsTotal > 0) reportsProgress.toFloat() / reportsTotal else 0f },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = if (isComplete) AppColors.Green else AppColors.Purple
    )

    // Agent results
    val selectedAgents = uiState.genericReportsSelectedAgents
    val totalCost = remember(reportsAgentResults) {
        reportsAgentResults.entries.sumOf { (agentId, resp) ->
            resp.tokenUsage?.let { tu ->
                tu.apiCost ?: run {
                    val p = PricingCache.getPricing(context, resp.service, resolveModelForResult(agentId, resp))
                    tu.inputTokens * p.promptPrice + tu.outputTokens * p.completionPrice
                }
            } ?: 0.0
        }
    }

    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        selectedAgents.sorted().forEach { agentId ->
            val result = reportsAgentResults[agentId]
            val agent = aiSettings.getAgentById(agentId)
            val displayName = result?.displayName ?: agent?.name ?: agentId.removePrefix("swarm:").replace(":", " / ")

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(
                if (result != null) Modifier.clickable { onView(agentId) } else Modifier
            ), verticalAlignment = Alignment.CenterVertically) {
                // Status icon
                Text(
                    text = when { result == null -> "\u23F3"; result.isSuccess -> "\u2705"; else -> "\u274C" },
                    fontSize = 16.sp, modifier = Modifier.width(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (result?.tokenUsage != null) {
                        Text("${result.tokenUsage.inputTokens}/${result.tokenUsage.outputTokens} tok", fontSize = 10.sp, color = AppColors.TextTertiary)
                    }
                }
                if (result?.tokenUsage != null) {
                    val cost = result.tokenUsage.apiCost ?: run {
                        val p = PricingCache.getPricing(context, result.service, resolveModelForResult(agentId, result))
                        result.tokenUsage.inputTokens * p.promptPrice + result.tokenUsage.outputTokens * p.completionPrice
                    }
                    Text(formatCents(cost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }

        if (reportsAgentResults.isNotEmpty()) {
            val totalIn = reportsAgentResults.values.sumOf { it.tokenUsage?.inputTokens ?: 0 }
            val totalOut = reportsAgentResults.values.sumOf { it.tokenUsage?.outputTokens ?: 0 }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Total: $totalIn/$totalOut tok", fontSize = 12.sp, color = AppColors.Blue, modifier = Modifier.weight(1f))
                Text("${formatCents(totalCost)} \u00A2", fontSize = 12.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Action buttons
    if (!isComplete) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStop, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)) { Text("STOP", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onContinueInBackground, modifier = Modifier.weight(1f)) { Text("Background", maxLines = 1, softWrap = false) }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onView(null) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("View", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onShare, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Share", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onBrowser, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Browser", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            if (hasDefaultEmail) Button(onClick = onEmail, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Email", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            if (currentReportId != null) Button(onClick = onTrace, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Trace", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }
}
