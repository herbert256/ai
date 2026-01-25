package com.eval.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HelpScreen(
    onBack: () -> Unit
) {
    // Handle back navigation
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        EvalTitleBar(
            title = "Help",
            onBackClick = onBack,
            onEvalClick = onBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Welcome section
            HelpSection(
                title = "Welcome to Eval",
                content = "Analyze your chess games from Lichess.org and Chess.com with the powerful Stockfish 17.1 engine and 9 AI services. " +
                    "The app automatically fetches your games and provides deep analysis to help you improve."
            )

            // Getting Started
            HelpSection(
                title = "Getting Started",
                icon = "\uD83D\uDE80",
                content = "Enter your username in the Lichess or Chess.com card and tap 'Retrieve' to fetch games. " +
                    "You can also browse tournaments, broadcasts, TV channels, streamers, or import PGN files. " +
                    "Select a game to start the analysis. The app remembers your last game for quick startup."
            )

            // Analysis Stages
            HelpSection(
                title = "Analysis Stages",
                icon = "\uD83D\uDCCA",
                content = "Games progress through three analysis stages:\n\n" +
                    "1. Preview Stage (orange) - Quick scan of all positions (50ms/move)\n" +
                    "2. Analyse Stage (blue) - Deep analysis backward (1s/move), tap to skip\n" +
                    "3. Manual Stage - Interactive exploration with real-time analysis"
            )

            // Navigation
            HelpSection(
                title = "Board Navigation",
                icon = "♟",
                content = "Use the navigation buttons:\n\n" +
                    "⏮  Go to start\n" +
                    "◀  Previous move\n" +
                    "▶  Next move\n" +
                    "⏭  Go to end\n" +
                    "↻  Flip board\n\n" +
                    "Tap or drag on the evaluation graph to jump to any position."
            )

            // Evaluation Graphs
            HelpSection(
                title = "Evaluation Graphs",
                icon = "\uD83D\uDCC8",
                content = "The line graph shows position evaluation over time:\n" +
                    "• Green = good for you\n" +
                    "• Red = bad for you\n" +
                    "• Yellow line = deep analysis scores\n\n" +
                    "The bar graph shows score changes between moves - tall red bars indicate blunders!"
            )

            // Arrows
            HelpSection(
                title = "Analysis Arrows",
                icon = "↗",
                content = "In Manual stage, tap the ↗ icon to cycle through three arrow modes:\n\n" +
                    "• Off - No arrows\n" +
                    "• Main line - Best continuation with numbered moves\n" +
                    "• Multi-line - One arrow per analysis line with scores\n\n" +
                    "Arrow colors can be customized in Settings."
            )

            // AI Analysis
            HelpSection(
                title = "AI Position Analysis",
                icon = "\uD83E\uDD16",
                content = "In Manual stage, tap AI logos next to the board to get intelligent analysis from 9 AI services:\n\n" +
                    "• ChatGPT (OpenAI)\n" +
                    "• Claude (Anthropic)\n" +
                    "• Gemini (Google)\n" +
                    "• Grok (xAI)\n" +
                    "• DeepSeek\n" +
                    "• Mistral\n" +
                    "• Perplexity\n" +
                    "• Together AI\n" +
                    "• OpenRouter\n\n" +
                    "Configure API keys in Settings > AI Setup."
            )

            // AI Hub
            HelpSection(
                title = "AI Hub",
                icon = "\uD83D\uDCDD",
                content = "Access the AI Hub from the main screen for advanced AI features:\n\n" +
                    "• New AI Report - Create custom AI reports with any prompt\n" +
                    "• Prompt History - Reuse previously submitted prompts\n" +
                    "• AI History - View previously generated reports\n\n" +
                    "Reports are saved as HTML files you can view in Chrome or share."
            )

            // Three-Tier AI Architecture
            HelpSection(
                title = "AI Agents",
                icon = "⚙",
                content = "Configure AI analysis with the three-tier architecture:\n\n" +
                    "• Providers - AI services with model settings\n" +
                    "• Prompts - Reusable prompt templates with placeholders (@FEN@, @PLAYER@, @SERVER@)\n" +
                    "• Agents - Combine provider + model + API key + prompts\n\n" +
                    "Create multiple agents for different analysis purposes."
            )

            // Content Sources
            HelpSection(
                title = "Game Sources",
                icon = "\uD83C\uDFAE",
                content = "Lichess.org:\n" +
                    "• User games, Tournaments, Broadcasts, TV channels, Streamers, Rankings\n\n" +
                    "Chess.com:\n" +
                    "• User games, Daily puzzle, Rankings\n\n" +
                    "Local sources:\n" +
                    "• PGN files (with ZIP support), ECO openings, FEN positions, Game history"
            )

            // Player Info
            HelpSection(
                title = "Player Information",
                icon = "\uD83D\uDC64",
                content = "View detailed player profiles with ratings across time controls, " +
                    "game statistics, and recent games. " +
                    "Generate AI reports about players using the AI Report feature on the player screen."
            )

            // Opening Explorer
            HelpSection(
                title = "Opening Explorer",
                icon = "\uD83D\uDCD6",
                content = "Enable Opening Explorer in Settings > Interface Elements to see position statistics:\n\n" +
                    "• Popular moves played in this position\n" +
                    "• Win/Draw/Loss percentages\n" +
                    "• Number of games with each move\n\n" +
                    "Data from Lichess opening database."
            )

            // Top Bar Icons
            HelpSection(
                title = "Top Bar Icons",
                icon = "\uD83D\uDD27",
                content = "↻  Reload latest game\n" +
                    "≡  Return to game selection\n" +
                    "↗  Toggle arrow display mode\n" +
                    "⚙  Settings\n" +
                    "?  This help screen\n" +
                    "\uD83D\uDC1B  API trace viewer (when enabled)"
            )

            // Exploring Lines
            HelpSection(
                title = "Exploring Lines",
                icon = "\uD83D\uDD0D",
                content = "In the analysis panel, tap any move in a Stockfish line to explore that variation. " +
                    "The board will show the position after those moves. " +
                    "Tap 'Back to game' to return to the main game position."
            )

            // Export Features
            HelpSection(
                title = "Export Features",
                icon = "\uD83D\uDCE4",
                content = "Share your analysis:\n\n" +
                    "• PGN - Full game with evaluation comments\n" +
                    "• GIF - Animated replay of the game\n" +
                    "• AI Reports - HTML with interactive board, graphs, and AI analysis\n\n" +
                    "Use Android share sheet or email directly from the app."
            )

            // Settings Overview
            HelpSection(
                title = "Settings Overview",
                icon = "⚙",
                content = "Customize the app:\n\n" +
                    "• Board Layout - Colors, coordinates, player bars, eval bar\n" +
                    "• Interface Elements - Show/hide UI per stage\n" +
                    "• Graph Settings - Evaluation graph colors and ranges\n" +
                    "• Arrow Settings - Arrow modes, colors, count\n" +
                    "• Stockfish - Engine settings per analysis stage\n" +
                    "• AI Setup - Providers, prompts, agents\n" +
                    "• General - Fullscreen, sounds, pagination"
            )

            // Tips
            HelpSection(
                title = "Tips",
                icon = "\uD83D\uDCA1",
                content = "• Background color shows result: green (win), red (loss), blue (draw)\n" +
                    "• Scores are always from your perspective\n" +
                    "• Player bars show remaining clock time when available\n" +
                    "• Tap 'Analysis running' banner to jump to biggest mistake\n" +
                    "• Enable 'Red border for player to move' to see whose turn it is\n" +
                    "• Move list shows colored scores - green moves are good, red are mistakes\n" +
                    "• Long tap for fullscreen mode (if enabled in General settings)"
            )

            // Live Games
            HelpSection(
                title = "Live Games",
                icon = "\uD83D\uDCFA",
                content = "Follow games in real-time:\n\n" +
                    "• Select a game from TV channels or streamers\n" +
                    "• Enable 'Auto-follow' to update automatically\n" +
                    "• Watch moves appear as they're played\n" +
                    "• Analysis updates with each new move"
            )

            // API Tracing
            HelpSection(
                title = "Developer: API Tracing",
                icon = "\uD83D\uDC1B",
                content = "Enable 'Track API calls' in General settings to log all network requests:\n\n" +
                    "• All Lichess, Chess.com, and AI service calls are logged\n" +
                    "• View requests/responses in the trace viewer\n" +
                    "• Useful for debugging API issues\n" +
                    "• Traces are cleared when tracking is disabled"
            )

            // About
            HelpSection(
                title = "About",
                icon = "ℹ",
                content = "Eval uses Stockfish 17.1, the world's strongest open-source chess engine.\n\n" +
                    "Game data from Lichess.org and Chess.com public APIs.\n\n" +
                    "AI analysis from OpenAI, Anthropic, Google, xAI, DeepSeek, Mistral, Perplexity, Together AI, and OpenRouter.\n\n" +
                    "All data stored locally on your device."
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    content: String,
    icon: String? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Text(
                        text = icon,
                        fontSize = 24.sp
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B9BFF)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFCCCCCC),
                lineHeight = 22.sp
            )
        }
    }
}
