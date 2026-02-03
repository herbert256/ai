package com.ai.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ai.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI hub screen - the home page of the app.
 * Shows links to AI Reports, AI Statistics, AI Chat, and AI Models.
 * Also has navigation icons for Settings, Trace, and Help.
 */
@Composable
fun AiHubScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToDeveloperOptions: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToReportsHub: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToCosts: () -> Unit,
    onNavigateToChatsHub: () -> Unit,
    onNavigateToAiSetup: () -> Unit,
    onNavigateToHousekeeping: () -> Unit,
    onNavigateToModelSearch: () -> Unit,
    viewModel: AiViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Check if at least one AI Agent is configured
    val hasAnyAgent = uiState.aiSettings.agents.isNotEmpty()

    // Check if at least one AI Provider has an API key configured
    val hasAnyProviderApiKey = com.ai.data.AiService.entries
        .any { uiState.aiSettings.getApiKey(it).isNotBlank() }

    // Check if there is any statistics data
    val hasStatisticsData = remember {
        val settingsPrefs = SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir)
        settingsPrefs.loadUsageStats().isNotEmpty()
    }

    // Calculate number of cards and required height
    val cardHeight = 50.dp  // HubCard height (icon 34sp + padding)
    val cardSpacing = 12.dp

    // Count cards that will be shown (all cards always shown, some may be disabled)
    var cardCount = 9  // AI Reports, AI Chat, AI Models, AI Statistics, AI Costs, AI Setup, AI Housekeeping, Settings, Help
    val extraSpacing = if (uiState.generalSettings.developerMode) 64.dp else 32.dp  // 32dp before Settings + 32dp before Developer Options

    if (uiState.generalSettings.developerMode) cardCount += 1  // Developer Options

    // Calculate total height needed for cards
    val cardsHeight = (cardHeight * cardCount) + (cardSpacing * (cardCount - 1)) + extraSpacing

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        // Calculate logo size based on available height
        val availableForLogo = maxHeight - cardsHeight
        val logoSize = availableForLogo.coerceIn(100.dp, 220.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // App logo with dynamic size (offset to remove built-in padding)
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "AI App Logo",
                modifier = Modifier
                    .size(logoSize)
                    .offset(y = (-32).dp)
            )

            // Cards that require at least 1 AI Agent (shown grayed out if no agents)
            HubCard(icon = "\uD83D\uDCDD", title = "AI Reports", onClick = onNavigateToReportsHub, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDCAC", title = "AI Chat", onClick = onNavigateToChatsHub, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDDE0", title = "AI Models", onClick = onNavigateToModelSearch, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))

            // Cards that require statistics data (shown grayed out if no data)
            HubCard(icon = "\uD83D\uDCCA", title = "AI Statistics", onClick = onNavigateToStatistics, enabled = hasStatisticsData)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDCB0", title = "AI Costs", onClick = onNavigateToCosts, enabled = hasStatisticsData)
            Spacer(modifier = Modifier.height(12.dp))

            // AI Setup always shown and enabled
            HubCard(icon = "\uD83E\uDD16", title = "AI Setup", onClick = onNavigateToAiSetup)
            Spacer(modifier = Modifier.height(12.dp))

            // AI Housekeeping requires at least 1 provider with API key
            HubCard(icon = "\uD83E\uDDF9", title = "AI Housekeeping", onClick = onNavigateToHousekeeping, enabled = hasAnyProviderApiKey)

            Spacer(modifier = Modifier.height(32.dp))
            HubCard(icon = "\u2699\uFE0F", title = "General Settings", onClick = onNavigateToSettings)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\u2753", title = "Help", onClick = onNavigateToHelp)

            // Developer Options card (developer mode only)
            if (uiState.generalSettings.developerMode) {
                Spacer(modifier = Modifier.height(32.dp))
                HubCard(icon = "\uD83D\uDC1E", title = "Developer Options", onClick = onNavigateToDeveloperOptions)
            }
        }
    }
}

/**
 * Compact card component for the AI Hub screen.
 */
@Composable
private fun HubCard(
    icon: String,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFF2A3A4A) else Color(0xFF1A2A3A)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 26.sp,
                modifier = if (enabled) Modifier else Modifier.alpha(0.4f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color.White else Color(0xFF666666)
            )
        }
    }
}

