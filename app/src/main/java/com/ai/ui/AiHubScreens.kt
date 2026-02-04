package com.ai.ui

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
import androidx.compose.ui.text.font.FontWeight
import com.ai.R
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
                Text("Next", fontSize = 16.sp)
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
