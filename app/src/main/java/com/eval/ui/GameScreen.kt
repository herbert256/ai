package com.eval.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.eval.data.AiAnalysisResponse
import com.eval.data.AiHistoryManager
import com.eval.data.AiReportType
import com.eval.data.ChessServer
import com.eval.data.PlayerInfo
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Main game screen content composable that handles game display.
 * Uses navigation callbacks for full-screen destinations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreenContent(
    modifier: Modifier = Modifier,
    viewModel: GameViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToHelp: () -> Unit = {},
    onNavigateToTrace: () -> Unit = {},
    onNavigateToRetrieve: () -> Unit = {},
    onNavigateToAi: () -> Unit = {},
    onNavigateToPlayerInfo: () -> Unit = {},
    onNavigateToAiReports: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Show blocking screen if Stockfish is not installed
    if (!uiState.stockfishInstalled) {
        StockfishNotInstalledScreen(
            onExit = {
                (context as? Activity)?.finish()
            },
            onCheckInstalled = {
                viewModel.checkStockfishInstalled()
            },
            onInstalled = {
                viewModel.initializeStockfish()
            }
        )
        return
    }

    // Calculate background color based on game result
    val backgroundColor = remember(uiState.game) {
        val game = uiState.game
        if (game == null) {
            Color(0xFF2A2A2A)  // Default dark gray when no game loaded
        } else {
            val searchedUser = viewModel.savedLichessUsername.lowercase()
            val whitePlayerName = game.players.white.user?.name?.lowercase() ?: ""
            val blackPlayerName = game.players.black.user?.name?.lowercase() ?: ""

            // Determine which color the user played
            val userPlayedWhite = searchedUser.isNotEmpty() && searchedUser == whitePlayerName
            val userPlayedBlack = searchedUser.isNotEmpty() && searchedUser == blackPlayerName

            when {
                game.winner == "white" && userPlayedWhite -> Color(0xFF2A4A2A)  // Light green - user won as white
                game.winner == "black" && userPlayedBlack -> Color(0xFF2A4A2A)  // Light green - user won as black
                game.winner == "white" && userPlayedBlack -> Color(0xFF4A2A2A)  // Light red - user lost as black
                game.winner == "black" && userPlayedWhite -> Color(0xFF4A2A2A)  // Light red - user lost as white
                game.winner == null -> Color(0xFF2A3A4A)  // Light blue - draw
                else -> Color(0xFF2A2A2A)  // Default dark gray
            }
        }
    }

    // Keep screen on during Preview and Analyse stages
    val view = LocalView.current
    DisposableEffect(uiState.currentStage) {
        if (uiState.currentStage != AnalysisStage.MANUAL) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Handle full screen mode (using generalSettings.longTapForFullScreen as the state)
    val window = (context as? Activity)?.window
    val isFullScreen = uiState.generalSettings.longTapForFullScreen

    // Track if we've ever entered full screen to avoid modifying window on startup
    var hasBeenFullScreen by remember { mutableStateOf(false) }

    LaunchedEffect(isFullScreen) {
        if (window != null) {
            if (isFullScreen) {
                // Enter full screen mode
                hasBeenFullScreen = true
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else if (hasBeenFullScreen) {
                // Only restore normal mode if we were previously in full screen
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Restore normal mode when composable is disposed (only if we modified it)
    DisposableEffect(Unit) {
        onDispose {
            if (window != null && hasBeenFullScreen) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Note: Full-screen destinations (Settings, Help, Trace, Retrieve) are now handled via navigation

    // Show share position dialog
    if (uiState.showSharePositionDialog) {
        SharePositionDialog(
            fen = viewModel.getCurrentFen(),
            onCopyFen = { viewModel.copyFenToClipboard(context) },
            onShare = { viewModel.sharePositionAsText(context) },
            onExportPgn = { viewModel.exportAnnotatedPgn(context) },
            onCopyPgn = { viewModel.copyPgnToClipboard(context) },
            onExportGif = { viewModel.exportAsGif(context) },
            onGenerateAiReports = { viewModel.showAiReportsSelectionDialog() },
            onDismiss = { viewModel.hideSharePositionDialog() }
        )
    }

    // Navigate to AI Reports screen when selection dialog is triggered
    LaunchedEffect(uiState.showAiReportsSelectionDialog) {
        if (uiState.showAiReportsSelectionDialog) {
            viewModel.dismissAiReportsSelectionDialog()
            onNavigateToAiReports()
        }
    }

    // Show GIF export progress dialog
    if (uiState.showGifExportDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelGifExport() },
            title = { Text("Exporting GIF", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { uiState.gifExportProgress ?: 0f },
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 6.dp
                    )
                    Text(
                        text = "Creating animated GIF...",
                        color = Color.Gray
                    )
                    Text(
                        text = "${((uiState.gifExportProgress ?: 0f) * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelGifExport() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Navigate to AI Reports screen when player AI reports selection is triggered
    LaunchedEffect(uiState.showPlayerAiReportsSelectionDialog) {
        if (uiState.showPlayerAiReportsSelectionDialog) {
            viewModel.dismissPlayerAiReportsSelectionDialog()
            onNavigateToAiReports()
        }
    }

    // Note: AI screens (AI hub, AI History, New AI Report, AI Reports) are now handled via navigation

    // Show AI analysis screen (full screen)
    if (uiState.showAiAnalysisDialog) {
        AiAnalysisScreen(
            serviceName = uiState.aiAnalysisServiceName,
            result = uiState.aiAnalysisResult,
            isLoading = uiState.aiAnalysisLoading,
            uiState = uiState,
            onDismiss = { viewModel.dismissAiAnalysisDialog() }
        )
        return
    }

    // Show player info screen (triggered by clicking on player names)
    if (uiState.showPlayerInfoScreen) {
        PlayerInfoScreen(
            playerInfo = uiState.playerInfo,
            isLoading = uiState.playerInfoLoading,
            error = uiState.playerInfoError,
            games = uiState.playerGames,
            gamesLoading = uiState.playerGamesLoading,
            currentPage = uiState.playerGamesPage,
            pageSize = uiState.playerGamesPageSize,
            hasMoreGames = uiState.playerGamesHasMore,
            onNextPage = { viewModel.nextPlayerGamesPage() },
            onPreviousPage = { viewModel.previousPlayerGamesPage() },
            onGameSelected = { game -> viewModel.selectGameFromPlayerInfo(game) },
            onAiReportsClick = {
                uiState.playerInfo?.let { info ->
                    val serverName = if (uiState.playerInfoError != null) {
                        null
                    } else {
                        "lichess.org"
                    }
                    viewModel.showPlayerAiReportsSelectionDialog(info.username, serverName, info)
                }
            },
            hasAiApiKeys = uiState.aiSettings.hasAnyApiKey(),
            onDismiss = { viewModel.dismissPlayerInfo() }
        )
        return
    }

    // Show game selection screen (full screen, outside scrollable column)
    if (uiState.showGameSelection && uiState.gameList.isNotEmpty()) {
        GameSelectionScreen(
            games = uiState.gameList,
            username = uiState.gameSelectionUsername,
            server = uiState.gameSelectionServer,
            onSelectGame = { viewModel.selectGame(it) },
            onDismiss = { viewModel.dismissGameSelection() }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        viewModel.toggleFullScreen()
                    }
                )
            }
    ) {
        // Title bar - hidden in full screen mode
        if (!isFullScreen) {
            EvalTitleBar(
                onEvalClick = { viewModel.clearGame() },
                leftContent = {
                    // Menu icon - navigate to retrieve
                    TitleBarIcon(
                        icon = "≡",
                        onClick = {
                            if (uiState.game != null) {
                                viewModel.clearGame()
                            }
                            onNavigateToRetrieve()
                        },
                        fontSize = 44,
                        offsetY = -12
                    )
                    // Reload last game (only when game is loaded)
                    if (uiState.game != null) {
                        TitleBarIcon(
                            icon = "↻",
                            onClick = { viewModel.reloadLastGame() },
                            fontSize = 44,
                            size = 52,
                            offsetY = -12
                        )
                    }
                    // Debug trace icon (only when developer mode and tracking are enabled)
                    if (uiState.generalSettings.developerMode && uiState.generalSettings.trackApiCalls) {
                        TitleBarIcon(
                            icon = "\uD83D\uDC1E",
                            onClick = { onNavigateToTrace() },
                            fontSize = 24
                        )
                    }
                    // AI prompt icon
                    TitleBarIcon(
                        icon = "🤖",
                        onClick = { onNavigateToAi() },
                        fontSize = 24
                    )
                    // Settings icon
                    TitleBarIcon(
                        icon = "⚙",
                        onClick = { onNavigateToSettings() }
                    )
                    // Help icon
                    TitleBarIcon(
                        icon = "?",
                        onClick = { onNavigateToHelp() }
                    )
                }
            )
        }

        // Stage indicator - only show during Preview and Analyse stages, hidden in full screen mode
        if (uiState.game != null && uiState.currentStage != AnalysisStage.MANUAL && !isFullScreen) {
            val isPreviewStage = uiState.currentStage == AnalysisStage.PREVIEW
            val stageText = if (isPreviewStage) "Preview stage" else "Analyse stage"
            val stageColor = if (isPreviewStage) Color(0xFFFFAA00) else Color(0xFF6B9BFF)

            if (isPreviewStage) {
                // Preview stage: not clickable, just a label
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-8).dp)
                        .padding(vertical = 4.dp)
                        .background(stageColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stageText,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = stageColor
                    )
                }
            } else {
                // Analyse stage: clickable, enters Manual stage at biggest change
                Button(
                    onClick = { viewModel.enterManualStageAtBiggestChange() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = stageColor.copy(alpha = 0.2f),
                        contentColor = stageColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-8).dp)
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "Analysis running - tap to end",
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        color = Color.Yellow
                    )
                }
            }
        }

        // Main view when no game is loaded - show logo centered on screen
        if (uiState.game == null) {
            // Center the logo vertically, offset 10% up from center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Box(
                    modifier = Modifier.offset(y = (-60).dp)
                ) {
                    EvalLogo()
                }
            }

            // Error message
            if (uiState.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = uiState.errorMessage ?: "Unknown error",
                        color = Color.White,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

        }

        // Game content
        if (uiState.game != null) {
            GameContent(uiState = uiState, viewModel = viewModel)
        }
    }
}

/**
 * Blocking screen shown when Stockfish is not installed.
 * User must install "Stockfish 17.1 Chess Engine" from Google Play Store.
 * Automatically checks every 2 seconds if Stockfish has been installed.
 */
@Composable
fun StockfishNotInstalledScreen(
    onExit: () -> Unit,
    onCheckInstalled: () -> Boolean,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val playStoreUrl = "https://play.google.com/store/apps/details?id=com.stockfish141"

    // Check every 2 seconds if Stockfish has been installed
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (onCheckInstalled()) {
                onInstalled()
                break
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Stockfish Not Installed",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "This app requires the Stockfish chess engine to analyze games.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )

                // Clickable link to Play Store
                val annotatedText = buildAnnotatedString {
                    append("Please install ")
                    pushStringAnnotation(tag = "URL", annotation = playStoreUrl)
                    withStyle(style = SpanStyle(
                        color = Color(0xFF6B9BFF),
                        textDecoration = TextDecoration.Underline
                    )) {
                        append("Stockfish 17.1 Chess Engine")
                    }
                    pop()
                    append(" from the Google Play Store.")
                }

                ClickableText(
                    text = annotatedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    ),
                    onClick = { offset ->
                        annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it.item))
                                context.startActivity(intent)
                            }
                    }
                )

                Text(
                    text = "The app will start automatically once Stockfish is installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Exit")
                }
            }
        }
    }
}

