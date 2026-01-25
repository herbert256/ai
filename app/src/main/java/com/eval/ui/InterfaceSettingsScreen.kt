package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Reusable toggle row for interface visibility settings.
 */
@Composable
private fun VisibilityToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Interface visibility settings screen for configuring which UI elements are shown in each stage.
 */
@Composable
fun InterfaceSettingsScreen(
    interfaceVisibility: InterfaceVisibilitySettings,
    onBackToSettings: () -> Unit,
    onBackToGame: () -> Unit,
    onSave: (InterfaceVisibilitySettings) -> Unit
) {
    // Preview Stage visibility state
    var previewShowScoreBarsGraph by remember { mutableStateOf(interfaceVisibility.previewStage.showScoreBarsGraph) }
    var previewShowResultBar by remember { mutableStateOf(interfaceVisibility.previewStage.showResultBar) }
    var previewShowBoard by remember { mutableStateOf(interfaceVisibility.previewStage.showBoard) }
    var previewShowMoveList by remember { mutableStateOf(interfaceVisibility.previewStage.showMoveList) }
    var previewShowPgn by remember { mutableStateOf(interfaceVisibility.previewStage.showPgn) }

    // Analyse Stage visibility state
    var analyseShowScoreLineGraph by remember { mutableStateOf(interfaceVisibility.analyseStage.showScoreLineGraph) }
    var analyseShowScoreBarsGraph by remember { mutableStateOf(interfaceVisibility.analyseStage.showScoreBarsGraph) }
    var analyseShowBoard by remember { mutableStateOf(interfaceVisibility.analyseStage.showBoard) }
    var analyseShowStockfishAnalyse by remember { mutableStateOf(interfaceVisibility.analyseStage.showStockfishAnalyse) }
    var analyseShowResultBar by remember { mutableStateOf(interfaceVisibility.analyseStage.showResultBar) }
    var analyseShowMoveList by remember { mutableStateOf(interfaceVisibility.analyseStage.showMoveList) }
    var analyseShowGameInfo by remember { mutableStateOf(interfaceVisibility.analyseStage.showGameInfo) }
    var analyseShowPgn by remember { mutableStateOf(interfaceVisibility.analyseStage.showPgn) }

    // Manual Stage visibility state
    var manualShowResultBar by remember { mutableStateOf(interfaceVisibility.manualStage.showResultBar) }
    var manualShowScoreLineGraph by remember { mutableStateOf(interfaceVisibility.manualStage.showScoreLineGraph) }
    var manualShowScoreBarsGraph by remember { mutableStateOf(interfaceVisibility.manualStage.showScoreBarsGraph) }
    var manualShowTimeGraph by remember { mutableStateOf(interfaceVisibility.manualStage.showTimeGraph) }
    var manualShowOpeningExplorer by remember { mutableStateOf(interfaceVisibility.manualStage.showOpeningExplorer) }
    var manualShowOpeningName by remember { mutableStateOf(interfaceVisibility.manualStage.showOpeningName) }
    var manualShowRawStockfishScore by remember { mutableStateOf(interfaceVisibility.manualStage.showRawStockfishScore) }
    var manualShowMoveList by remember { mutableStateOf(interfaceVisibility.manualStage.showMoveList) }
    var manualShowGameInfo by remember { mutableStateOf(interfaceVisibility.manualStage.showGameInfo) }
    var manualShowPgn by remember { mutableStateOf(interfaceVisibility.manualStage.showPgn) }

    fun saveAllSettings() {
        onSave(InterfaceVisibilitySettings(
            previewStage = PreviewStageVisibility(
                showScoreBarsGraph = previewShowScoreBarsGraph,
                showResultBar = previewShowResultBar,
                showBoard = previewShowBoard,
                showMoveList = previewShowMoveList,
                showPgn = previewShowPgn
            ),
            analyseStage = AnalyseStageVisibility(
                showScoreLineGraph = analyseShowScoreLineGraph,
                showScoreBarsGraph = analyseShowScoreBarsGraph,
                showBoard = analyseShowBoard,
                showStockfishAnalyse = analyseShowStockfishAnalyse,
                showResultBar = analyseShowResultBar,
                showMoveList = analyseShowMoveList,
                showGameInfo = analyseShowGameInfo,
                showPgn = analyseShowPgn
            ),
            manualStage = ManualStageVisibility(
                showResultBar = manualShowResultBar,
                showScoreLineGraph = manualShowScoreLineGraph,
                showScoreBarsGraph = manualShowScoreBarsGraph,
                showTimeGraph = manualShowTimeGraph,
                showOpeningExplorer = manualShowOpeningExplorer,
                showOpeningName = manualShowOpeningName,
                showRawStockfishScore = manualShowRawStockfishScore,
                showMoveList = manualShowMoveList,
                showGameInfo = manualShowGameInfo,
                showPgn = manualShowPgn
            )
        ))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EvalTitleBar(
            title = "Interface elements",
            onBackClick = onBackToSettings,
            onEvalClick = onBackToGame
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ===== PREVIEW STAGE CARD =====
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
                Text(
                    text = "Preview Stage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Text(
                    text = "Always shown: Score Line graph, Game Information",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                VisibilityToggle(
                    label = "Score Bars graph",
                    checked = previewShowScoreBarsGraph,
                    onCheckedChange = {
                        previewShowScoreBarsGraph = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Result bar",
                    checked = previewShowResultBar,
                    onCheckedChange = {
                        previewShowResultBar = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Board",
                    checked = previewShowBoard,
                    onCheckedChange = {
                        previewShowBoard = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Move list",
                    checked = previewShowMoveList,
                    onCheckedChange = {
                        previewShowMoveList = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "PGN",
                    checked = previewShowPgn,
                    onCheckedChange = {
                        previewShowPgn = it
                        saveAllSettings()
                    }
                )
            }
        }

        // ===== ANALYSE STAGE CARD =====
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
                Text(
                    text = "Analyse Stage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                VisibilityToggle(
                    label = "Score Line graph",
                    checked = analyseShowScoreLineGraph,
                    onCheckedChange = {
                        analyseShowScoreLineGraph = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Score Bars graph",
                    checked = analyseShowScoreBarsGraph,
                    onCheckedChange = {
                        analyseShowScoreBarsGraph = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Board",
                    checked = analyseShowBoard,
                    onCheckedChange = {
                        analyseShowBoard = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Stockfish Analyse",
                    checked = analyseShowStockfishAnalyse,
                    onCheckedChange = {
                        analyseShowStockfishAnalyse = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Result bar",
                    checked = analyseShowResultBar,
                    onCheckedChange = {
                        analyseShowResultBar = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Move list",
                    checked = analyseShowMoveList,
                    onCheckedChange = {
                        analyseShowMoveList = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Game Information",
                    checked = analyseShowGameInfo,
                    onCheckedChange = {
                        analyseShowGameInfo = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "PGN",
                    checked = analyseShowPgn,
                    onCheckedChange = {
                        analyseShowPgn = it
                        saveAllSettings()
                    }
                )
            }
        }

        // ===== MANUAL STAGE CARD =====
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
                Text(
                    text = "Manual Stage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Text(
                    text = "Always shown: Board, Navigation bar, Stockfish",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                VisibilityToggle(
                    label = "Result bar",
                    checked = manualShowResultBar,
                    onCheckedChange = {
                        manualShowResultBar = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Score Line graph",
                    checked = manualShowScoreLineGraph,
                    onCheckedChange = {
                        manualShowScoreLineGraph = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Score Bars graph",
                    checked = manualShowScoreBarsGraph,
                    onCheckedChange = {
                        manualShowScoreBarsGraph = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Time Usage graph",
                    checked = manualShowTimeGraph,
                    onCheckedChange = {
                        manualShowTimeGraph = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Opening Explorer",
                    checked = manualShowOpeningExplorer,
                    onCheckedChange = {
                        manualShowOpeningExplorer = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Opening Name",
                    checked = manualShowOpeningName,
                    onCheckedChange = {
                        manualShowOpeningName = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Raw Stockfish score",
                    checked = manualShowRawStockfishScore,
                    onCheckedChange = {
                        manualShowRawStockfishScore = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Move list",
                    checked = manualShowMoveList,
                    onCheckedChange = {
                        manualShowMoveList = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "Game Information",
                    checked = manualShowGameInfo,
                    onCheckedChange = {
                        manualShowGameInfo = it
                        saveAllSettings()
                    }
                )

                VisibilityToggle(
                    label = "PGN",
                    checked = manualShowPgn,
                    onCheckedChange = {
                        manualShowPgn = it
                        saveAllSettings()
                    }
                )
            }
        }

    }
}
