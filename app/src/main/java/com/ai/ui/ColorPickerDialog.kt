package com.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Color picker dialog with hue/saturation grid, brightness slider, and opacity slider.
 */
@Composable
fun ColorPickerDialog(
    currentColor: Long,
    title: String,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // Extract HSV from current color
    val initialColor = Color(currentColor.toInt())
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (initialColor.red * 255).toInt(),
        (initialColor.green * 255).toInt(),
        (initialColor.blue * 255).toInt(),
        hsv
    )

    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var brightness by remember { mutableStateOf(hsv[2]) }
    var alpha by remember { mutableStateOf(initialColor.alpha) }

    // Compute current color from HSV
    val currentHsvColor = remember(hue, saturation, brightness, alpha) {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        Color(
            red = android.graphics.Color.red(rgb) / 255f,
            green = android.graphics.Color.green(rgb) / 255f,
            blue = android.graphics.Color.blue(rgb) / 255f,
            alpha = alpha
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Color preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentHsvColor)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                )

                // Hue/Saturation picker (2D grid)
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    hue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                                    saturation = (1f - offset.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ ->
                                    val offset = change.position
                                    hue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                                    saturation = (1f - offset.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        // Draw hue/saturation gradient
                        val step = 4f
                        for (x in 0 until size.width.toInt() step step.toInt()) {
                            for (y in 0 until size.height.toInt() step step.toInt()) {
                                val h = x / size.width * 360f
                                val s = 1f - y / size.height
                                val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, brightness))
                                drawRect(
                                    color = Color(rgb),
                                    topLeft = Offset(x.toFloat(), y.toFloat()),
                                    size = androidx.compose.ui.geometry.Size(step, step)
                                )
                            }
                        }
                        // Draw crosshair at current position
                        val crossX = hue / 360f * size.width
                        val crossY = (1f - saturation) * size.height
                        drawCircle(Color.White, 8f, Offset(crossX, crossY))
                        drawCircle(Color.Black, 6f, Offset(crossX, crossY))
                    }
                }

                // Brightness slider
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    brightness = (offset.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ ->
                                    brightness = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        // Draw brightness gradient
                        for (x in 0 until size.width.toInt()) {
                            val b = x / size.width
                            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, b))
                            drawLine(
                                Color(rgb),
                                Offset(x.toFloat(), 0f),
                                Offset(x.toFloat(), size.height),
                                strokeWidth = 1f
                            )
                        }
                        // Draw indicator
                        val indicatorX = brightness * size.width
                        drawLine(Color.White, Offset(indicatorX, 0f), Offset(indicatorX, size.height), 3f)
                        drawLine(Color.Black, Offset(indicatorX, 0f), Offset(indicatorX, size.height), 1f)
                    }
                }

                // Alpha/Opacity slider
                Text("Opacity", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    alpha = (offset.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ ->
                                    alpha = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        // Draw checkerboard background for transparency
                        val checkSize = 8f
                        for (x in 0 until (size.width / checkSize).toInt()) {
                            for (y in 0 until (size.height / checkSize).toInt()) {
                                val isLight = (x + y) % 2 == 0
                                drawRect(
                                    if (isLight) Color.White else Color.LightGray,
                                    Offset(x * checkSize, y * checkSize),
                                    androidx.compose.ui.geometry.Size(checkSize, checkSize)
                                )
                            }
                        }
                        // Draw alpha gradient
                        val baseRgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
                        val baseColor = Color(baseRgb)
                        for (x in 0 until size.width.toInt()) {
                            val a = x / size.width
                            drawLine(
                                baseColor.copy(alpha = a),
                                Offset(x.toFloat(), 0f),
                                Offset(x.toFloat(), size.height),
                                strokeWidth = 1f
                            )
                        }
                        // Draw indicator
                        val indicatorX = alpha * size.width
                        drawLine(Color.White, Offset(indicatorX, 0f), Offset(indicatorX, size.height), 3f)
                        drawLine(Color.Black, Offset(indicatorX, 0f), Offset(indicatorX, size.height), 1f)
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(onClick = {
                        // Convert to Long color value
                        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
                        val r = android.graphics.Color.red(rgb)
                        val g = android.graphics.Color.green(rgb)
                        val b = android.graphics.Color.blue(rgb)
                        val a = (alpha * 255).toInt()
                        val colorLong = ((a.toLong() and 0xFF) shl 24) or
                                ((r.toLong() and 0xFF) shl 16) or
                                ((g.toLong() and 0xFF) shl 8) or
                                (b.toLong() and 0xFF)
                        onColorSelected(colorLong)
                        onDismiss()
                    }) {
                        Text("Select")
                    }
                }
            }
        }
    }
}