/**
 * AI Reports hub screen.
 * Shows links to New AI Report, Prompt History, and AI History.
 */
@Composable
fun AiReportsHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val context = LocalContext.current

    // Check if there are any stored prompts
    val hasPromptHistory = remember {
        val settingsPrefs = SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir)
        settingsPrefs.loadPromptHistory().isNotEmpty()
    }

    // Check if there are any previous reports
    val hasPreviousReports = remember {
        com.ai.data.AiReportStorage.getAllReports(context).isNotEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Reports",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(24.dp))

        // New AI Report card
        HubCard(
            icon = "\uD83D\uDCDD",
            title = "New AI Report",
            onClick = onNavigateToNewReport
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Start with a previous prompt card
        HubCard(
            icon = "\uD83D\uDD04",
            title = "Start with a previous prompt",
            onClick = onNavigateToPromptHistory,
            enabled = hasPromptHistory
        )

        Spacer(modifier = Modifier.height(12.dp))

        // View previous reports card
        HubCard(
            icon = "\uD83D\uDCDA",
            title = "View previous reports",
            onClick = onNavigateToHistory,
            enabled = hasPreviousReports
        )
    }
}

/**
 * New AI Report screen for entering a custom prompt.
 * Used as a navigation destination.
 */
@Composable
fun AiNewReportScreen(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToAiReports: () -> Unit = {},
    initialTitle: String = "",
    initialPrompt: String = ""
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    // Load last prompt from SharedPreferences on first composition
    // Use initialTitle/initialPrompt if provided (from prompt history or external app),
    // otherwise load from persistent "last prompt" storage
    var title by remember {
        val lastTitle = prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: ""
        mutableStateOf(initialTitle.ifEmpty { lastTitle })
    }
    // Extract and preserve <user>...</user> block separately from display prompt
    val userTagRegex = Regex("""<user>.*?</user>""", RegexOption.DOT_MATCHES_ALL)
    val rawPrompt = remember { initialPrompt.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" } }
    val userTagBlock = remember { userTagRegex.find(rawPrompt)?.value ?: "" }
    var prompt by remember {
        // Strip <user>...</user> content from display (it goes to HTML export only)
        mutableStateOf(rawPrompt.replace(userTagRegex, "").trim())
    }

    // Navigate to AI Reports screen when agent selection is triggered
    LaunchedEffect(uiState.showGenericAiAgentSelection) {
        if (uiState.showGenericAiAgentSelection) {
            viewModel.dismissGenericAiAgentSelection()
            onNavigateToAiReports()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "New AI Report",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Clear and Submit buttons side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Clear button
            OutlinedButton(
                onClick = {
                    title = ""
                    prompt = ""
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear")
            }

            // Submit button
            Button(
                onClick = {
                    if (title.isNotBlank() && prompt.isNotBlank()) {
                        // Re-attach <user> block for ViewModel extraction
                        val fullPrompt = if (userTagBlock.isNotEmpty()) "$prompt\n$userTagBlock" else prompt

                        // Save as last prompt (persistent) - include <user> block
                        prefs.edit()
                            .putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
                            .putString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, fullPrompt)
                            .apply()

                        // Save to prompt history
                        val settingsPrefs = SettingsPreferences(prefs, context.filesDir)
                        settingsPrefs.savePromptToHistory(title, fullPrompt)

                        viewModel.showGenericAiAgentSelection(title, fullPrompt)
                    }
                },
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6)
                )
            ) {
                Text("Submit", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title field
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            placeholder = { Text("Enter a title for the report") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444),
                focusedLabelColor = Color(0xFF8B5CF6),
                unfocusedLabelColor = Color.Gray,
                cursorColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Prompt field
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("AI Prompt") },
            placeholder = { Text("Enter your prompt for the AI...") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            minLines = 10,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444),
                focusedLabelColor = Color(0xFF8B5CF6),
                unfocusedLabelColor = Color.Gray,
                cursorColor = Color.White
            )
        )

    }
}

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
        savedAgentIds = viewModel.loadAiReportAgents(),
        savedFlockIds = viewModel.loadAiReportFlocks(),
        savedSwarmIds = viewModel.loadAiReportSwarms(),
        savedModelIds = viewModel.loadAiReportModels(),
        onGenerate = { combinedAgentIds, directAgentIds, selectedFlockIds, selectedSwarmIds, directModelIds, paramsIds, reportType ->
            viewModel.saveAiReportAgents(directAgentIds)
            viewModel.saveAiReportFlocks(selectedFlockIds)
            viewModel.saveAiReportSwarms(selectedSwarmIds)
            viewModel.saveAiReportModels(directModelIds)
            viewModel.generateGenericAiReports(combinedAgentIds, selectedSwarmIds, directModelIds, paramsIds, reportType)
        },
        onStop = { viewModel.stopGenericAiReports() },
        onShare = { shareGenericAiReports(context, uiState) },
        onOpenInBrowser = {
            // Use the stored AI-REPORT object to generate HTML on demand
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
 * Shows agent selection first, then progress and results.
 */
// Selection mode for report generation
private enum class ReportSelectionMode { FLOCKS, AGENTS, SWARMS, MODELS }

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
    return FormattedPricing("${fmt(input)}/${fmt(output)}", pricing.source == "default")
}

