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
 * Arrow settings screen for configuring move arrow display options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrowSettingsScreen(
    stockfishSettings: StockfishSettings,
    onBackToSettings: () -> Unit,
    onBackToGame: () -> Unit,
    onSave: (StockfishSettings) -> Unit
) {
    var arrowMode by remember { mutableStateOf(stockfishSettings.manualStage.arrowMode) }
    // Main line settings
    var numArrows by remember { mutableStateOf(stockfishSettings.manualStage.numArrows) }
    var showArrowNumbers by remember { mutableStateOf(stockfishSettings.manualStage.showArrowNumbers) }
    var whiteArrowColor by remember { mutableStateOf(stockfishSettings.manualStage.whiteArrowColor) }
    var blackArrowColor by remember { mutableStateOf(stockfishSettings.manualStage.blackArrowColor) }
    // Multi lines settings
    var multiLinesArrowColor by remember { mutableStateOf(stockfishSettings.manualStage.multiLinesArrowColor) }

    // Dialog states
    var showWhiteColorPicker by remember { mutableStateOf(false) }
    var showBlackColorPicker by remember { mutableStateOf(false) }
    var showMultiLinesColorPicker by remember { mutableStateOf(false) }

    // Dropdown states
    var arrowModeExpanded by remember { mutableStateOf(false) }
    var numArrowsExpanded by remember { mutableStateOf(false) }
    var showArrowNumbersExpanded by remember { mutableStateOf(false) }

    val numArrowsOptions = listOf(1, 2, 3, 4, 5, 6, 7, 8)
    val arrowModeOptions = listOf(
        ArrowMode.NONE to "None",
        ArrowMode.MAIN_LINE to "Main line",
        ArrowMode.MULTI_LINES to "Multi lines"
    )

    fun saveSettings(
        newArrowMode: ArrowMode = arrowMode,
        newNumArrows: Int = numArrows,
        newShowArrowNumbers: Boolean = showArrowNumbers,
        newWhiteArrowColor: Long = whiteArrowColor,
        newBlackArrowColor: Long = blackArrowColor,
        newMultiLinesArrowColor: Long = multiLinesArrowColor
    ) {
        onSave(stockfishSettings.copy(
            manualStage = stockfishSettings.manualStage.copy(
                arrowMode = newArrowMode,
                numArrows = newNumArrows,
                showArrowNumbers = newShowArrowNumbers,
                whiteArrowColor = newWhiteArrowColor,
                blackArrowColor = newBlackArrowColor,
                multiLinesArrowColor = newMultiLinesArrowColor
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
            title = "Arrow settings",
            onBackClick = onBackToSettings,
            onEvalClick = onBackToGame
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ===== CARD 1: Arrow Mode (no title) =====
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
                // Arrow mode dropdown
                ExposedDropdownMenuBox(
                    expanded = arrowModeExpanded,
                    onExpandedChange = { arrowModeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = arrowModeOptions.find { it.first == arrowMode }?.second ?: "Main line",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Draw arrows") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = arrowModeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = arrowModeExpanded,
                        onDismissRequest = { arrowModeExpanded = false }
                    ) {
                        arrowModeOptions.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    arrowMode = mode
                                    arrowModeExpanded = false
                                    saveSettings(newArrowMode = mode)
                                }
                            )
                        }
                    }
                }
            }
        }

        // ===== CARD 2: Main Line Settings =====
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
                    text = "Main line",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // Number of arrows dropdown
                ExposedDropdownMenuBox(
                    expanded = numArrowsExpanded,
                    onExpandedChange = { numArrowsExpanded = it }
                ) {
                    OutlinedTextField(
                        value = numArrows.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Number of arrows") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = numArrowsExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = numArrowsExpanded,
                        onDismissRequest = { numArrowsExpanded = false }
                    ) {
                        numArrowsOptions.forEach { num ->
                            DropdownMenuItem(
                                text = { Text(num.toString()) },
                                onClick = {
                                    numArrows = num
                                    numArrowsExpanded = false
                                    saveSettings(newNumArrows = num)
                                }
                            )
                        }
                    }
                }

                // Show move number in arrow dropdown
                ExposedDropdownMenuBox(
                    expanded = showArrowNumbersExpanded,
                    onExpandedChange = { showArrowNumbersExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (showArrowNumbers) "Yes" else "No",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Show move number in arrow") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showArrowNumbersExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showArrowNumbersExpanded,
                        onDismissRequest = { showArrowNumbersExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Yes") },
                            onClick = {
                                showArrowNumbers = true
                                showArrowNumbersExpanded = false
                                saveSettings(newShowArrowNumbers = true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("No") },
                            onClick = {
                                showArrowNumbers = false
                                showArrowNumbersExpanded = false
                                saveSettings(newShowArrowNumbers = false)
                            }
                        )
                    }
                }

                // Color picker for white move arrows
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("White move color")
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(whiteArrowColor.toInt()))
                            .border(2.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .clickable { showWhiteColorPicker = true }
                    )
                }

                // Color picker for black move arrows
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Black move color")
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(blackArrowColor.toInt()))
                            .border(2.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .clickable { showBlackColorPicker = true }
                    )
                }
            }
        }

        // ===== CARD 3: Multi Lines Settings =====
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
                    text = "Multi lines",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // Color picker for multi lines arrows
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Arrow color")
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(multiLinesArrowColor.toInt()))
                            .border(2.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .clickable { showMultiLinesColorPicker = true }
                    )
                }
            }
        }

    }

    // Color picker dialogs
    if (showWhiteColorPicker) {
        ColorPickerDialog(
            currentColor = whiteArrowColor,
            title = "Arrow color for white moves",
            onColorSelected = { color ->
                whiteArrowColor = color
                saveSettings(newWhiteArrowColor = color)
            },
            onDismiss = { showWhiteColorPicker = false }
        )
    }

    if (showBlackColorPicker) {
        ColorPickerDialog(
            currentColor = blackArrowColor,
            title = "Arrow color for black moves",
            onColorSelected = { color ->
                blackArrowColor = color
                saveSettings(newBlackArrowColor = color)
            },
            onDismiss = { showBlackColorPicker = false }
        )
    }

    if (showMultiLinesColorPicker) {
        ColorPickerDialog(
            currentColor = multiLinesArrowColor,
            title = "Arrow color for multi lines",
            onColorSelected = { color ->
                multiLinesArrowColor = color
                saveSettings(newMultiLinesArrowColor = color)
            },
            onDismiss = { showMultiLinesColorPicker = false }
        )
    }
}
