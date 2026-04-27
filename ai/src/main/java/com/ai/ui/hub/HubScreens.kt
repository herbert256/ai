package com.ai.ui.hub

import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.data.ApiTracer
import com.ai.data.ReportStorage
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel

@Composable
fun HubScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTraces: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToReportsHub: () -> Unit,
    onNavigateToUsage: () -> Unit,
    onNavigateToChatsHub: () -> Unit,
    onNavigateToAiSetup: () -> Unit,
    onNavigateToHousekeeping: () -> Unit,
    onNavigateToModelSearch: () -> Unit,
    viewModel: AppViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BackHandler { (context as? Activity)?.moveTaskToBack(true) }

    val hasAnyAgent = remember(uiState.aiSettings.agents) { uiState.aiSettings.agents.isNotEmpty() }
    val hasStatisticsData by produceState(initialValue = false, uiState) {
        val sp = SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir)
        value = sp.loadUsageStats().isNotEmpty()
    }
    val hasTraces by produceState(initialValue = false, uiState) {
        value = ApiTracer.getTraceFiles().isNotEmpty()
    }

    val cardHeight = 50.dp
    val cardSpacing = 12.dp
    val cardCount = 9
    val cardsHeight = (cardHeight * cardCount) + (cardSpacing * (cardCount - 1)) + 32.dp

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)
    ) {
        val logoSize = (maxHeight - cardsHeight).coerceIn(100.dp, 220.dp)
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "AI App Logo",
                modifier = Modifier.size(logoSize).offset(y = (-32).dp)
            )
            HubCard(icon = "\uD83D\uDCDD", title = "AI Reports", onClick = onNavigateToReportsHub, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDCAC", title = "AI Chat", onClick = onNavigateToChatsHub, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDDE0", title = "AI Models", onClick = onNavigateToModelSearch, enabled = hasAnyAgent)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDCC8", title = "AI Usage", onClick = onNavigateToUsage, enabled = hasStatisticsData)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83D\uDC1E", title = "AI API Traces", onClick = onNavigateToTraces, enabled = hasTraces)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDD16", title = "AI Setup", onClick = onNavigateToAiSetup)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDDF9", title = "AI Housekeeping", onClick = onNavigateToHousekeeping)
            Spacer(modifier = Modifier.height(32.dp))
            HubCard(icon = "\u2699\uFE0F", title = "Settings", onClick = onNavigateToSettings)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\u2753", title = "Help", onClick = onNavigateToHelp)
        }
    }
}

@Composable
private fun HubCard(icon: String, title: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 26.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else AppColors.TextDim)
        }
    }
}

@Composable
fun ReportsHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSearch: () -> Unit
) {
    val context = LocalContext.current
    val hasPromptHistory = remember {
        SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir).loadPromptHistory().isNotEmpty()
    }
    val hasPreviousReports = remember { ReportStorage.getAllReports(context).isNotEmpty() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "AI Reports", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(24.dp))
        HubCard(icon = "\uD83D\uDCDD", title = "New AI Report", onClick = onNavigateToNewReport)
        Spacer(modifier = Modifier.height(12.dp))
        HubCard(icon = "\uD83D\uDD04", title = "Start with a previous prompt", onClick = onNavigateToPromptHistory, enabled = hasPromptHistory)
        Spacer(modifier = Modifier.height(12.dp))
        HubCard(icon = "\uD83D\uDCDA", title = "View previous reports", onClick = onNavigateToHistory, enabled = hasPreviousReports)
        Spacer(modifier = Modifier.height(12.dp))
        HubCard(icon = "🔎", title = "Semantic search", onClick = onNavigateToSearch, enabled = hasPreviousReports)
    }
}

private val userTagRegex = Regex("""<user>.*?</user>""", RegexOption.DOT_MATCHES_ALL)

@Composable
fun NewReportScreen(
    viewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToReports: () -> Unit = {},
    initialTitle: String = "",
    initialPrompt: String = ""
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    var title by remember {
        mutableStateOf(initialTitle.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: "" })
    }
    val rawPrompt = remember { initialPrompt.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" } }
    val userTagBlock = remember { userTagRegex.find(rawPrompt)?.value ?: "" }
    var prompt by remember { mutableStateOf(rawPrompt.replace(userTagRegex, "").trim()) }
    // (mime, base64) of an optional image attached to the prompt — passed
    // through to every agent in the report.
    var attachedImage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    var useWebSearch by remember { mutableStateOf(false) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    attachedImage = mime to Base64.encodeToString(bytes, Base64.NO_WRAP)
                    attachError = null
                }
            } catch (e: Exception) {
                attachError = "Failed to attach image: ${e.message}"
            }
        }
    }

    LaunchedEffect(uiState.showGenericAgentSelection) {
        if (uiState.showGenericAgentSelection) {
            reportViewModel.dismissGenericAgentSelection()
            onNavigateToReports()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "New AI Report", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { title = ""; prompt = ""; attachedImage = null }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Clear", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }, colors = AppColors.outlinedButtonColors()) {
                Text("📎", fontSize = 16.sp, maxLines = 1, softWrap = false)
            }
            Button(
                onClick = {
                    if (title.isNotBlank() && prompt.isNotBlank()) {
                        val fullPrompt = if (userTagBlock.isNotEmpty()) "$prompt\n$userTagBlock" else prompt
                        prefs.edit().putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
                            .putString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, fullPrompt).apply()
                        SettingsPreferences(prefs, context.filesDir).savePromptToHistory(title, fullPrompt)
                        reportViewModel.showGenericAgentSelection(
                            title, fullPrompt,
                            imageBase64 = attachedImage?.second,
                            imageMime = attachedImage?.first,
                            webSearchTool = useWebSearch
                        )
                    }
                },
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) { Text("Next", fontSize = 16.sp, maxLines = 1, softWrap = false) }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = useWebSearch,
                onClick = { useWebSearch = !useWebSearch },
                label = { Text("🌐 Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Blue.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Blue
                )
            )
        }

        attachError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = AppColors.Red, fontSize = 12.sp)
        }

        attachedImage?.let { (mime, b64) ->
            val bmp = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Image attached (${mime.substringAfter('/')})", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                TextButton(onClick = { attachedImage = null }) { Text("Remove", color = AppColors.Red, fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Title") },
            placeholder = { Text("Enter a title for the report") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it }, label = { Text("AI Prompt") },
            placeholder = { Text("Enter your prompt for the AI...") },
            modifier = Modifier.fillMaxWidth().weight(1f), minLines = 10, colors = AppColors.outlinedFieldColors()
        )
    }
}