@Composable
fun AiReportsScreen(
    uiState: AiUiState,
    savedAgentIds: Set<String>,
    savedFlockIds: Set<String>,
    savedSwarmIds: Set<String> = emptySet(),
    savedModelIds: Set<String> = emptySet(),
    onGenerate: (Set<String>, Set<String>, Set<String>, Set<String>, Set<String>, List<String>, com.ai.data.ReportType) -> Unit,  // combinedAgentIds, directAgentIds, flockIds, swarmIds, directModelIds, paramsIds, reportType
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

    // Selection mode: Flocks, Agents, or Swarms
    var selectionMode by remember { mutableStateOf(ReportSelectionMode.FLOCKS) }

    // Search query
    var searchQuery by remember { mutableStateOf("") }

    // Filter agents to only those with an active provider
    val allConfiguredAgents = uiState.aiSettings.getConfiguredAgents()
    val configuredAgents = allConfiguredAgents.filter {
        uiState.aiSettings.isProviderActive(it.provider)
    }

    // Pre-resolve external selections for pre-selecting in the UI
    val externalResolvedAgentIds = remember(uiState.externalAgentNames) {
        if (uiState.externalAgentNames.isEmpty()) emptySet()
        else uiState.externalAgentNames.mapNotNull { name ->
            configuredAgents.find { it.name.equals(name, ignoreCase = true) }?.id
        }.toSet()
    }
    val externalResolvedFlockIds = remember(uiState.externalFlockNames) {
        if (uiState.externalFlockNames.isEmpty()) emptySet()
        else uiState.externalFlockNames.mapNotNull { name ->
            uiState.aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }?.id
        }.toSet()
    }
    val externalResolvedSwarmIds = remember(uiState.externalSwarmNames) {
        if (uiState.externalSwarmNames.isEmpty()) emptySet()
        else uiState.externalSwarmNames.mapNotNull { name ->
            uiState.aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }?.id
        }.toSet()
    }
    val externalResolvedModelIds = remember(uiState.externalModelSpecs) {
        if (uiState.externalModelSpecs.isEmpty()) emptySet()
        else uiState.externalModelSpecs.mapNotNull { spec ->
            val parts = spec.split("/", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val provider = com.ai.data.AiService.entries.find {
                it.id.equals(parts[0].trim(), ignoreCase = true) ||
                it.displayName.equals(parts[0].trim(), ignoreCase = true)
            } ?: return@mapNotNull null
            "swarm:${provider.id}:${parts[1].trim()}"
        }.toSet()
    }

    // Flock selection state (pre-select external flocks if present)
    val flocks = uiState.aiSettings.flocks
    val validFlockIds = flocks.map { it.id }.toSet()
    val validSavedFlocks = savedFlockIds.filter { it in validFlockIds }.toSet()
    var selectedFlockIds by remember {
        mutableStateOf(
            if (externalResolvedFlockIds.isNotEmpty()) externalResolvedFlockIds
            else validSavedFlocks
        )
    }

    // Swarm selection state (pre-select external swarms if present)
    val swarms = uiState.aiSettings.swarms
    val validSwarmIds = swarms.map { it.id }.toSet()
    val validSavedSwarms = savedSwarmIds.filter { it in validSwarmIds }.toSet()
    var selectedSwarmIds by remember {
        mutableStateOf(
            if (externalResolvedSwarmIds.isNotEmpty()) externalResolvedSwarmIds
            else validSavedSwarms
        )
    }

    // Direct model selection state (pre-select external models if present)
    var directlySelectedModelIds by remember {
        mutableStateOf(
            if (externalResolvedModelIds.isNotEmpty()) externalResolvedModelIds
            else savedModelIds
        )
    }

    // Direct agent selection state (pre-select external agents if present)
    val validAgentIds = configuredAgents.map { it.id }.toSet()
    val validSavedAgents = savedAgentIds.filter { it in validAgentIds }.toSet()
    var directlySelectedAgentIds by remember {
        mutableStateOf(
            if (externalResolvedAgentIds.isNotEmpty()) externalResolvedAgentIds
            else validSavedAgents
        )
    }

    // Get agents from selected flocks (only active providers)
    val flockAgentIds = uiState.aiSettings.getAgentsForFlocks(selectedFlockIds)
        .filter { uiState.aiSettings.isProviderActive(it.provider) }
        .map { it.id }.toSet()

    // Get members from selected swarms (only active providers)
    val swarmMembers = uiState.aiSettings.getMembersForSwarms(selectedSwarmIds)
        .filter { uiState.aiSettings.isProviderActive(it.provider) }

    // Get synthetic IDs for swarm members (to check which models are already selected via swarm)
    val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()

    // Combined unique agent IDs (from flocks + directly selected)
    val combinedAgentIds = flockAgentIds + directlySelectedAgentIds

    // Combined model IDs (from swarms + directly selected, avoiding duplicates)
    val combinedModelIds = swarmMemberIds + directlySelectedModelIds.filter { it !in swarmMemberIds }

    // Total worker count (agents + all model selections)
    val totalWorkers = combinedAgentIds.size + combinedModelIds.size

    // Parameters preset selection for the report (multi-select)
    var selectedParametersIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // Auto-generate when external API selections + type are present
    // Resolves <agent>, <flock>, <swarm>, <model> names to IDs, deduplicates, and triggers generation
    val hasExternalSelections = uiState.externalAgentNames.isNotEmpty() ||
            uiState.externalFlockNames.isNotEmpty() ||
            uiState.externalSwarmNames.isNotEmpty() ||
            uiState.externalModelSpecs.isNotEmpty()
    var externalAutoGenerated by remember { mutableStateOf(false) }
    LaunchedEffect(hasExternalSelections, uiState.externalReportType) {
        if (hasExternalSelections && uiState.externalReportType != null && !uiState.externalSelect && !isGenerating && !externalAutoGenerated) {
            externalAutoGenerated = true
            val aiSettings = uiState.aiSettings

            // Resolve agent names to IDs (case-insensitive)
            val resolvedAgentIds = uiState.externalAgentNames.mapNotNull { name ->
                configuredAgents.find { it.name.equals(name, ignoreCase = true) }?.id
            }.toSet()

            // Resolve flock names to IDs (case-insensitive)
            val resolvedFlockIds = uiState.externalFlockNames.mapNotNull { name ->
                aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }?.id
            }.toSet()

            // Expand flock agent IDs
            val flockExpandedAgentIds = aiSettings.getAgentsForFlocks(resolvedFlockIds)
                .filter { aiSettings.isProviderActive(it.provider) }
                .map { it.id }.toSet()

            // Resolve swarm names to IDs (case-insensitive)
            val resolvedSwarmIds = uiState.externalSwarmNames.mapNotNull { name ->
                aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }?.id
            }.toSet()

            // Get swarm member synthetic IDs for deduplication
            val resolvedSwarmMemberIds = aiSettings.getMembersForSwarms(resolvedSwarmIds)
                .filter { aiSettings.isProviderActive(it.provider) }
                .map { "swarm:${it.provider.id}:${it.model}" }.toSet()

            // Parse <model>provider/model</model> specs into synthetic IDs
            val resolvedDirectModelIds = uiState.externalModelSpecs.mapNotNull { spec ->
                val parts = spec.split("/", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val providerName = parts[0].trim()
                val modelName = parts[1].trim()
                // Match provider by ID or displayName (case-insensitive)
                val provider = com.ai.data.AiService.entries.find {
                    it.id.equals(providerName, ignoreCase = true) ||
                    it.displayName.equals(providerName, ignoreCase = true)
                } ?: return@mapNotNull null
                "swarm:${provider.id}:$modelName"
            }.toSet()

            // Deduplicate: remove direct models already covered by swarms
            val uniqueDirectModelIds = resolvedDirectModelIds.filter { it !in resolvedSwarmMemberIds }.toSet()

            // Combined agent IDs (from flocks + directly resolved agents, deduplicated)
            val allAgentIds = flockExpandedAgentIds + resolvedAgentIds

            // Determine report type
            val reportType = if (uiState.externalReportType.equals("Table", ignoreCase = true))
                com.ai.data.ReportType.TABLE else com.ai.data.ReportType.CLASSIC

            // Also deduplicate agents that appear both directly and via flock
            // (Set union already handles this)

            if (allAgentIds.isNotEmpty() || resolvedSwarmIds.isNotEmpty() || uniqueDirectModelIds.isNotEmpty()) {
                onGenerate(allAgentIds, resolvedAgentIds, resolvedFlockIds, resolvedSwarmIds, uniqueDirectModelIds, emptyList(), reportType)
            }
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
                selectionMode == ReportSelectionMode.FLOCKS -> "Select Flock(s)"
                selectionMode == ReportSelectionMode.AGENTS -> "Select Agent(s)"
                selectionMode == ReportSelectionMode.SWARMS -> "Select Swarm(s)"
                else -> "Select Model(s)"
            },
            onBackClick = if (isComplete) onResetReports else onDismiss,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (!isGenerating) {
            // Action row: Parameters + Clear (left), Generate (right)
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
                        if (selectedParametersIds.isNotEmpty()) "⚙ Parameters" else "Parameters",
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

                Spacer(modifier = Modifier.width(6.dp))

                // Clear button
                Button(
                    onClick = {
                        when (selectionMode) {
                            ReportSelectionMode.FLOCKS -> selectedFlockIds = emptySet()
                            ReportSelectionMode.AGENTS -> directlySelectedAgentIds = emptySet()
                            ReportSelectionMode.SWARMS -> selectedSwarmIds = emptySet()
                            ReportSelectionMode.MODELS -> directlySelectedModelIds = emptySet()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B5CF6)
                    )
                ) {
                    Text("Clear", fontSize = 13.sp, maxLines = 1)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Report type selection popup state
                var showReportTypeDialog by remember { mutableStateOf(false) }

                // Generate button (right-aligned) - opens report type popup (or auto-selects for external intent)
                Button(
                    onClick = {
                        val extType = uiState.externalReportType
                        if (extType != null) {
                            val reportType = if (extType.equals("Table", ignoreCase = true))
                                com.ai.data.ReportType.TABLE else com.ai.data.ReportType.CLASSIC
                            onGenerate(combinedAgentIds, directlySelectedAgentIds, selectedFlockIds, selectedSwarmIds, directlySelectedModelIds, selectedParametersIds, reportType)
                        } else {
                            showReportTypeDialog = true
                        }
                    },
                    enabled = totalWorkers > 0,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("Generate ($totalWorkers)", fontSize = 13.sp, maxLines = 1)
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
                                        onGenerate(combinedAgentIds, directlySelectedAgentIds, selectedFlockIds, selectedSwarmIds, directlySelectedModelIds, selectedParametersIds, com.ai.data.ReportType.CLASSIC)
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
                                        onGenerate(combinedAgentIds, directlySelectedAgentIds, selectedFlockIds, selectedSwarmIds, directlySelectedModelIds, selectedParametersIds, com.ai.data.ReportType.TABLE)
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

            // Mode toggle buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { selectionMode = ReportSelectionMode.FLOCKS },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectionMode == ReportSelectionMode.FLOCKS) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                ) {
                    Text("Flocks", fontSize = 13.sp)
                }
                Button(
                    onClick = { selectionMode = ReportSelectionMode.AGENTS },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectionMode == ReportSelectionMode.AGENTS) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                ) {
                    Text("Agents", fontSize = 13.sp)
                }
                Button(
                    onClick = { selectionMode = ReportSelectionMode.SWARMS },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectionMode == ReportSelectionMode.SWARMS) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                ) {
                    Text("Swarms", fontSize = 13.sp)
                }
                Button(
                    onClick = { selectionMode = ReportSelectionMode.MODELS },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectionMode == ReportSelectionMode.MODELS) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                ) {
                    Text("Models", fontSize = 13.sp)
                }
            }

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                placeholder = { Text(when (selectionMode) {
                    ReportSelectionMode.FLOCKS -> "Search flocks..."
                    ReportSelectionMode.AGENTS -> "Search agents..."
                    ReportSelectionMode.SWARMS -> "Search swarms..."
                    ReportSelectionMode.MODELS -> "Search models..."
                }) },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", color = Color.Gray)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    when (selectionMode) {
                        ReportSelectionMode.FLOCKS -> {
                            // Flock selection mode
                            val filteredFlocks = flocks
                                .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
                                .sortedWith(compareByDescending<AiFlock> { it.id in selectedFlockIds }.thenBy { it.name.lowercase() })
                            if (flocks.isEmpty()) {
                                Text(
                                    text = "No AI flocks configured.",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else if (filteredFlocks.isEmpty()) {
                                Text(
                                    text = "No flocks match \"$searchQuery\"",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else {
                                filteredFlocks.forEach { flock ->
                                    val flockAgentsList = uiState.aiSettings.getAgentsForFlock(flock)
                                        .filter { uiState.aiSettings.isProviderActive(it.provider) }
                                    val flockAgentIdsList = flockAgentsList.map { it.id }.toSet()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (flock.id in selectedFlockIds) {
                                                    selectedFlockIds = selectedFlockIds - flock.id
                                                    directlySelectedAgentIds = directlySelectedAgentIds - flockAgentIdsList
                                                } else {
                                                    selectedFlockIds = selectedFlockIds + flock.id
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = flock.id in selectedFlockIds,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    selectedFlockIds = selectedFlockIds + flock.id
                                                } else {
                                                    selectedFlockIds = selectedFlockIds - flock.id
                                                    directlySelectedAgentIds = directlySelectedAgentIds - flockAgentIdsList
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Text(
                                            text = "${flock.name} (${flockAgentsList.size})",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        ReportSelectionMode.AGENTS -> {
                            // Agent selection mode - show all agents, but flock agents are locked
                            val filteredAgents = configuredAgents
                                .filter { agent ->
                                    searchQuery.isBlank() ||
                                    agent.name.contains(searchQuery, ignoreCase = true) ||
                                    agent.provider.displayName.contains(searchQuery, ignoreCase = true) ||
                                    agent.model.contains(searchQuery, ignoreCase = true)
                                }
                                .sortedWith(compareByDescending<AiAgent> { it.id in flockAgentIds || it.id in directlySelectedAgentIds }.thenBy { it.name.lowercase() })
                            if (configuredAgents.isEmpty()) {
                                Text(
                                    text = "No AI agents configured.",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else if (filteredAgents.isEmpty()) {
                                Text(
                                    text = "No agents match \"$searchQuery\"",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else {
                                filteredAgents.forEach { agent ->
                                    val isFromFlock = agent.id in flockAgentIds
                                    val isChecked = isFromFlock || agent.id in directlySelectedAgentIds
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (isFromFlock) Modifier
                                                else Modifier.clickable {
                                                    directlySelectedAgentIds = if (agent.id in directlySelectedAgentIds) {
                                                        directlySelectedAgentIds - agent.id
                                                    } else {
                                                        directlySelectedAgentIds + agent.id
                                                    }
                                                }
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = if (isFromFlock) null else { checked ->
                                                directlySelectedAgentIds = if (checked) {
                                                    directlySelectedAgentIds + agent.id
                                                } else {
                                                    directlySelectedAgentIds - agent.id
                                                }
                                            },
                                            enabled = !isFromFlock,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Text(
                                            text = agent.name,
                                            color = if (isFromFlock) Color(0xFF888888) else Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val pricing = formatPricingPerMillion(context, agent.provider, agent.model)
                                        Text(
                                            text = pricing.text,
                                            color = if (pricing.isDefault) Color(0xFF666666) else Color(0xFFFF6B6B),
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        ReportSelectionMode.SWARMS -> {
                            // Swarm selection mode
                            val filteredSwarms = swarms
                                .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
                                .sortedWith(compareByDescending<AiSwarm> { it.id in selectedSwarmIds }.thenBy { it.name.lowercase() })
                            if (swarms.isEmpty()) {
                                Text(
                                    text = "No AI swarms configured.",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else if (filteredSwarms.isEmpty()) {
                                Text(
                                    text = "No swarms match \"$searchQuery\"",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else {
                                filteredSwarms.forEach { swarm ->
                                    val swarmMembersList = swarm.members
                                        .filter { uiState.aiSettings.isProviderActive(it.provider) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedSwarmIds = if (swarm.id in selectedSwarmIds) {
                                                    selectedSwarmIds - swarm.id
                                                } else {
                                                    selectedSwarmIds + swarm.id
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = swarm.id in selectedSwarmIds,
                                            onCheckedChange = { checked ->
                                                selectedSwarmIds = if (checked) {
                                                    selectedSwarmIds + swarm.id
                                                } else {
                                                    selectedSwarmIds - swarm.id
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Text(
                                            text = "${swarm.name} (${swarmMembersList.size})",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        ReportSelectionMode.MODELS -> {
                            // Models selection mode - select provider/model combinations directly
                            // Build list of all available provider/model combinations (active providers only)
                            val allProviderModels = uiState.aiSettings.getActiveServices()
                                .flatMap { provider ->
                                    val modelsForProvider = uiState.aiSettings.getModels(provider)
                                    modelsForProvider.map { model -> provider to model }
                                }

                            val filteredModels = allProviderModels
                                .filter { (provider, model) ->
                                    searchQuery.isBlank() ||
                                    provider.displayName.contains(searchQuery, ignoreCase = true) ||
                                    model.contains(searchQuery, ignoreCase = true)
                                }
                                .sortedWith(compareBy({ it.first.displayName.lowercase() }, { it.second.lowercase() }))

                            // Sort selected/swarm items to top
                            val sortedModels = filteredModels
                                .sortedWith(compareByDescending<Pair<com.ai.data.AiService, String>> {
                                    val synId = "swarm:${it.first.id}:${it.second}"
                                    synId in swarmMemberIds || synId in directlySelectedModelIds
                                }.thenBy { it.first.displayName.lowercase() }.thenBy { it.second.lowercase() })

                            if (allProviderModels.isEmpty()) {
                                Text(
                                    text = "No models available.",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else if (filteredModels.isEmpty()) {
                                Text(
                                    text = "No models match \"$searchQuery\"",
                                    color = Color(0xFFAAAAAA)
                                )
                            } else {
                                sortedModels.forEach { (provider, model) ->
                                    val syntheticId = "swarm:${provider.id}:$model"
                                    val isFromSwarm = syntheticId in swarmMemberIds
                                    val isChecked = isFromSwarm || syntheticId in directlySelectedModelIds

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (isFromSwarm) Modifier
                                                else Modifier.clickable {
                                                    directlySelectedModelIds = if (syntheticId in directlySelectedModelIds) {
                                                        directlySelectedModelIds - syntheticId
                                                    } else {
                                                        directlySelectedModelIds + syntheticId
                                                    }
                                                }
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = if (isFromSwarm) null else { checked ->
                                                directlySelectedModelIds = if (checked) {
                                                    directlySelectedModelIds + syntheticId
                                                } else {
                                                    directlySelectedModelIds - syntheticId
                                                }
                                            },
                                            enabled = !isFromSwarm,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Text(
                                            text = if (isFromSwarm) "${provider.displayName} (via swarm) " else "${provider.displayName} ",
                                            fontSize = 11.sp,
                                            color = if (isFromSwarm) Color(0xFF666666) else Color(0xFFAAAAAA),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = model,
                                            fontSize = 13.sp,
                                            color = if (isFromSwarm) Color(0xFF888888) else Color.White,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val pricing = formatPricingPerMillion(context, provider, model)
                                        Text(
                                            text = pricing.text,
                                            color = if (pricing.isDefault) Color(0xFF666666) else Color(0xFFFF6B6B),
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                    }
                                }
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
                                        Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    else -> {
                                        Text("✗", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                            Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                        else -> {
                                            Text("✗", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

/**
 * Screen for configuring advanced parameters for a report.
 * These parameters override individual agent parameters for this specific report.
 */
@Composable
fun ReportAdvancedParametersScreen(
    currentParameters: AiAgentParameters?,
    onApply: (AiAgentParameters?) -> Unit,
    onBack: () -> Unit
) {
    // Local state for each parameter
    var temperature by remember { mutableStateOf(currentParameters?.temperature?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(currentParameters?.maxTokens?.toString() ?: "") }
    var topP by remember { mutableStateOf(currentParameters?.topP?.toString() ?: "") }
    var topK by remember { mutableStateOf(currentParameters?.topK?.toString() ?: "") }
    var frequencyPenalty by remember { mutableStateOf(currentParameters?.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember { mutableStateOf(currentParameters?.presencePenalty?.toString() ?: "") }
    var systemPrompt by remember { mutableStateOf(currentParameters?.systemPrompt ?: "") }
    var seed by remember { mutableStateOf(currentParameters?.seed?.toString() ?: "") }
    var searchEnabled by remember { mutableStateOf(currentParameters?.searchEnabled ?: false) }
    var returnCitations by remember { mutableStateOf(currentParameters?.returnCitations ?: true) }
    var searchRecency by remember { mutableStateOf(currentParameters?.searchRecency ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Advanced Parameters",
            onBackClick = onBack,
            onAiClick = onBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Apply button at top
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    // Build parameters object (null values mean no override)
                    val params = AiAgentParameters(
                        temperature = temperature.toFloatOrNull(),
                        maxTokens = maxTokens.toIntOrNull(),
                        topP = topP.toFloatOrNull(),
                        topK = topK.toIntOrNull(),
                        frequencyPenalty = frequencyPenalty.toFloatOrNull(),
                        presencePenalty = presencePenalty.toFloatOrNull(),
                        systemPrompt = systemPrompt.takeIf { it.isNotBlank() },
                        seed = seed.toIntOrNull(),
                        searchEnabled = searchEnabled,
                        returnCitations = returnCitations,
                        searchRecency = searchRecency.takeIf { it.isNotBlank() }
                    )
                    // Only return non-empty parameters
                    val hasAnyValue = temperature.isNotBlank() || maxTokens.isNotBlank() ||
                            topP.isNotBlank() || topK.isNotBlank() ||
                            frequencyPenalty.isNotBlank() || presencePenalty.isNotBlank() ||
                            systemPrompt.isNotBlank() || seed.isNotBlank() ||
                            searchEnabled || !returnCitations || searchRecency.isNotBlank()
                    onApply(if (hasAnyValue) params else null)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("Apply")
            }
            OutlinedButton(
                onClick = {
                    // Clear all parameters
                    temperature = ""
                    maxTokens = ""
                    topP = ""
                    topK = ""
                    frequencyPenalty = ""
                    presencePenalty = ""
                    systemPrompt = ""
                    seed = ""
                    searchEnabled = false
                    returnCitations = true
                    searchRecency = ""
                    onApply(null)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear all")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Parameters form
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "These parameters override individual agent settings for this report only.",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Temperature
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Temperature (0.0 - 2.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Max Tokens
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("Max tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Top P
                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text("Top P (0.0 - 1.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Top K
                OutlinedTextField(
                    value = topK,
                    onValueChange = { topK = it },
                    label = { Text("Top K") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Frequency Penalty
                OutlinedTextField(
                    value = frequencyPenalty,
                    onValueChange = { frequencyPenalty = it },
                    label = { Text("Frequency penalty (-2.0 - 2.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Presence Penalty
                OutlinedTextField(
                    value = presencePenalty,
                    onValueChange = { presencePenalty = it },
                    label = { Text("Presence penalty (-2.0 - 2.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Seed
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text("Seed (for reproducibility)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // System Prompt
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Search enabled checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { searchEnabled = !searchEnabled },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = searchEnabled,
                        onCheckedChange = { searchEnabled = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable web search (xAI, Perplexity)")
                }

                // Return citations checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { returnCitations = !returnCitations },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = returnCitations,
                        onCheckedChange = { returnCitations = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Return citations (Perplexity)")
                }

                // Search recency
                OutlinedTextField(
                    value = searchRecency,
                    onValueChange = { searchRecency = it },
                    label = { Text("Search recency (day, week, month, year)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }
        }
    }
}
