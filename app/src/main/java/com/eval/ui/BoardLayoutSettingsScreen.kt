package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Board layout settings screen for configuring chess board appearance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardLayoutSettingsScreen(
    boardLayoutSettings: BoardLayoutSettings,
    onBackToSettings: () -> Unit,
    onBackToGame: () -> Unit,
    onSave: (BoardLayoutSettings) -> Unit
) {
    var showCoordinates by remember { mutableStateOf(boardLayoutSettings.showCoordinates) }
    var showLastMove by remember { mutableStateOf(boardLayoutSettings.showLastMove) }
    var playerBarMode by remember { mutableStateOf(boardLayoutSettings.playerBarMode) }
    var showRedBorderForPlayerToMove by remember { mutableStateOf(boardLayoutSettings.showRedBorderForPlayerToMove) }
    var whiteSquareColor by remember { mutableStateOf(boardLayoutSettings.whiteSquareColor) }
    var blackSquareColor by remember { mutableStateOf(boardLayoutSettings.blackSquareColor) }
    var whitePieceColor by remember { mutableStateOf(boardLayoutSettings.whitePieceColor) }
    var blackPieceColor by remember { mutableStateOf(boardLayoutSettings.blackPieceColor) }
    // Evaluation bar settings
    var evalBarPosition by remember { mutableStateOf(boardLayoutSettings.evalBarPosition) }
    var evalBarColor1 by remember { mutableStateOf(boardLayoutSettings.evalBarColor1) }
    var evalBarColor2 by remember { mutableStateOf(boardLayoutSettings.evalBarColor2) }
    var evalBarRange by remember { mutableStateOf(boardLayoutSettings.evalBarRange) }

    var playerBarModeExpanded by remember { mutableStateOf(false) }
    var evalBarPositionExpanded by remember { mutableStateOf(false) }
    var showWhiteSquareColorPicker by remember { mutableStateOf(false) }
    var showBlackSquareColorPicker by remember { mutableStateOf(false) }
    var showWhitePieceColorPicker by remember { mutableStateOf(false) }
    var showBlackPieceColorPicker by remember { mutableStateOf(false) }
    var showEvalBarColor1Picker by remember { mutableStateOf(false) }
    var showEvalBarColor2Picker by remember { mutableStateOf(false) }

    fun saveSettings(
        newShowCoordinates: Boolean = showCoordinates,
        newShowLastMove: Boolean = showLastMove,
        newPlayerBarMode: PlayerBarMode = playerBarMode,
        newShowRedBorderForPlayerToMove: Boolean = showRedBorderForPlayerToMove,
        newWhiteSquareColor: Long = whiteSquareColor,
        newBlackSquareColor: Long = blackSquareColor,
        newWhitePieceColor: Long = whitePieceColor,
        newBlackPieceColor: Long = blackPieceColor,
        newEvalBarPosition: EvalBarPosition = evalBarPosition,
        newEvalBarColor1: Long = evalBarColor1,
        newEvalBarColor2: Long = evalBarColor2,
        newEvalBarRange: Int = evalBarRange
    ) {
        onSave(BoardLayoutSettings(
            showCoordinates = newShowCoordinates,
            showLastMove = newShowLastMove,
            playerBarMode = newPlayerBarMode,
            showRedBorderForPlayerToMove = newShowRedBorderForPlayerToMove,
            whiteSquareColor = newWhiteSquareColor,
            blackSquareColor = newBlackSquareColor,
            whitePieceColor = newWhitePieceColor,
            blackPieceColor = newBlackPieceColor,
            evalBarPosition = newEvalBarPosition,
            evalBarColor1 = newEvalBarColor1,
            evalBarColor2 = newEvalBarColor2,
            evalBarRange = newEvalBarRange
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
            title = "Board layout",
            onBackClick = onBackToSettings,
            onEvalClick = onBackToGame
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Show coordinates toggle
        SettingsToggle(
            label = "Show coordinates",
            checked = showCoordinates,
            onCheckedChange = {
                showCoordinates = it
                saveSettings(newShowCoordinates = it)
            }
        )

        // Show last move toggle
        SettingsToggle(
            label = "Show last move",
            checked = showLastMove,
            onCheckedChange = {
                showLastMove = it
                saveSettings(newShowLastMove = it)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Player bar(s) dropdown
        ExposedDropdownMenuBox(
            expanded = playerBarModeExpanded,
            onExpandedChange = { playerBarModeExpanded = it }
        ) {
            val displayText = when (playerBarMode) {
                PlayerBarMode.NONE -> "None"
                PlayerBarMode.TOP -> "Top"
                PlayerBarMode.BOTTOM -> "Bottom"
                PlayerBarMode.BOTH -> "Both"
            }
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                label = { Text("Player bar(s)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = playerBarModeExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = playerBarModeExpanded, onDismissRequest = { playerBarModeExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        playerBarMode = PlayerBarMode.NONE
                        playerBarModeExpanded = false
                        saveSettings(newPlayerBarMode = PlayerBarMode.NONE)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Top") },
                    onClick = {
                        playerBarMode = PlayerBarMode.TOP
                        playerBarModeExpanded = false
                        saveSettings(newPlayerBarMode = PlayerBarMode.TOP)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Bottom") },
                    onClick = {
                        playerBarMode = PlayerBarMode.BOTTOM
                        playerBarModeExpanded = false
                        saveSettings(newPlayerBarMode = PlayerBarMode.BOTTOM)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Both") },
                    onClick = {
                        playerBarMode = PlayerBarMode.BOTH
                        playerBarModeExpanded = false
                        saveSettings(newPlayerBarMode = PlayerBarMode.BOTH)
                    }
                )
            }
        }

        // Red border for player to move - only visible when player bar mode is not NONE
        if (playerBarMode != PlayerBarMode.NONE) {
            SettingsToggle(
                label = "Red border for player to move",
                checked = showRedBorderForPlayerToMove,
                onCheckedChange = {
                    showRedBorderForPlayerToMove = it
                    saveSettings(newShowRedBorderForPlayerToMove = it)
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // White squares color picker
        ColorSettingRow(
            label = "White squares color",
            color = Color(whiteSquareColor.toInt()),
            onClick = { showWhiteSquareColorPicker = true }
        )

        // Black squares color picker
        ColorSettingRow(
            label = "Black squares color",
            color = Color(blackSquareColor.toInt()),
            onClick = { showBlackSquareColorPicker = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // White pieces color picker
        ColorSettingRow(
            label = "White pieces color",
            color = Color(whitePieceColor.toInt()),
            onClick = { showWhitePieceColorPicker = true }
        )

        // Black pieces color picker
        ColorSettingRow(
            label = "Black pieces color",
            color = Color(blackPieceColor.toInt()),
            onClick = { showBlackPieceColorPicker = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ===== EVALUATION BAR CARD =====
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
                    text = "Evaluation bar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // Show evaluation bar dropdown
                ExposedDropdownMenuBox(
                    expanded = evalBarPositionExpanded,
                    onExpandedChange = { evalBarPositionExpanded = it }
                ) {
                    val displayText = when (evalBarPosition) {
                        EvalBarPosition.NONE -> "None"
                        EvalBarPosition.LEFT -> "Left"
                        EvalBarPosition.RIGHT -> "Right"
                    }
                    OutlinedTextField(
                        value = displayText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Show evaluation bar") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = evalBarPositionExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = evalBarPositionExpanded, onDismissRequest = { evalBarPositionExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                evalBarPosition = EvalBarPosition.NONE
                                evalBarPositionExpanded = false
                                saveSettings(newEvalBarPosition = EvalBarPosition.NONE)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Left") },
                            onClick = {
                                evalBarPosition = EvalBarPosition.LEFT
                                evalBarPositionExpanded = false
                                saveSettings(newEvalBarPosition = EvalBarPosition.LEFT)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Right") },
                            onClick = {
                                evalBarPosition = EvalBarPosition.RIGHT
                                evalBarPositionExpanded = false
                                saveSettings(newEvalBarPosition = EvalBarPosition.RIGHT)
                            }
                        )
                    }
                }

                // Only show other settings if eval bar is visible
                if (evalBarPosition != EvalBarPosition.NONE) {
                    // Color 1 (score color)
                    ColorSettingRow(
                        label = "Color 1 (score)",
                        color = Color(evalBarColor1.toInt()),
                        onClick = { showEvalBarColor1Picker = true }
                    )

                    // Color 2 (filler color)
                    ColorSettingRow(
                        label = "Color 2 (filler)",
                        color = Color(evalBarColor2.toInt()),
                        onClick = { showEvalBarColor2Picker = true }
                    )

                    // Range stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Range (pawns)", color = Color.White)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (evalBarRange > 1) {
                                        evalBarRange -= 1
                                        saveSettings(newEvalBarRange = evalBarRange)
                                    }
                                },
                                enabled = evalBarRange > 1,
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("-")
                            }
                            Text(
                                text = evalBarRange.toString(),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(24.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    if (evalBarRange < 10) {
                                        evalBarRange += 1
                                        saveSettings(newEvalBarRange = evalBarRange)
                                    }
                                },
                                enabled = evalBarRange < 10,
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("+")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reset to defaults button
        Button(
            onClick = {
                // Reset all values to defaults
                showCoordinates = true
                showLastMove = true
                playerBarMode = PlayerBarMode.BOTH
                showRedBorderForPlayerToMove = false
                whiteSquareColor = DEFAULT_WHITE_SQUARE_COLOR
                blackSquareColor = DEFAULT_BLACK_SQUARE_COLOR
                whitePieceColor = DEFAULT_WHITE_PIECE_COLOR
                blackPieceColor = DEFAULT_BLACK_PIECE_COLOR
                evalBarPosition = EvalBarPosition.RIGHT
                evalBarColor1 = DEFAULT_EVAL_BAR_COLOR_1
                evalBarColor2 = DEFAULT_EVAL_BAR_COLOR_2
                evalBarRange = 5
                // Save the default settings
                onSave(BoardLayoutSettings())
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset to defaults")
        }

    }

    // Color picker dialogs
    if (showWhiteSquareColorPicker) {
        ColorPickerDialog(
            currentColor = whiteSquareColor,
            title = "White squares color",
            onColorSelected = { color ->
                whiteSquareColor = color
                saveSettings(newWhiteSquareColor = color)
            },
            onDismiss = { showWhiteSquareColorPicker = false }
        )
    }

    if (showBlackSquareColorPicker) {
        ColorPickerDialog(
            currentColor = blackSquareColor,
            title = "Black squares color",
            onColorSelected = { color ->
                blackSquareColor = color
                saveSettings(newBlackSquareColor = color)
            },
            onDismiss = { showBlackSquareColorPicker = false }
        )
    }

    if (showWhitePieceColorPicker) {
        ColorPickerDialog(
            currentColor = whitePieceColor,
            title = "White pieces color",
            onColorSelected = { color ->
                whitePieceColor = color
                saveSettings(newWhitePieceColor = color)
            },
            onDismiss = { showWhitePieceColorPicker = false }
        )
    }

    if (showBlackPieceColorPicker) {
        ColorPickerDialog(
            currentColor = blackPieceColor,
            title = "Black pieces color",
            onColorSelected = { color ->
                blackPieceColor = color
                saveSettings(newBlackPieceColor = color)
            },
            onDismiss = { showBlackPieceColorPicker = false }
        )
    }

    if (showEvalBarColor1Picker) {
        ColorPickerDialog(
            currentColor = evalBarColor1,
            title = "Evaluation bar color 1 (score)",
            onColorSelected = { color ->
                evalBarColor1 = color
                saveSettings(newEvalBarColor1 = color)
            },
            onDismiss = { showEvalBarColor1Picker = false }
        )
    }

    if (showEvalBarColor2Picker) {
        ColorPickerDialog(
            currentColor = evalBarColor2,
            title = "Evaluation bar color 2 (filler)",
            onColorSelected = { color ->
                evalBarColor2 = color
                saveSettings(newEvalBarColor2 = color)
            },
            onDismiss = { showEvalBarColor2Picker = false }
        )
    }
}

@Composable
private fun ColorSettingRow(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .border(2.dp, Color.White, RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun SettingsToggle(
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
