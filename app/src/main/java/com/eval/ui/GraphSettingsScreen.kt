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
 * Graph settings screen for configuring evaluation graph appearance.
 */
@Composable
fun GraphSettingsScreen(
    graphSettings: GraphSettings,
    onBackToSettings: () -> Unit,
    onBackToGame: () -> Unit,
    onSave: (GraphSettings) -> Unit
) {
    var plusScoreColor by remember { mutableStateOf(graphSettings.plusScoreColor) }
    var negativeScoreColor by remember { mutableStateOf(graphSettings.negativeScoreColor) }
    var backgroundColor by remember { mutableStateOf(graphSettings.backgroundColor) }
    var analyseLineColor by remember { mutableStateOf(graphSettings.analyseLineColor) }
    var verticalLineColor by remember { mutableStateOf(graphSettings.verticalLineColor) }
    var lineGraphRange by remember { mutableStateOf(graphSettings.lineGraphRange) }
    var barGraphRange by remember { mutableStateOf(graphSettings.barGraphRange) }
    var lineGraphScale by remember { mutableStateOf(graphSettings.lineGraphScale) }
    var barGraphScale by remember { mutableStateOf(graphSettings.barGraphScale) }

    val scaleValues = listOf(50, 75, 100, 150, 200, 250, 300)

    var showPlusScoreColorPicker by remember { mutableStateOf(false) }
    var showNegativeScoreColorPicker by remember { mutableStateOf(false) }
    var showBackgroundColorPicker by remember { mutableStateOf(false) }
    var showAnalyseLineColorPicker by remember { mutableStateOf(false) }
    var showVerticalLineColorPicker by remember { mutableStateOf(false) }

    fun saveSettings(
        newPlusScoreColor: Long = plusScoreColor,
        newNegativeScoreColor: Long = negativeScoreColor,
        newBackgroundColor: Long = backgroundColor,
        newAnalyseLineColor: Long = analyseLineColor,
        newVerticalLineColor: Long = verticalLineColor,
        newLineGraphRange: Int = lineGraphRange,
        newBarGraphRange: Int = barGraphRange,
        newLineGraphScale: Int = lineGraphScale,
        newBarGraphScale: Int = barGraphScale
    ) {
        onSave(GraphSettings(
            plusScoreColor = newPlusScoreColor,
            negativeScoreColor = newNegativeScoreColor,
            backgroundColor = newBackgroundColor,
            analyseLineColor = newAnalyseLineColor,
            verticalLineColor = newVerticalLineColor,
            lineGraphRange = newLineGraphRange,
            barGraphRange = newBarGraphRange,
            lineGraphScale = newLineGraphScale,
            barGraphScale = newBarGraphScale
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
            title = "Graph settings",
            onBackClick = onBackToSettings,
            onEvalClick = onBackToGame
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Colors card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Colors",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // Plus score color picker
                ColorSettingRow(
                    label = "Plus score color",
                    color = Color(plusScoreColor.toInt()),
                    onClick = { showPlusScoreColorPicker = true }
                )

                // Negative score color picker
                ColorSettingRow(
                    label = "Negative score color",
                    color = Color(negativeScoreColor.toInt()),
                    onClick = { showNegativeScoreColorPicker = true }
                )

                // Background color picker
                ColorSettingRow(
                    label = "Background color",
                    color = Color(backgroundColor.toInt()),
                    onClick = { showBackgroundColorPicker = true }
                )

                // Analyse line color picker
                ColorSettingRow(
                    label = "Line color in Analyse stage",
                    color = Color(analyseLineColor.toInt()),
                    onClick = { showAnalyseLineColorPicker = true }
                )

                // Vertical line color picker
                ColorSettingRow(
                    label = "Vertical line color",
                    color = Color(verticalLineColor.toInt()),
                    onClick = { showVerticalLineColorPicker = true }
                )
            }
        }

        // Ranges card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Ranges",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // Line graph range
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Graph one (line) range",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (lineGraphRange > 1) {
                                    lineGraphRange -= 1
                                    saveSettings(newLineGraphRange = lineGraphRange)
                                }
                            },
                            enabled = lineGraphRange > 1,
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("-")
                        }
                        Text(
                            text = lineGraphRange.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = {
                                if (lineGraphRange < 10) {
                                    lineGraphRange += 1
                                    saveSettings(newLineGraphRange = lineGraphRange)
                                }
                            },
                            enabled = lineGraphRange < 10,
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("+")
                        }
                    }
                }

                // Bar graph range
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Graph two (bar) range",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (barGraphRange > 1) {
                                    barGraphRange -= 1
                                    saveSettings(newBarGraphRange = barGraphRange)
                                }
                            },
                            enabled = barGraphRange > 1,
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("-")
                        }
                        Text(
                            text = barGraphRange.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = {
                                if (barGraphRange < 10) {
                                    barGraphRange += 1
                                    saveSettings(newBarGraphRange = barGraphRange)
                                }
                            },
                            enabled = barGraphRange < 10,
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("+")
                        }
                    }
                }
            }
        }

        // Scale card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Scale",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // Line graph scale
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Graph one (line) scale",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val currentIndex = scaleValues.indexOf(lineGraphScale)
                                if (currentIndex > 0) {
                                    lineGraphScale = scaleValues[currentIndex - 1]
                                    saveSettings(newLineGraphScale = lineGraphScale)
                                }
                            },
                            enabled = lineGraphScale > scaleValues.first(),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("-")
                        }
                        Text(
                            text = "$lineGraphScale%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(48.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = {
                                val currentIndex = scaleValues.indexOf(lineGraphScale)
                                if (currentIndex < scaleValues.size - 1) {
                                    lineGraphScale = scaleValues[currentIndex + 1]
                                    saveSettings(newLineGraphScale = lineGraphScale)
                                }
                            },
                            enabled = lineGraphScale < scaleValues.last(),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("+")
                        }
                    }
                }

                // Bar graph scale
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Graph two (bar) scale",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val currentIndex = scaleValues.indexOf(barGraphScale)
                                if (currentIndex > 0) {
                                    barGraphScale = scaleValues[currentIndex - 1]
                                    saveSettings(newBarGraphScale = barGraphScale)
                                }
                            },
                            enabled = barGraphScale > scaleValues.first(),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("-")
                        }
                        Text(
                            text = "$barGraphScale%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(48.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = {
                                val currentIndex = scaleValues.indexOf(barGraphScale)
                                if (currentIndex < scaleValues.size - 1) {
                                    barGraphScale = scaleValues[currentIndex + 1]
                                    saveSettings(newBarGraphScale = barGraphScale)
                                }
                            },
                            enabled = barGraphScale < scaleValues.last(),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("+")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reset to defaults button
        Button(
            onClick = {
                plusScoreColor = DEFAULT_GRAPH_PLUS_SCORE_COLOR
                negativeScoreColor = DEFAULT_GRAPH_NEGATIVE_SCORE_COLOR
                backgroundColor = DEFAULT_GRAPH_BACKGROUND_COLOR
                analyseLineColor = DEFAULT_GRAPH_ANALYSE_LINE_COLOR
                verticalLineColor = DEFAULT_GRAPH_VERTICAL_LINE_COLOR
                lineGraphRange = 7
                barGraphRange = 3
                lineGraphScale = 100
                barGraphScale = 100
                onSave(GraphSettings())
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
    if (showPlusScoreColorPicker) {
        ColorPickerDialog(
            currentColor = plusScoreColor,
            title = "Plus score color",
            onColorSelected = { color ->
                plusScoreColor = color
                saveSettings(newPlusScoreColor = color)
            },
            onDismiss = { showPlusScoreColorPicker = false }
        )
    }

    if (showNegativeScoreColorPicker) {
        ColorPickerDialog(
            currentColor = negativeScoreColor,
            title = "Negative score color",
            onColorSelected = { color ->
                negativeScoreColor = color
                saveSettings(newNegativeScoreColor = color)
            },
            onDismiss = { showNegativeScoreColorPicker = false }
        )
    }

    if (showBackgroundColorPicker) {
        ColorPickerDialog(
            currentColor = backgroundColor,
            title = "Background color",
            onColorSelected = { color ->
                backgroundColor = color
                saveSettings(newBackgroundColor = color)
            },
            onDismiss = { showBackgroundColorPicker = false }
        )
    }

    if (showAnalyseLineColorPicker) {
        ColorPickerDialog(
            currentColor = analyseLineColor,
            title = "Line color in Analyse stage",
            onColorSelected = { color ->
                analyseLineColor = color
                saveSettings(newAnalyseLineColor = color)
            },
            onDismiss = { showAnalyseLineColorPicker = false }
        )
    }

    if (showVerticalLineColorPicker) {
        ColorPickerDialog(
            currentColor = verticalLineColor,
            title = "Vertical line color",
            onColorSelected = { color ->
                verticalLineColor = color
                saveSettings(newVerticalLineColor = color)
            },
            onDismiss = { showVerticalLineColorPicker = false }
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
