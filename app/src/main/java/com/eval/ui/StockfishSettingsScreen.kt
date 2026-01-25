package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable stepper component for settings.
 */
@Composable
private fun SettingStepper(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDecrement,
                enabled = canDecrement,
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color(0xFF444444)
                )
            ) {
                Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onIncrement,
                enabled = canIncrement,
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color(0xFF444444)
                )
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Stockfish settings screen for configuring engine parameters for all analysis stages.
 */
@Composable
fun StockfishSettingsScreen(
    stockfishSettings: StockfishSettings,
    onBackToSettings: () -> Unit,
    onBackToGame: () -> Unit,
    onSave: (StockfishSettings) -> Unit
) {
    // Preview Stage state
    var previewSeconds by remember { mutableStateOf(stockfishSettings.previewStage.secondsForMove) }
    var previewThreads by remember { mutableStateOf(stockfishSettings.previewStage.threads) }
    var previewHash by remember { mutableStateOf(stockfishSettings.previewStage.hashMb) }
    var previewNnue by remember { mutableStateOf(stockfishSettings.previewStage.useNnue) }

    // Analyse Stage state
    var analyseSeconds by remember { mutableStateOf(stockfishSettings.analyseStage.secondsForMove) }
    var analyseThreads by remember { mutableStateOf(stockfishSettings.analyseStage.threads) }
    var analyseHash by remember { mutableStateOf(stockfishSettings.analyseStage.hashMb) }
    var analyseNnue by remember { mutableStateOf(stockfishSettings.analyseStage.useNnue) }

    // Manual Stage state
    var manualDepth by remember { mutableStateOf(stockfishSettings.manualStage.depth) }
    var manualThreads by remember { mutableStateOf(stockfishSettings.manualStage.threads) }
    var manualHash by remember { mutableStateOf(stockfishSettings.manualStage.hashMb) }
    var manualMultiPv by remember { mutableStateOf(stockfishSettings.manualStage.multiPv) }
    var manualNnue by remember { mutableStateOf(stockfishSettings.manualStage.useNnue) }

    // Options for steppers
    val previewSecondsOptions = listOf(0.01f, 0.05f, 0.10f, 0.25f, 0.50f)
    val previewThreadsOptions = (1..4).toList()
    val previewHashOptions = listOf(8, 16, 64)

    val analyseSecondsOptions = listOf(0.50f, 0.75f, 1.00f, 1.50f, 2.50f, 5.00f, 10.00f)
    val analyseThreadsOptions = (1..8).toList()
    val analyseHashOptions = listOf(16, 64, 96, 128, 192, 256)

    val manualDepthOptions = (16..64 step 2).toList()
    val manualThreadsOptions = (1..16).toList()
    val manualHashOptions = listOf(32, 64, 96, 128, 192, 256, 384, 512)
    val manualMultiPvOptions = (1..32).toList()

    fun saveAllSettings() {
        onSave(stockfishSettings.copy(
            previewStage = PreviewStageSettings(
                secondsForMove = previewSeconds,
                threads = previewThreads,
                hashMb = previewHash,
                useNnue = previewNnue
            ),
            analyseStage = AnalyseStageSettings(
                secondsForMove = analyseSeconds,
                threads = analyseThreads,
                hashMb = analyseHash,
                useNnue = analyseNnue
            ),
            manualStage = stockfishSettings.manualStage.copy(
                depth = manualDepth,
                threads = manualThreads,
                hashMb = manualHash,
                multiPv = manualMultiPv,
                useNnue = manualNnue
            )
        ))
    }

    // Helper functions for stepping through list options
    fun <T> stepInList(current: T, options: List<T>, delta: Int): T {
        val currentIndex = options.indexOf(current)
        if (currentIndex == -1) return options.first()
        val newIndex = (currentIndex + delta).coerceIn(0, options.lastIndex)
        return options[newIndex]
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
            title = "Stockfish",
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

                // Seconds for move
                SettingStepper(
                    label = "Seconds for move",
                    value = String.format("%.2f s", previewSeconds),
                    onDecrement = {
                        previewSeconds = stepInList(previewSeconds, previewSecondsOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        previewSeconds = stepInList(previewSeconds, previewSecondsOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = previewSecondsOptions.indexOf(previewSeconds) > 0,
                    canIncrement = previewSecondsOptions.indexOf(previewSeconds) < previewSecondsOptions.lastIndex
                )

                // Number of threads
                SettingStepper(
                    label = "Number of threads",
                    value = previewThreads.toString(),
                    onDecrement = {
                        previewThreads = stepInList(previewThreads, previewThreadsOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        previewThreads = stepInList(previewThreads, previewThreadsOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = previewThreadsOptions.indexOf(previewThreads) > 0,
                    canIncrement = previewThreadsOptions.indexOf(previewThreads) < previewThreadsOptions.lastIndex
                )

                // Hash memory
                SettingStepper(
                    label = "Hash memory (MB)",
                    value = "$previewHash MB",
                    onDecrement = {
                        previewHash = stepInList(previewHash, previewHashOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        previewHash = stepInList(previewHash, previewHashOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = previewHashOptions.indexOf(previewHash) > 0,
                    canIncrement = previewHashOptions.indexOf(previewHash) < previewHashOptions.lastIndex
                )

                // Use NNUE toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use NNUE", color = Color.White)
                    Switch(
                        checked = previewNnue,
                        onCheckedChange = {
                            previewNnue = it
                            saveAllSettings()
                        }
                    )
                }
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

                // Seconds for move
                SettingStepper(
                    label = "Seconds for move",
                    value = String.format("%.2f s", analyseSeconds),
                    onDecrement = {
                        analyseSeconds = stepInList(analyseSeconds, analyseSecondsOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        analyseSeconds = stepInList(analyseSeconds, analyseSecondsOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = analyseSecondsOptions.indexOf(analyseSeconds) > 0,
                    canIncrement = analyseSecondsOptions.indexOf(analyseSeconds) < analyseSecondsOptions.lastIndex
                )

                // Number of threads
                SettingStepper(
                    label = "Number of threads",
                    value = analyseThreads.toString(),
                    onDecrement = {
                        analyseThreads = stepInList(analyseThreads, analyseThreadsOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        analyseThreads = stepInList(analyseThreads, analyseThreadsOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = analyseThreadsOptions.indexOf(analyseThreads) > 0,
                    canIncrement = analyseThreadsOptions.indexOf(analyseThreads) < analyseThreadsOptions.lastIndex
                )

                // Hash memory
                SettingStepper(
                    label = "Hash memory (MB)",
                    value = "$analyseHash MB",
                    onDecrement = {
                        analyseHash = stepInList(analyseHash, analyseHashOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        analyseHash = stepInList(analyseHash, analyseHashOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = analyseHashOptions.indexOf(analyseHash) > 0,
                    canIncrement = analyseHashOptions.indexOf(analyseHash) < analyseHashOptions.lastIndex
                )

                // Use NNUE toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use NNUE", color = Color.White)
                    Switch(
                        checked = analyseNnue,
                        onCheckedChange = {
                            analyseNnue = it
                            saveAllSettings()
                        }
                    )
                }
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

                // Depth
                SettingStepper(
                    label = "Depth",
                    value = manualDepth.toString(),
                    onDecrement = {
                        manualDepth = stepInList(manualDepth, manualDepthOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        manualDepth = stepInList(manualDepth, manualDepthOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = manualDepthOptions.indexOf(manualDepth) > 0,
                    canIncrement = manualDepthOptions.indexOf(manualDepth) < manualDepthOptions.lastIndex
                )

                // Number of threads
                SettingStepper(
                    label = "Number of threads",
                    value = manualThreads.toString(),
                    onDecrement = {
                        manualThreads = stepInList(manualThreads, manualThreadsOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        manualThreads = stepInList(manualThreads, manualThreadsOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = manualThreadsOptions.indexOf(manualThreads) > 0,
                    canIncrement = manualThreadsOptions.indexOf(manualThreads) < manualThreadsOptions.lastIndex
                )

                // Hash memory
                SettingStepper(
                    label = "Hash memory (MB)",
                    value = "$manualHash MB",
                    onDecrement = {
                        manualHash = stepInList(manualHash, manualHashOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        manualHash = stepInList(manualHash, manualHashOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = manualHashOptions.indexOf(manualHash) > 0,
                    canIncrement = manualHashOptions.indexOf(manualHash) < manualHashOptions.lastIndex
                )

                // MultiPV lines
                SettingStepper(
                    label = "MultiPV lines",
                    value = manualMultiPv.toString(),
                    onDecrement = {
                        manualMultiPv = stepInList(manualMultiPv, manualMultiPvOptions, -1)
                        saveAllSettings()
                    },
                    onIncrement = {
                        manualMultiPv = stepInList(manualMultiPv, manualMultiPvOptions, 1)
                        saveAllSettings()
                    },
                    canDecrement = manualMultiPvOptions.indexOf(manualMultiPv) > 0,
                    canIncrement = manualMultiPvOptions.indexOf(manualMultiPv) < manualMultiPvOptions.lastIndex
                )

                // Use NNUE toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use NNUE", color = Color.White)
                    Switch(
                        checked = manualNnue,
                        onCheckedChange = {
                            manualNnue = it
                            saveAllSettings()
                        }
                    )
                }
            }
        }

    }
}