/**
 * Eval logo displayed on the main screen when no game is loaded.
 * Features a stylized chess-themed design with light green background.
 */
@Composable
fun EvalLogo() {
    Card(
        modifier = Modifier
            .padding(horizontal = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF3A5A3A)  // Light green background
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp, horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top chess pieces - black pieces
            Text(
                text = "\u265A \u265B \u265C",
                fontSize = 56.sp,
                color = Color(0xFF1A1A1A),
                letterSpacing = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main title
            Text(
                text = "Eval",
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 6.sp
            )

            // Subtitle
            Text(
                text = "Chess Game Analyser",
                fontSize = 16.sp,
                color = Color(0xFFCCCCCC),
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom chess pieces - white pieces
            Text(
                text = "\u2657 \u2658 \u2659",
                fontSize = 56.sp,
                color = Color.White,
                letterSpacing = 12.sp
            )
        }
    }
}

/**
 * Opens AI reports from multiple services in Chrome with tabbed interface.
 */
internal fun openAiReportsInChrome(context: android.content.Context, uiState: GameUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertAiReportsToHtml(uiState, appVersion)

        // Save to AI history
        AiHistoryManager.saveReport(html, AiReportType.GAME_ANALYSIS)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val htmlFile = java.io.File(cacheDir, "ai_reports.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.android.chrome")
        }

        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            intent.setPackage(null)
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open in browser: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Shares the AI Reports HTML file using Android share sheet.
 */
internal fun shareAiReports(context: android.content.Context, uiState: GameUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertAiReportsToHtml(uiState, appVersion)

        // Save to AI history
        AiHistoryManager.saveReport(html, AiReportType.GAME_ANALYSIS)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val game = uiState.game
        val whiteName = game?.players?.white?.user?.name ?: "White"
        val blackName = game?.players?.black?.user?.name ?: "Black"

        val htmlFile = java.io.File(cacheDir, "ai_reports.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, "AI Analysis Report - $whiteName vs $blackName")
            putExtra(Intent.EXTRA_TEXT, "Chess game AI analysis report for $whiteName vs $blackName.\n\nOpen the attached HTML file in a browser to view the interactive report.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share AI Report"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to share: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Opens the player AI reports HTML in Chrome browser.
 */
internal fun openPlayerAiReportsInChrome(context: android.content.Context, uiState: GameUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertPlayerAiReportsToHtml(uiState, appVersion)

        // Save to AI history - server_player if server is set, otherwise other_player
        val reportType = if (uiState.playerAiReportsServer != null) {
            AiReportType.SERVER_PLAYER
        } else {
            AiReportType.OTHER_PLAYER
        }
        AiHistoryManager.saveReport(html, reportType)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val htmlFile = java.io.File(cacheDir, "player_ai_reports.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.android.chrome")
        }

        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            intent.setPackage(null)
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open in Chrome: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Shares the player AI reports HTML via Android share sheet.
 */
internal fun sharePlayerAiReports(context: android.content.Context, uiState: GameUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertPlayerAiReportsToHtml(uiState, appVersion)

        // Save to AI history - server_player if server is set, otherwise other_player
        val reportType = if (uiState.playerAiReportsServer != null) {
            AiReportType.SERVER_PLAYER
        } else {
            AiReportType.OTHER_PLAYER
        }
        AiHistoryManager.saveReport(html, reportType)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val playerName = uiState.playerAiReportsPlayerName

        val htmlFile = java.io.File(cacheDir, "player_ai_reports.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - Player: $playerName")
            putExtra(Intent.EXTRA_TEXT, "AI analysis report for chess player: $playerName.\n\nOpen the attached HTML file in a browser to view the report.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share Player AI Report"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to share: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Converts player AI analysis results from multiple services to a tabbed HTML document.
 */
private fun convertPlayerAiReportsToHtml(uiState: GameUiState, appVersion: String): String {
    val generatedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    val playerName = uiState.playerAiReportsPlayerName
    val server = uiState.playerAiReportsServer
    val playerInfo = uiState.playerAiReportsPlayerInfo
    val aiSettings = uiState.aiSettings

    // Build player info HTML section
    val playerInfoHtml = buildPlayerInfoHtml(playerInfo, server)

    // Get agents with results, sorted by name
    val agentsWithResults = uiState.playerAiReportsSelectedAgents.mapNotNull { agentId ->
        val agent = aiSettings.getAgentById(agentId)
        val result = uiState.playerAiReportsAgentResults[agentId]
        if (agent != null && result != null) agent to result else null
    }.sortedBy { it.first.name.lowercase() }

    // Generate tabs HTML (show agent name)
    val tabsHtml = agentsWithResults.mapIndexed { index, (agent, _) ->
        val isActive = if (index == 0) "active" else ""
        """<button class="tab-btn $isActive" onclick="showTab('${agent.id}')">${escapeHtml(agent.name)}</button>"""
    }.joinToString("\n")

    // Generate content panels for each agent
    val panelsHtml = agentsWithResults.mapIndexed { index, (agent, result) ->
        val isActive = if (index == 0) "active" else ""
        val providerWithModel = "${agent.provider.displayName} (${agent.model})"
        val content = if (result.isSuccess && result.analysis != null) {
            convertMarkdownContentToHtml(result.analysis)
        } else {
            """<pre class="error">${escapeHtml(result.error ?: "Unknown error")}</pre>"""
        }
        // Get the actual prompt used (with placeholders replaced)
        val promptUsed = result.promptUsed ?: ""
        val promptHtml = if (promptUsed.isNotBlank()) {
            """
            <div class="prompt-section">
                <h3>Prompt Used</h3>
                <pre class="prompt-text">${escapeHtml(promptUsed)}</pre>
            </div>
            """
        } else ""
        """
        <div id="panel-${agent.id}" class="tab-panel $isActive">
            <div class="panel-header">${escapeHtml(providerWithModel)}</div>
            $content
            $promptHtml
        </div>
        """
    }.joinToString("\n")

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI Report - $playerName</title>
    <style>
        * { box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
            padding: 16px;
            max-width: 900px;
            margin: 0 auto;
            background-color: #1a1a1a;
            color: #e0e0e0;
        }
        h1, h2, h3 {
            color: #ffffff;
            margin-top: 1.5em;
            margin-bottom: 0.5em;
        }
        h1 { font-size: 1.8em; border-bottom: 2px solid #8B5CF6; padding-bottom: 8px; }
        h2 { font-size: 1.4em; color: #8B5CF6; margin-top: 1em; }
        h3 { font-size: 1.2em; color: #A78BFA; }
        .panel-header {
            font-size: 2em;
            font-weight: bold;
            color: #FF9800;
            margin-bottom: 0.8em;
            padding-bottom: 8px;
            border-bottom: 2px solid #FF9800;
        }
        p { margin: 1em 0; }
        ul { padding-left: 20px; }
        li { margin: 0.5em 0; }
        strong { color: #ffffff; }
        em { color: #b0b0b0; }

        /* Player info section */
        .player-section {
            background: #242424;
            border-radius: 12px;
            padding: 20px;
            margin-bottom: 24px;
        }
        .player-header {
            display: flex;
            align-items: center;
            gap: 16px;
            margin-bottom: 16px;
        }
        .player-name {
            font-size: 1.8em;
            font-weight: bold;
            color: #fff;
        }
        .player-title {
            background: #FFD700;
            color: #000;
            padding: 4px 12px;
            border-radius: 4px;
            font-weight: bold;
            font-size: 1.1em;
        }
        .server-badge {
            display: inline-block;
            padding: 6px 14px;
            border-radius: 6px;
            color: #fff;
            font-weight: 500;
            margin-bottom: 12px;
        }
        .server-lichess { background: #629924; }
        .server-chesscom { background: #769656; }
        .info-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 12px;
            margin-top: 16px;
        }
        .info-item {
            background: #2d2d2d;
            padding: 12px;
            border-radius: 8px;
        }
        .info-label {
            font-size: 12px;
            color: #888;
            margin-bottom: 4px;
        }
        .info-value {
            font-size: 16px;
            color: #fff;
            font-weight: 500;
        }
        .ratings-section {
            margin-top: 16px;
        }
        .ratings-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            margin-top: 8px;
        }
        .rating-badge {
            background: #3d3d3d;
            padding: 8px 16px;
            border-radius: 8px;
            text-align: center;
        }
        .rating-type {
            font-size: 11px;
            color: #888;
        }
        .rating-value {
            font-size: 18px;
            font-weight: bold;
            color: #64B5F6;
        }
        .stats-section {
            margin-top: 16px;
        }
        .stats-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            margin-top: 8px;
        }
        .stat-badge {
            padding: 8px 16px;
            border-radius: 8px;
            text-align: center;
        }
        .stat-badge.wins { background: rgba(0, 230, 118, 0.2); }
        .stat-badge.losses { background: rgba(244, 67, 54, 0.2); }
        .stat-badge.draws { background: rgba(144, 164, 174, 0.2); }
        .stat-badge.total { background: rgba(100, 181, 246, 0.2); }
        .stat-label { font-size: 11px; color: #888; }
        .stat-value { font-size: 18px; font-weight: bold; }
        .stat-value.wins { color: #00E676; }
        .stat-value.losses { color: #FF5252; }
        .stat-value.draws { color: #90A4AE; }
        .stat-value.total { color: #64B5F6; }

        /* Prompt section */
        .prompt-section {
            margin-top: 24px;
            padding-top: 16px;
            border-top: 1px solid #444;
        }
        .prompt-section h3 {
            color: #888;
            font-size: 1em;
            margin-bottom: 8px;
        }
        .prompt-text {
            background: #1a1a1a;
            border: 1px solid #333;
            border-radius: 6px;
            padding: 12px;
            font-family: monospace;
            font-size: 11px;
            color: #aaa;
            white-space: pre-wrap;
            word-wrap: break-word;
            line-height: 1.5;
        }

        /* Tabs */
        .tabs-container {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            margin: 24px 0 16px 0;
            border-bottom: 2px solid #333;
            padding-bottom: 8px;
        }
        .tab-btn {
            padding: 10px 20px;
            background: #2d2d2d;
            border: none;
            border-radius: 8px 8px 0 0;
            color: #888;
            cursor: pointer;
            font-size: 14px;
            font-weight: 500;
            transition: all 0.2s;
        }
        .tab-btn:hover {
            background: #3d3d3d;
            color: #ccc;
        }
        .tab-btn.active {
            background: #8B5CF6;
            color: #fff;
        }

        /* Tab panels */
        .tab-panel {
            display: none;
            padding: 16px 0;
        }
        .tab-panel.active {
            display: block;
        }

        /* Error styling */
        pre.error {
            background: #2d1f1f;
            border: 1px solid #5c2020;
            border-radius: 8px;
            padding: 16px;
            color: #ff6b6b;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: monospace;
            font-size: 13px;
        }

        /* Footer */
        .generated-footer {
            margin-top: 40px;
            padding-top: 16px;
            border-top: 1px solid #444;
            text-align: center;
            color: #666;
            font-size: 12px;
        }
    </style>
</head>
<body>
    <h1>AI Report: $playerName</h1>

    <!-- Player Info Section -->
    $playerInfoHtml

    <!-- AI Analysis Tabs -->
    <div class="tabs-container">
        $tabsHtml
    </div>

    $panelsHtml

    <div class="generated-footer">
        Generated by Eval $appVersion on $generatedDate
    </div>

    <script>
    function showTab(serviceName) {
        document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-panel').forEach(panel => panel.classList.remove('active'));
        document.querySelector('.tab-btn[onclick*="' + serviceName + '"]').classList.add('active');
        document.getElementById('panel-' + serviceName).classList.add('active');
    }
    </script>
</body>
</html>
""".trimIndent()
}

// ============================================================================
// HTML CONVERSION FUNCTIONS
// ============================================================================

// Note: AI screen dialogs (AiScreen, AiHistoryScreen, AiHistoryRow, NewAiReportScreen, etc.)
// have been removed as they are now handled via Navigation.kt routes to AiScreens.kt components.
// The AiAnalysisScreen component has been moved to AiAnalysisScreen.kt.

/**
 * Converts generic AI analysis results to a tabbed HTML document.
 */
internal fun convertGenericAiReportsToHtml(uiState: GameUiState, appVersion: String): String {
    val generatedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    val title = uiState.genericAiPromptTitle
    val aiSettings = uiState.aiSettings

    // Get agents with results, sorted by name
    val agentsWithResults = uiState.genericAiReportsSelectedAgents.mapNotNull { agentId ->
        val agent = aiSettings.getAgentById(agentId)
        val result = uiState.genericAiReportsAgentResults[agentId]
        if (agent != null && result != null) agent to result else null
    }.sortedBy { it.first.name.lowercase() }

    // Get the actual prompt used from the first result
    val promptUsed = agentsWithResults.firstOrNull()?.second?.promptUsed ?: uiState.genericAiPromptText

    // Generate tabs HTML (show agent name)
    val tabsHtml = agentsWithResults.mapIndexed { index, (agent, _) ->
        val isActive = if (index == 0) "active" else ""
        """<button class="tab-btn $isActive" onclick="showTab('${agent.id}')">${escapeHtml(agent.name)}</button>"""
    }.joinToString("\n")

    val developerMode = uiState.generalSettings.developerMode

    // Generate content panels for each agent (no prompt per panel)
    val panelsHtml = agentsWithResults.mapIndexed { index, (agent, result) ->
        val isActive = if (index == 0) "active" else ""
        val providerWithModel = "${agent.provider.displayName} (${agent.model})"
        val content = if (result.isSuccess && result.analysis != null) {
            convertMarkdownContentToHtml(result.analysis)
        } else {
            """<pre class="error">${escapeHtml(result.error ?: "Unknown error")}</pre>"""
        }
        // Build citations HTML if available
        val citationsHtml = if (!result.citations.isNullOrEmpty()) {
            val citationsList = result.citations.map { url ->
                """<li><a href="${escapeHtml(url)}" target="_blank">${escapeHtml(url)}</a></li>"""
            }.joinToString("\n")
            """
            <div class="citations-section">
                <h3>Sources</h3>
                <ol class="citations-list">
                    $citationsList
                </ol>
            </div>
            """
        } else ""
        // Build search results HTML if available
        val searchResultsHtml = if (!result.searchResults.isNullOrEmpty()) {
            val searchResultsList = result.searchResults.mapNotNull { sr ->
                val url = sr.url ?: return@mapNotNull null
                val resultTitle = sr.name ?: url
                val snippet = sr.snippet?.let { "<p class=\"search-snippet\">${escapeHtml(it)}</p>" } ?: ""
                """<li><a href="${escapeHtml(url)}" target="_blank">${escapeHtml(resultTitle)}</a>$snippet</li>"""
            }.joinToString("\n")
            if (searchResultsList.isNotEmpty()) {
                """
                <div class="search-results-section">
                    <h3>Search Results</h3>
                    <ol class="search-results-list">
                        $searchResultsList
                    </ol>
                </div>
                """
            } else ""
        } else ""
        // Developer mode: raw usage JSON
        val usageHtml = if (developerMode && !result.rawUsageJson.isNullOrBlank()) {
            """
            <div class="developer-section">
                <h3>Usage (Developer Mode)</h3>
                <pre class="developer-pre">${escapeHtml(result.rawUsageJson)}</pre>
            </div>
            """
        } else ""
        // Developer mode: HTTP headers
        val headersHtml = if (developerMode && !result.httpHeaders.isNullOrBlank()) {
            """
            <div class="developer-section">
                <h3>HTTP Headers (Developer Mode)</h3>
                <pre class="developer-pre">${escapeHtml(result.httpHeaders)}</pre>
            </div>
            """
        } else ""
        """
        <div id="panel-${agent.id}" class="tab-panel $isActive">
            <div class="panel-header">${escapeHtml(providerWithModel)}</div>
            $content
            $citationsHtml
            $searchResultsHtml
            $usageHtml
            $headersHtml
        </div>
        """
    }.joinToString("\n")

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI Report - $title</title>
    <style>
        * { box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
            padding: 16px;
            max-width: 900px;
            margin: 0 auto;
            background-color: #1a1a1a;
            color: #e0e0e0;
        }
        h1, h2, h3 {
            color: #ffffff;
            margin-top: 1.5em;
            margin-bottom: 0.5em;
        }
        h1 { font-size: 1.8em; border-bottom: 2px solid #8B5CF6; padding-bottom: 8px; }
        h2 { font-size: 1.4em; color: #8B5CF6; margin-top: 1em; }
        h3 { font-size: 1.2em; color: #A78BFA; }
        .panel-header {
            font-size: 2em;
            font-weight: bold;
            color: #FF9800;
            margin-bottom: 0.8em;
            padding-bottom: 8px;
            border-bottom: 2px solid #FF9800;
        }
        p { margin: 1em 0; }
        ul { padding-left: 20px; }
        li { margin: 0.5em 0; }
        strong { color: #ffffff; }
        em { color: #b0b0b0; }

        /* Tabs */
        .tabs-container {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            margin-bottom: 16px;
        }
        .tab-btn {
            padding: 8px 16px;
            background: #2d2d2d;
            border: 1px solid #444;
            border-radius: 6px;
            color: #aaa;
            cursor: pointer;
            font-size: 14px;
            transition: all 0.2s;
        }
        .tab-btn:hover { background: #3d3d3d; color: #fff; }
        .tab-btn.active {
            background: #8B5CF6;
            border-color: #8B5CF6;
            color: #fff;
        }
        .tab-panel { display: none; }
        .tab-panel.active { display: block; }

        /* Prompt section */
        .prompt-section {
            margin-top: 40px;
            padding: 16px;
            background: #252525;
            border-radius: 8px;
            border: 1px solid #333;
        }
        .prompt-section h3 {
            margin-top: 0;
            color: #8B5CF6;
        }
        .prompt-text {
            background: #1a1a1a;
            border: 1px solid #333;
            border-radius: 4px;
            padding: 12px;
            color: #b0b0b0;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: monospace;
            font-size: 13px;
        }

        /* Error styling */
        .error {
            background: #2d1f1f;
            border: 1px solid #5c2626;
            border-radius: 8px;
            padding: 16px;
            color: #ff6b6b;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: monospace;
            font-size: 13px;
        }

        /* Citations section */
        .citations-section {
            margin-top: 24px;
            padding: 16px;
            background: #252525;
            border-radius: 8px;
            border: 1px solid #333;
        }
        .citations-section h3 {
            margin-top: 0;
            margin-bottom: 12px;
            color: #8B5CF6;
        }
        .citations-list {
            margin: 0;
            padding-left: 24px;
        }
        .citations-list li {
            margin: 6px 0;
        }
        .citations-list a {
            color: #64B5F6;
            text-decoration: none;
            word-break: break-all;
        }
        .citations-list a:hover {
            text-decoration: underline;
        }

        /* Search results section */
        .search-results-section {
            margin-top: 24px;
            padding: 16px;
            background: #252525;
            border-radius: 8px;
            border: 1px solid #333;
        }
        .search-results-section h3 {
            margin-top: 0;
            margin-bottom: 12px;
            color: #FF9800;
        }
        .search-results-list {
            margin: 0;
            padding-left: 24px;
        }
        .search-results-list li {
            margin: 12px 0;
        }
        .search-results-list a {
            color: #64B5F6;
            text-decoration: none;
            font-weight: bold;
        }
        .search-results-list a:hover {
            text-decoration: underline;
        }
        .search-snippet {
            margin: 4px 0 0 0;
            color: #999;
            font-size: 0.9em;
        }

        /* Developer mode sections */
        .developer-section {
            margin-top: 24px;
            padding: 16px;
            background: #1a2a1a;
            border-radius: 8px;
            border: 1px solid #2a4a2a;
        }
        .developer-section h3 {
            margin-top: 0;
            margin-bottom: 12px;
            color: #4CAF50;
        }
        .developer-pre {
            background: #0d1a0d;
            border: 1px solid #2a4a2a;
            border-radius: 4px;
            padding: 12px;
            color: #81C784;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: monospace;
            font-size: 12px;
            margin: 0;
            overflow-x: auto;
        }

        /* Footer */
        .generated-footer {
            margin-top: 40px;
            padding-top: 16px;
            border-top: 1px solid #444;
            text-align: center;
            color: #666;
            font-size: 12px;
        }
    </style>
</head>
<body>
    <h1>${escapeHtml(title)}</h1>

    <!-- AI Analysis Tabs -->
    <div class="tabs-container">
        $tabsHtml
    </div>

    $panelsHtml

    <!-- Prompt used (shown once at the bottom) -->
    <div class="prompt-section">
        <h3>Prompt Used</h3>
        <pre class="prompt-text">${escapeHtml(promptUsed)}</pre>
    </div>

    <div class="generated-footer">
        Generated by Eval $appVersion on $generatedDate
    </div>

    <script>
    function showTab(serviceName) {
        document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-panel').forEach(panel => panel.classList.remove('active'));
        document.querySelector('.tab-btn[onclick*="' + serviceName + '"]').classList.add('active');
        document.getElementById('panel-' + serviceName).classList.add('active');
    }
    </script>
</body>
</html>
""".trimIndent()
}

/**
 * Builds the player info HTML section from PlayerInfo data.
 */
private fun buildPlayerInfoHtml(playerInfo: com.eval.data.PlayerInfo?, server: String?): String {
    if (playerInfo == null) {
        return """
        <div class="player-section">
            <div class="player-header">
                <span class="player-name">Unknown Player</span>
            </div>
        </div>
        """
    }

    val serverBadge = when (server) {
        "lichess.org" -> """<span class="server-badge server-lichess">Lichess</span>"""
        "chess.com" -> """<span class="server-badge server-chesscom">Chess.com</span>"""
        else -> ""
    }

    val titleBadge = playerInfo.title?.let {
        """<span class="player-title">$it</span>"""
    } ?: ""

    val infoItems = mutableListOf<String>()

    playerInfo.name?.let {
        infoItems.add("""
            <div class="info-item">
                <div class="info-label">Real Name</div>
                <div class="info-value">$it</div>
            </div>
        """)
    }

    val location = listOfNotNull(playerInfo.location, playerInfo.country).joinToString(", ")
    if (location.isNotBlank()) {
        infoItems.add("""
            <div class="info-item">
                <div class="info-label">Location</div>
                <div class="info-value">$location</div>
            </div>
        """)
    }

    playerInfo.createdAt?.let { timestamp ->
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        infoItems.add("""
            <div class="info-item">
                <div class="info-label">Member Since</div>
                <div class="info-value">$date</div>
            </div>
        """)
    }

    playerInfo.followers?.let {
        infoItems.add("""
            <div class="info-item">
                <div class="info-label">Followers</div>
                <div class="info-value">$it</div>
            </div>
        """)
    }

    val infoGridHtml = if (infoItems.isNotEmpty()) {
        """<div class="info-grid">${infoItems.joinToString("\n")}</div>"""
    } else ""

    // Ratings section
    val ratings = mutableListOf<Pair<String, Int>>()
    playerInfo.bulletRating?.let { ratings.add("Bullet" to it) }
    playerInfo.blitzRating?.let { ratings.add("Blitz" to it) }
    playerInfo.rapidRating?.let { ratings.add("Rapid" to it) }
    playerInfo.classicalRating?.let { ratings.add("Classical" to it) }
    playerInfo.dailyRating?.let { ratings.add("Daily" to it) }

    val ratingsHtml = if (ratings.isNotEmpty()) {
        val badgesHtml = ratings.joinToString("\n") { (type, value) ->
            """
            <div class="rating-badge">
                <div class="rating-type">$type</div>
                <div class="rating-value">$value</div>
            </div>
            """
        }
        """
        <div class="ratings-section">
            <h3 style="color: #888; margin-bottom: 0;">Ratings</h3>
            <div class="ratings-grid">$badgesHtml</div>
        </div>
        """
    } else ""

    // Stats section
    val hasStats = playerInfo.totalGames != null || playerInfo.wins != null
    val statsHtml = if (hasStats) {
        val statBadges = mutableListOf<String>()
        playerInfo.totalGames?.let {
            statBadges.add("""
                <div class="stat-badge total">
                    <div class="stat-label">Total</div>
                    <div class="stat-value total">$it</div>
                </div>
            """)
        }
        playerInfo.wins?.let {
            statBadges.add("""
                <div class="stat-badge wins">
                    <div class="stat-label">Wins</div>
                    <div class="stat-value wins">$it</div>
                </div>
            """)
        }
        playerInfo.losses?.let {
            statBadges.add("""
                <div class="stat-badge losses">
                    <div class="stat-label">Losses</div>
                    <div class="stat-value losses">$it</div>
                </div>
            """)
        }
        playerInfo.draws?.let {
            statBadges.add("""
                <div class="stat-badge draws">
                    <div class="stat-label">Draws</div>
                    <div class="stat-value draws">$it</div>
                </div>
            """)
        }
        """
        <div class="stats-section">
            <h3 style="color: #888; margin-bottom: 0;">Game Statistics</h3>
            <div class="stats-grid">${statBadges.joinToString("\n")}</div>
        </div>
        """
    } else ""

    // Bio section
    val bioHtml = playerInfo.bio?.takeIf { it.isNotBlank() }?.let {
        """
        <div style="margin-top: 16px;">
            <h3 style="color: #888; margin-bottom: 8px;">Bio</h3>
            <p style="color: #ccc; margin: 0;">${escapeHtml(it)}</p>
        </div>
        """
    } ?: ""

    return """
    <div class="player-section">
        $serverBadge
        <div class="player-header">
            <span class="player-name">${playerInfo.username}</span>
            $titleBadge
        </div>
        $infoGridHtml
        $ratingsHtml
        $statsHtml
        $bioHtml
    </div>
    """
}

/**
 * Converts AI analysis results from multiple services to a tabbed HTML document.
 */
private fun convertAiReportsToHtml(uiState: GameUiState, appVersion: String): String {
    val generatedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    val game = uiState.game
    val whiteName = game?.players?.white?.user?.name
        ?: game?.players?.white?.aiLevel?.let { "Stockfish $it" }
        ?: "White"
    val blackName = game?.players?.black?.user?.name
        ?: game?.players?.black?.aiLevel?.let { "Stockfish $it" }
        ?: "Black"
    val whiteRating = game?.players?.white?.rating?.toString() ?: ""
    val blackRating = game?.players?.black?.rating?.toString() ?: ""
    val fen = uiState.currentBoard.getFen()
    val flippedBoard = uiState.flippedBoard
    val isWhiteToMove = uiState.currentBoard.getTurn() == com.eval.chess.PieceColor.WHITE
    val currentMoveIndex = uiState.currentMoveIndex
    val moveDetails = uiState.moveDetails
    val analyseScores = uiState.analyseScores

    // Build position info text: "Position after move X .... move / Color to move next" or "Position after move X move / Color to move next"
    val positionInfoText = if (currentMoveIndex >= 0 && currentMoveIndex < moveDetails.size) {
        val lastMove = moveDetails[currentMoveIndex]
        val moveNumber = (currentMoveIndex / 2) + 1
        val wasWhiteMove = currentMoveIndex % 2 == 0  // Index 0 = white's 1st move, index 1 = black's 1st move, etc.
        val moveNotation = if (wasWhiteMove) {
            "$moveNumber. ${lastMove.san}"
        } else {
            "$moveNumber. .... ${lastMove.san}"
        }
        val nextToMove = if (isWhiteToMove) "White to move next" else "Black to move next"
        "Position after move $moveNotation &nbsp;&nbsp;/&nbsp;&nbsp; $nextToMove"
    } else {
        val nextToMove = if (isWhiteToMove) "White to move" else "Black to move"
        "Starting position &nbsp;&nbsp;/&nbsp;&nbsp; $nextToMove"
    }

    // Build move list HTML with Stockfish scores
    val moveListHtml = buildAiReportsMoveListHtml(moveDetails, analyseScores, currentMoveIndex)

    val aiSettings = uiState.aiSettings

    // Get agents with results, sorted by name
    val agentsWithResults = uiState.aiReportsSelectedAgents.mapNotNull { agentId ->
        val agent = aiSettings.getAgentById(agentId)
        val result = uiState.aiReportsAgentResults[agentId]
        if (agent != null && result != null) agent to result else null
    }.sortedBy { it.first.name.lowercase() }

    // Generate tabs HTML (show agent name)
    val tabsHtml = agentsWithResults.mapIndexed { index, (agent, _) ->
        val isActive = if (index == 0) "active" else ""
        """<button class="tab-btn $isActive" onclick="showTab('${agent.id}')">${escapeHtml(agent.name)}</button>"""
    }.joinToString("\n")

    val developerMode = uiState.generalSettings.developerMode

    // Generate content panels for each agent
    val panelsHtml = agentsWithResults.mapIndexed { index, (agent, result) ->
        val isActive = if (index == 0) "active" else ""
        val providerWithModel = "${agent.provider.displayName} (${agent.model})"
        val content = if (result.isSuccess && result.analysis != null) {
            convertMarkdownContentToHtml(result.analysis)
        } else {
            """<pre class="error">${escapeHtml(result.error ?: "Unknown error")}</pre>"""
        }
        // Build citations HTML if available
        val citationsHtml = if (!result.citations.isNullOrEmpty()) {
            val citationsList = result.citations.map { url ->
                """<li><a href="${escapeHtml(url)}" target="_blank">${escapeHtml(url)}</a></li>"""
            }.joinToString("\n")
            """
            <div class="citations-section">
                <h3>Sources</h3>
                <ol class="citations-list">
                    $citationsList
                </ol>
            </div>
            """
        } else ""
        // Build search results HTML if available
        val searchResultsHtml = if (!result.searchResults.isNullOrEmpty()) {
            val searchResultsList = result.searchResults.mapNotNull { sr ->
                val url = sr.url ?: return@mapNotNull null
                val resultTitle = sr.name ?: url
                val snippet = sr.snippet?.let { "<p class=\"search-snippet\">${escapeHtml(it)}</p>" } ?: ""
                """<li><a href="${escapeHtml(url)}" target="_blank">${escapeHtml(resultTitle)}</a>$snippet</li>"""
            }.joinToString("\n")
            if (searchResultsList.isNotEmpty()) {
                """
                <div class="search-results-section">
                    <h3>Search Results</h3>
                    <ol class="search-results-list">
                        $searchResultsList
                    </ol>
                </div>
                """
            } else ""
        } else ""
        // Developer mode: raw usage JSON
        val usageHtml = if (developerMode && !result.rawUsageJson.isNullOrBlank()) {
            """
            <div class="developer-section">
                <h3>Usage (Developer Mode)</h3>
                <pre class="developer-pre">${escapeHtml(result.rawUsageJson)}</pre>
            </div>
            """
        } else ""
        // Developer mode: HTTP headers
        val headersHtml = if (developerMode && !result.httpHeaders.isNullOrBlank()) {
            """
            <div class="developer-section">
                <h3>HTTP Headers (Developer Mode)</h3>
                <pre class="developer-pre">${escapeHtml(result.httpHeaders)}</pre>
            </div>
            """
        } else ""
        // Get the actual prompt used (with @FEN@ replaced)
        val promptUsed = result.promptUsed ?: ""
        val promptHtml = if (promptUsed.isNotBlank()) {
            """
            <div class="prompt-section">
                <h3>Prompt Used</h3>
                <pre class="prompt-text">${escapeHtml(promptUsed)}</pre>
            </div>
            """
        } else ""
        """
        <div id="panel-${agent.id}" class="tab-panel $isActive">
            <div class="panel-header">${escapeHtml(providerWithModel)}</div>
            $content
            $citationsHtml
            $searchResultsHtml
            $usageHtml
            $headersHtml
            $promptHtml
        </div>
        """
    }.joinToString("\n")

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI Analysis Reports</title>
    <link rel="stylesheet" href="https://unpkg.com/@chrisoakman/chessboardjs@1.0.0/dist/chessboard-1.0.0.min.css">
    <script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
    <script src="https://unpkg.com/@chrisoakman/chessboardjs@1.0.0/dist/chessboard-1.0.0.min.js"></script>
    <style>
        * { box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
            padding: 16px;
            max-width: 900px;
            margin: 0 auto;
            background-color: #1a1a1a;
            color: #e0e0e0;
        }
        h1, h2, h3 {
            color: #ffffff;
            margin-top: 1.5em;
            margin-bottom: 0.5em;
        }
        h1 { font-size: 1.8em; border-bottom: 2px solid #8B5CF6; padding-bottom: 8px; }
        h2 { font-size: 1.4em; color: #8B5CF6; margin-top: 1em; }
        h3 { font-size: 1.2em; color: #A78BFA; }
        .panel-header {
            font-size: 2em;
            font-weight: bold;
            color: #FF9800;
            margin-bottom: 0.8em;
            padding-bottom: 8px;
            border-bottom: 2px solid #FF9800;
        }
        p { margin: 1em 0; }
        ul { padding-left: 20px; }
        li { margin: 0.5em 0; }
        strong { color: #ffffff; }
        em { color: #b0b0b0; }

        /* Board container */
        .board-section { margin-bottom: 24px; }
        .board-container {
            display: flex;
            gap: 12px;
            justify-content: center;
            align-items: flex-start;
        }
        .board-wrapper { width: 320px; }

        /* Player bars */
        .player-bar {
            display: flex;
            align-items: center;
            padding: 8px 12px;
            background: #2d2d2d;
            border-radius: 4px;
            margin-bottom: 4px;
            width: 320px;
            box-sizing: border-box;
        }
        .player-bar.bottom { margin-top: 4px; margin-bottom: 0; }
        .player-name { font-weight: bold; color: #fff; }
        .player-rating { color: #888; margin-left: 8px; font-size: 0.9em; }
        .player-indicator {
            width: 12px;
            height: 12px;
            border-radius: 50%;
            margin-right: 8px;
        }
        .player-indicator.white { background: #fff; border: 1px solid #666; }
        .player-indicator.black { background: #000; border: 1px solid #666; }
        .to-move { box-shadow: 0 0 0 2px #ff4444; }

        /* Move list box next to board */
        .moves-box {
            width: 220px;
            height: 388px; /* Same height as board + player bars */
            background: #242424;
            border-radius: 8px;
            overflow-y: auto;
            padding: 8px;
        }
        .moves-box-title {
            font-size: 12px;
            color: #888;
            margin-bottom: 8px;
            text-align: center;
            border-bottom: 1px solid #444;
            padding-bottom: 4px;
        }
        .moves-grid {
            display: grid;
            grid-template-columns: 28px 1fr 1fr;
            gap: 2px 4px;
            font-family: monospace;
            font-size: 12px;
        }
        .move-number { color: #666; text-align: right; padding-right: 4px; }
        .move-cell {
            padding: 2px 4px;
            border-radius: 3px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .move-cell.current { background: #4a4a00; }
        .move-san { color: #fff; }
        .move-score { color: #888; font-size: 10px; margin-left: 4px; }
        .move-score.positive { color: #4CAF50; }
        .move-score.negative { color: #f44336; }

        /* Position info text */
        .position-info {
            text-align: center;
            font-size: 1.2em;
            font-weight: bold;
            color: #4CAF50;
            margin-top: 16px;
            padding: 12px;
            background: #242424;
            border-radius: 8px;
        }

        /* Prompt section */
        .prompt-section {
            margin-top: 24px;
            padding-top: 16px;
            border-top: 1px solid #444;
        }
        .prompt-section h3 {
            color: #888;
            font-size: 1em;
            margin-bottom: 8px;
        }
        .prompt-text {
            background: #1a1a1a;
            border: 1px solid #333;
            border-radius: 6px;
            padding: 12px;
            font-family: monospace;
            font-size: 11px;
            color: #aaa;
            white-space: pre-wrap;
            word-wrap: break-word;
            line-height: 1.5;
        }

        /* Tabs */
        .tabs-container {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            margin: 24px 0 16px 0;
            border-bottom: 2px solid #333;
            padding-bottom: 8px;
        }
        .tab-btn {
            padding: 10px 20px;
            background: #2d2d2d;
            border: none;
            border-radius: 8px 8px 0 0;
            color: #888;
            cursor: pointer;
            font-size: 14px;
            font-weight: 500;
            transition: all 0.2s;
        }
        .tab-btn:hover {
            background: #3d3d3d;
            color: #ccc;
        }
        .tab-btn.active {
            background: #8B5CF6;
            color: #fff;
        }

        /* Tab panels */
        .tab-panel {
            display: none;
            padding: 16px 0;
        }
        .tab-panel.active {
            display: block;
        }

        /* Error styling */
        pre.error {
            background: #2d1f1f;
            border: 1px solid #5c2020;
            border-radius: 8px;
            padding: 16px;
            color: #ff6b6b;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: monospace;
            font-size: 13px;
        }

        /* Citations section */
        .citations-section {
            margin-top: 24px;
            padding: 16px;
            background: #252525;
            border-radius: 8px;
            border: 1px solid #333;
        }
        .citations-section h3 {
            margin-top: 0;
            margin-bottom: 12px;
            color: #8B5CF6;
        }
        .citations-list {
            margin: 0;
            padding-left: 24px;
        }
        .citations-list li {
            margin: 6px 0;
        }
        .citations-list a {
            color: #64B5F6;
            text-decoration: none;
            word-break: break-all;
        }
        .citations-list a:hover {
            text-decoration: underline;
        }

        /* Search results section */
        .search-results-section {
            margin-top: 24px;
            padding: 16px;
            background: #252525;
            border-radius: 8px;
            border: 1px solid #333;
        }
        .search-results-section h3 {
            margin-top: 0;
            margin-bottom: 12px;
            color: #FF9800;
        }
        .search-results-list {
            margin: 0;
            padding-left: 24px;
        }
        .search-results-list li {
            margin: 12px 0;
        }
        .search-results-list a {
            color: #64B5F6;
            text-decoration: none;
            font-weight: bold;
        }
        .search-results-list a:hover {
            text-decoration: underline;
        }
        .search-snippet {
            margin: 4px 0 0 0;
            color: #999;
            font-size: 0.9em;
        }

        /* Developer mode sections */
        .developer-section {
            margin-top: 24px;
            padding: 16px;
            background: #1a2a1a;
            border-radius: 8px;
            border: 1px solid #2a4a2a;
        }
        .developer-section h3 {
            margin-top: 0;
            margin-bottom: 12px;
            color: #4CAF50;
        }
        .developer-pre {
            background: #0d1a0d;
            border: 1px solid #2a4a2a;
            border-radius: 4px;
            padding: 12px;
            color: #81C784;
            white-space: pre-wrap;
            word-wrap: break-word;
            font-family: monospace;
            font-size: 12px;
            margin: 0;
            overflow-x: auto;
        }

        /* Footer */
        .generated-footer {
            margin-top: 40px;
            padding-top: 16px;
            border-top: 1px solid #444;
            text-align: center;
            color: #666;
            font-size: 12px;
        }
    </style>
</head>
<body>
    <h1>AI Analysis Reports</h1>

    <!-- Chess Board Section -->
    <div class="board-section">
        <div class="board-container">
            <!-- Board with player bars -->
            <div class="board-wrapper">
                <div class="player-bar" id="topPlayer">
                    <div class="player-indicator ${if (flippedBoard) "white" else "black"}"></div>
                    <span class="player-name">${if (flippedBoard) whiteName else blackName}</span>
                    <span class="player-rating">${if (flippedBoard) whiteRating else blackRating}</span>
                </div>
                <div id="board" style="width: 320px;"></div>
                <div class="player-bar bottom" id="bottomPlayer">
                    <div class="player-indicator ${if (flippedBoard) "black" else "white"}"></div>
                    <span class="player-name">${if (flippedBoard) blackName else whiteName}</span>
                    <span class="player-rating">${if (flippedBoard) blackRating else whiteRating}</span>
                </div>
            </div>
            <!-- Move list box -->
            <div class="moves-box">
                <div class="moves-box-title">Moves with Stockfish Scores</div>
                $moveListHtml
            </div>
        </div>
        <!-- Position info text below board -->
        <div class="position-info">
            $positionInfoText
        </div>
    </div>

    <!-- Tabs -->
    <div class="tabs-container">
        $tabsHtml
    </div>

    <!-- Tab panels -->
    $panelsHtml

    <script>
        // Initialize chessboard
        var board = Chessboard('board', {
            position: '$fen',
            orientation: '${if (flippedBoard) "black" else "white"}',
            pieceTheme: 'https://chessboardjs.com/img/chesspieces/wikipedia/{piece}.png'
        });

        // Tab switching
        function showTab(serviceName) {
            // Hide all panels
            document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
            // Deactivate all buttons
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            // Show selected panel
            document.getElementById('panel-' + serviceName).classList.add('active');
            // Activate selected button
            event.target.classList.add('active');
        }
    </script>

    <div class="generated-footer">
        Generated by Eval $appVersion on $generatedDate
    </div>
</body>
</html>
"""
}

/**
 * Converts markdown content to HTML (helper function for AI reports).
 */
private fun convertMarkdownContentToHtml(markdown: String): String {
    return markdown
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace("\n\n", "</p><p>")
        .replace("\n", "<br>")
        .let { html ->
            html.replace(Regex("(<li>.*?</li>)+")) { match ->
                "<ul>${match.value}</ul>"
            }
        }
        .let { "<p>$it</p>" }
}

/**
 * Escapes HTML special characters.
 */
private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

/**
 * Converts markdown text to a styled HTML document with chessboard and game data.
 */
internal fun convertMarkdownToHtml(serviceName: String, markdown: String, uiState: GameUiState, appVersion: String): String {
    val generatedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    // Basic markdown to HTML conversion
    var analysisHtml = markdown
        // Escape HTML entities first
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        // Headers
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        // Bold
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        // Italic
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        // Bullet points
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        // Numbered lists
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        // Line breaks
        .replace("\n\n", "</p><p>")
        .replace("\n", "<br>")

    // Wrap consecutive <li> items in <ul>
    analysisHtml = analysisHtml.replace(Regex("(<li>.*?</li>)+")) { match ->
        "<ul>${match.value}</ul>"
    }

    // Extract game data
    val game = uiState.game
    val whiteName = game?.players?.white?.user?.name
        ?: game?.players?.white?.aiLevel?.let { "Stockfish $it" }
        ?: "White"
    val blackName = game?.players?.black?.user?.name
        ?: game?.players?.black?.aiLevel?.let { "Stockfish $it" }
        ?: "Black"
    val whiteRating = game?.players?.white?.rating?.toString() ?: ""
    val blackRating = game?.players?.black?.rating?.toString() ?: ""
    val fen = uiState.currentBoard.getFen()
    val currentMoveIndex = uiState.currentMoveIndex
    val isWhiteToMove = uiState.currentBoard.getTurn() == com.eval.chess.PieceColor.WHITE
    val flippedBoard = uiState.flippedBoard

    // Generate move list HTML
    val moveListHtml = buildMoveListHtml(uiState)

    // Generate preview scores for line graph
    val previewScoresJson = uiState.previewScores.entries
        .sortedBy { it.key }
        .joinToString(",") { (idx, score) ->
            val value = if (score.isMate) {
                if (score.mateIn > 0) 10.0 else -10.0
            } else {
                score.score.toDouble().coerceIn(-10.0, 10.0)
            }
            """{"move":$idx,"score":$value}"""
        }

    // Generate analyse scores for line graph
    val analyseScoresJson = uiState.analyseScores.entries
        .sortedBy { it.key }
        .joinToString(",") { (idx, score) ->
            val value = if (score.isMate) {
                if (score.mateIn > 0) 10.0 else -10.0
            } else {
                score.score.toDouble().coerceIn(-10.0, 10.0)
            }
            """{"move":$idx,"score":$value}"""
        }

    // Generate Stockfish analysis HTML
    val stockfishHtml = buildStockfishAnalysisHtml(uiState)

    // Generate PGN with each move on a new line
    val pgnHtml = buildPgnHtml(uiState)

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$serviceName Analysis</title>
    <link rel="stylesheet" href="https://unpkg.com/@chrisoakman/chessboardjs@1.0.0/dist/chessboard-1.0.0.min.css">
    <script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
    <script src="https://unpkg.com/@chrisoakman/chessboardjs@1.0.0/dist/chessboard-1.0.0.min.js"></script>
    <style>
        * { box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
            padding: 16px;
            max-width: 900px;
            margin: 0 auto;
            background-color: #1a1a1a;
            color: #e0e0e0;
        }
        h1, h2, h3 {
            color: #ffffff;
            margin-top: 1.5em;
            margin-bottom: 0.5em;
        }
        h1 { font-size: 1.8em; border-bottom: 2px solid #6B9BFF; padding-bottom: 8px; }
        h2 { font-size: 1.4em; color: #6B9BFF; margin-top: 1em; }
        h3 { font-size: 1.2em; color: #8BB8FF; }
        p { margin: 1em 0; }
        ul { padding-left: 20px; }
        li { margin: 0.5em 0; }
        strong { color: #ffffff; }
        em { color: #b0b0b0; }

        /* Board container */
        .board-section {
            margin-bottom: 24px;
        }
        .board-container {
            display: flex;
            gap: 8px;
            justify-content: center;
            align-items: stretch;
        }
        .board-wrapper {
            width: 320px;
        }

        /* Player bars */
        .player-bar {
            display: flex;
            align-items: center;
            padding: 8px 12px;
            background: #2d2d2d;
            border-radius: 4px;
            margin-bottom: 4px;
            width: 320px;
            box-sizing: border-box;
        }
        .player-bar.bottom { margin-top: 4px; margin-bottom: 0; }
        .player-name { font-weight: bold; color: #fff; }
        .player-rating { color: #888; margin-left: 8px; font-size: 0.9em; }
        .player-indicator {
            width: 12px;
            height: 12px;
            border-radius: 50%;
            margin-right: 8px;
        }
        .player-indicator.white { background: #fff; border: 1px solid #666; }
        .player-indicator.black { background: #000; border: 1px solid #666; }
        .to-move { box-shadow: 0 0 0 2px #ff4444; }

        /* PGN section */
        .pgn-section {
            background: #242424;
            padding: 16px;
            border-radius: 8px;
            margin: 16px 0;
        }
        .pgn-section pre {
            background: #1a1a1a;
            padding: 12px;
            border-radius: 4px;
            font-family: monospace;
            font-size: 13px;
            color: #ccc;
            white-space: pre-wrap;
            word-wrap: break-word;
            margin: 0;
            line-height: 1.8;
        }

        /* Analysis section */
        .analysis-section {
            background: #242424;
            padding: 16px;
            border-radius: 8px;
            margin: 16px 0;
        }

        /* Graphs */
        .graphs-section {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 16px;
            margin: 16px 0;
        }
        .graph-container {
            background: #1a1a1a;
            border-radius: 8px;
            padding: 12px;
            border: 1px solid #333;
        }
        .graph-title {
            font-size: 0.9em;
            color: #888;
            margin-bottom: 8px;
        }
        .graph-canvas {
            width: 100%;
            height: 120px;
            background: #111;
            border-radius: 4px;
        }

        /* Move list */
        .moves-section {
            background: #242424;
            padding: 16px;
            border-radius: 8px;
            margin: 16px 0;
        }
        .moves-grid {
            display: grid;
            grid-template-columns: 40px 1fr 1fr;
            gap: 4px 8px;
            font-family: monospace;
            font-size: 14px;
        }
        .move-number { color: #666; text-align: right; }
        .move { padding: 2px 6px; border-radius: 3px; }
        .move.current { background: #4a4a00; }
        .move.white-move { color: #fff; }
        .move.black-move { color: #ccc; }

        /* Stockfish section */
        .stockfish-section {
            background: #242424;
            padding: 16px;
            border-radius: 8px;
            margin: 16px 0;
        }
        .pv-line {
            font-family: monospace;
            padding: 8px;
            background: #1a1a1a;
            border-radius: 4px;
            margin: 4px 0;
        }
        .pv-score {
            display: inline-block;
            min-width: 60px;
            font-weight: bold;
        }
        .pv-score.positive { color: #4CAF50; }
        .pv-score.negative { color: #f44336; }
        .pv-moves { color: #aaa; }

        @media (max-width: 600px) {
            .graphs-section { grid-template-columns: 1fr; }
            .board-wrapper { width: 280px; }
        }

        /* Footer */
        .generated-footer {
            margin-top: 40px;
            padding-top: 16px;
            border-top: 1px solid #444;
            text-align: center;
            color: #666;
            font-size: 12px;
        }
    </style>
</head>
<body>
    <h1>$serviceName Analysis</h1>

    <!-- Chess Board Section -->
    <div class="board-section">
        <div class="board-container">
            <!-- Board with player bars -->
            <div class="board-wrapper">
                <div class="player-bar" id="topPlayer">
                    <div class="player-indicator ${if (flippedBoard) "white" else "black"} ${if ((flippedBoard && isWhiteToMove) || (!flippedBoard && !isWhiteToMove)) "to-move" else ""}"></div>
                    <span class="player-name">${if (flippedBoard) whiteName else blackName}</span>
                    <span class="player-rating">${if (flippedBoard) whiteRating else blackRating}</span>
                </div>
                <div id="board" style="width: 320px;"></div>
                <div class="player-bar bottom" id="bottomPlayer">
                    <div class="player-indicator ${if (flippedBoard) "black" else "white"} ${if ((flippedBoard && !isWhiteToMove) || (!flippedBoard && isWhiteToMove)) "to-move" else ""}"></div>
                    <span class="player-name">${if (flippedBoard) blackName else whiteName}</span>
                    <span class="player-rating">${if (flippedBoard) blackRating else whiteRating}</span>
                </div>
            </div>
        </div>
        <p style="text-align: center; color: #4CAF50; font-size: 1.3em; font-weight: bold; margin-top: 12px;">
            Move ${currentMoveIndex + 1} of ${uiState.moves.size} - ${if (isWhiteToMove) "White" else "Black"} to move
        </p>
    </div>

    <!-- AI Analysis -->
    <div class="analysis-section">
        <h2>$serviceName Analysis</h2>
        <p>$analysisHtml</p>
    </div>

    <!-- Graphs -->
    <div class="graphs-section">
        <div class="graph-container">
            <div class="graph-title">Score Line Graph (Preview)</div>
            <canvas id="lineGraph" class="graph-canvas"></canvas>
        </div>
        <div class="graph-container">
            <div class="graph-title">Score Line Graph (Analyse)</div>
            <canvas id="analyseGraph" class="graph-canvas"></canvas>
        </div>
    </div>

    <!-- Move List -->
    <div class="moves-section">
        <h2>Move List</h2>
        $moveListHtml
    </div>

    <!-- Stockfish Analysis -->
    <div class="stockfish-section">
        <h2>Stockfish Analysis</h2>
        $stockfishHtml
    </div>

    <!-- PGN -->
    <div class="pgn-section">
        <h2>PGN</h2>
        <pre>$pgnHtml</pre>
    </div>

    <script>
        // Initialize chessboard
        var board = Chessboard('board', {
            position: '$fen',
            orientation: '${if (flippedBoard) "black" else "white"}',
            pieceTheme: 'https://chessboardjs.com/img/chesspieces/wikipedia/{piece}.png'
        });

        // Draw line graphs
        function drawLineGraph(canvasId, data, currentMove) {
            var canvas = document.getElementById(canvasId);
            var ctx = canvas.getContext('2d');
            var width = canvas.width = canvas.offsetWidth * 2;
            var height = canvas.height = canvas.offsetHeight * 2;
            ctx.scale(2, 2);
            var w = width / 2;
            var h = height / 2;

            // Background
            ctx.fillStyle = '#111';
            ctx.fillRect(0, 0, w, h);

            // Draw zero line
            ctx.strokeStyle = '#333';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(0, h/2);
            ctx.lineTo(w, h/2);
            ctx.stroke();

            if (data.length === 0) {
                ctx.fillStyle = '#666';
                ctx.font = '12px sans-serif';
                ctx.textAlign = 'center';
                ctx.fillText('No data', w/2, h/2);
                return;
            }

            // Find range
            var maxMove = Math.max(...data.map(d => d.move));
            var range = 7; // -7 to +7

            // Draw filled area
            ctx.beginPath();
            ctx.moveTo(0, h/2);
            data.forEach(function(d, i) {
                var x = (d.move / Math.max(maxMove, 1)) * w;
                var y = h/2 - (d.score / range) * (h/2);
                y = Math.max(0, Math.min(h, y));
                if (i === 0) ctx.lineTo(x, y);
                else ctx.lineTo(x, y);
            });
            var lastX = (data[data.length-1].move / Math.max(maxMove, 1)) * w;
            ctx.lineTo(lastX, h/2);
            ctx.closePath();

            // Fill with gradient
            var gradient = ctx.createLinearGradient(0, 0, 0, h);
            gradient.addColorStop(0, 'rgba(244, 67, 54, 0.5)');
            gradient.addColorStop(0.5, 'rgba(100, 100, 100, 0.3)');
            gradient.addColorStop(1, 'rgba(76, 175, 80, 0.5)');
            ctx.fillStyle = gradient;
            ctx.fill();

            // Draw line
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            data.forEach(function(d, i) {
                var x = (d.move / Math.max(maxMove, 1)) * w;
                var y = h/2 - (d.score / range) * (h/2);
                y = Math.max(0, Math.min(h, y));
                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            });
            ctx.stroke();

            // Draw current move line
            if (currentMove >= 0) {
                var cx = (currentMove / Math.max(maxMove, 1)) * w;
                ctx.strokeStyle = '#2196F3';
                ctx.lineWidth = 2;
                ctx.beginPath();
                ctx.moveTo(cx, 0);
                ctx.lineTo(cx, h);
                ctx.stroke();
            }
        }

        var previewData = [$previewScoresJson];
        var analyseData = [$analyseScoresJson];
        var currentMove = $currentMoveIndex;

        drawLineGraph('lineGraph', previewData, currentMove);
        drawLineGraph('analyseGraph', analyseData, currentMove);

        // Resize handler
        window.addEventListener('resize', function() {
            board.resize();
            drawLineGraph('lineGraph', previewData, currentMove);
            drawLineGraph('analyseGraph', analyseData, currentMove);
        });
    </script>

    <div class="generated-footer">
        Generated by Eval $appVersion on $generatedDate
    </div>
</body>
</html>
    """.trimIndent()
}

/**
 * Builds HTML for the move list.
 */
private fun buildMoveListHtml(uiState: GameUiState): String {
    val moves = uiState.moveDetails
    if (moves.isEmpty()) return "<p>No moves available</p>"

    val sb = StringBuilder()
    sb.append("<div class=\"moves-grid\">")

    for (i in moves.indices step 2) {
        val moveNum = (i / 2) + 1
        val whiteMove = moves[i]
        val blackMove = moves.getOrNull(i + 1)

        val whiteCurrent = if (i == uiState.currentMoveIndex) "current" else ""
        val blackCurrent = if (i + 1 == uiState.currentMoveIndex) "current" else ""

        sb.append("<div class=\"move-number\">$moveNum.</div>")
        sb.append("<div class=\"move white-move $whiteCurrent\">${whiteMove.san}</div>")
        if (blackMove != null) {
            sb.append("<div class=\"move black-move $blackCurrent\">${blackMove.san}</div>")
        } else {
            sb.append("<div></div>")
        }
    }

    sb.append("</div>")
    return sb.toString()
}

/**
 * Builds HTML for the AI reports move list with Stockfish scores.
 */
private fun buildAiReportsMoveListHtml(
    moveDetails: List<MoveDetails>,
    analyseScores: Map<Int, MoveScore>,
    currentMoveIndex: Int
): String {
    if (moveDetails.isEmpty()) return "<div style=\"color: #888; text-align: center;\">No moves</div>"

    val sb = StringBuilder()
    sb.append("<div class=\"moves-grid\">")

    for (i in moveDetails.indices step 2) {
        val moveNum = (i / 2) + 1
        val whiteMove = moveDetails[i]
        val blackMove = moveDetails.getOrNull(i + 1)

        val whiteCurrent = if (i == currentMoveIndex) "current" else ""
        val blackCurrent = if (i + 1 == currentMoveIndex) "current" else ""

        // Get scores for white and black moves
        val whiteScore = analyseScores[i]
        val blackScore = analyseScores.getOrElse(i + 1) { null }

        fun formatScore(score: MoveScore?): String {
            if (score == null) return ""
            return if (score.isMate) {
                val mateText = if (score.mateIn > 0) "M${score.mateIn}" else "M${-score.mateIn}"
                val scoreClass = if (score.mateIn > 0) "positive" else "negative"
                """<span class="move-score $scoreClass">$mateText</span>"""
            } else {
                val scoreClass = if (score.score >= 0) "positive" else "negative"
                val scoreText = if (score.score >= 0) "+%.1f".format(score.score) else "%.1f".format(score.score)
                """<span class="move-score $scoreClass">$scoreText</span>"""
            }
        }

        sb.append("<div class=\"move-number\">$moveNum.</div>")
        sb.append("<div class=\"move-cell $whiteCurrent\"><span class=\"move-san\">${whiteMove.san}</span>${formatScore(whiteScore)}</div>")
        if (blackMove != null) {
            sb.append("<div class=\"move-cell $blackCurrent\"><span class=\"move-san\">${blackMove.san}</span>${formatScore(blackScore)}</div>")
        } else {
            sb.append("<div></div>")
        }
    }

    sb.append("</div>")
    return sb.toString()
}

/**
 * Builds HTML for the Stockfish analysis.
 */
private fun buildStockfishAnalysisHtml(uiState: GameUiState): String {
    val result = uiState.analysisResult
    if (result == null) return "<p>No Stockfish analysis available</p>"

    val sb = StringBuilder()
    sb.append("<p style=\"color: #888;\">Depth: ${result.depth} • Nodes: ${formatNodes(result.nodes)}</p>")

    result.lines.forEach { line ->
        val scoreClass = when {
            line.isMate -> if (line.mateIn > 0) "positive" else "negative"
            line.score >= 0 -> "positive"
            else -> "negative"
        }
        val scoreText = if (line.isMate) {
            if (line.mateIn > 0) "M${line.mateIn}" else "M${-line.mateIn}"
        } else {
            val s = line.score
            if (s >= 0) "+%.2f".format(s) else "%.2f".format(s)
        }

        // Convert UCI moves to readable format (simplified)
        val movesText = line.pv.split(" ").take(8).joinToString(" ")

        sb.append("""
            <div class="pv-line">
                <span class="pv-score $scoreClass">$scoreText</span>
                <span class="pv-moves">$movesText</span>
            </div>
        """.trimIndent())
    }

    return sb.toString()
}

/**
 * Formats node count for display.
 */
private fun formatNodes(nodes: Long): String {
    return when {
        nodes >= 1_000_000_000 -> "%.1fB".format(nodes / 1_000_000_000.0)
        nodes >= 1_000_000 -> "%.1fM".format(nodes / 1_000_000.0)
        nodes >= 1_000 -> "%.1fK".format(nodes / 1_000.0)
        else -> nodes.toString()
    }
}

/**
 * Builds HTML for the PGN with each move on a new line.
 */
private fun buildPgnHtml(uiState: GameUiState): String {
    val moves = uiState.moveDetails
    if (moves.isEmpty()) return "No moves available"

    val sb = StringBuilder()

    // Add game headers if available
    val game = uiState.game
    if (game != null) {
        val whiteName = game.players.white.user?.name ?: "White"
        val blackName = game.players.black.user?.name ?: "Black"
        val whiteRating = game.players.white.rating
        val blackRating = game.players.black.rating

        sb.append("[White \"$whiteName\"]\n")
        sb.append("[Black \"$blackName\"]\n")
        if (whiteRating != null) sb.append("[WhiteElo \"$whiteRating\"]\n")
        if (blackRating != null) sb.append("[BlackElo \"$blackRating\"]\n")

        val result = when {
            game.winner == "white" -> "1-0"
            game.winner == "black" -> "0-1"
            game.status == "draw" || game.status == "stalemate" -> "1/2-1/2"
            else -> "*"
        }
        sb.append("[Result \"$result\"]\n")
        sb.append("\n")
    }

    // Add moves, one per line with move number
    for (i in moves.indices step 2) {
        val moveNum = (i / 2) + 1
        val whiteMove = moves[i]
        val blackMove = moves.getOrNull(i + 1)

        if (blackMove != null) {
            sb.append("$moveNum. ${whiteMove.san} ${blackMove.san}\n")
        } else {
            sb.append("$moveNum. ${whiteMove.san}\n")
        }
    }

    return sb.toString().trim()
}

/**
 * Dialog for sharing the current position.
 */
@Composable
fun SharePositionDialog(
    fen: String,
    onCopyFen: () -> Unit,
    onShare: () -> Unit,
    onExportPgn: () -> Unit,
    onCopyPgn: () -> Unit,
    onExportGif: () -> Unit,
    onGenerateAiReports: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Share / Export",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // FEN display
                Text(
                    text = "FEN:",
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFAAAAAA)
                )
                Text(
                    text = fen,
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )

                // Copy FEN button
                Button(
                    onClick = {
                        onCopyFen()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B8E23)
                    )
                ) {
                    Text("Copy FEN to Clipboard")
                }

                // Copy PGN button
                Button(
                    onClick = {
                        onCopyPgn()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B8E23)
                    )
                ) {
                    Text("Copy PGN to Clipboard")
                }

                // Share button
                Button(
                    onClick = {
                        onShare()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B8E23)
                    )
                ) {
                    Text("Share Position")
                }

                // Export PGN button
                Button(
                    onClick = {
                        onExportPgn()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B8E23)
                    )
                ) {
                    Text("Share Annotated PGN")
                }

                // Export GIF button
                Button(
                    onClick = {
                        onExportGif()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B8E23)
                    )
                ) {
                    Text("Export as Animated GIF")
                }

                // AI Reports button - always visible, selection dialog will show available providers
                Button(
                    onClick = {
                        onGenerateAiReports()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B8E23)
                    )
                ) {
                    Text("Generate AI Reports")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
