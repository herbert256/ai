package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eval.data.OpeningExplorerMove
import com.eval.data.OpeningExplorerResponse

/**
 * Panel displaying Opening Explorer statistics for the current position.
 */
@Composable
fun OpeningExplorerPanel(
    explorerData: OpeningExplorerResponse?,
    isLoading: Boolean,
    onMoveClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Opening Explorer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF6B9BFF)
                    )
                }
            }

            if (explorerData != null) {
                // Overall stats bar
                val total = explorerData.white + explorerData.draws + explorerData.black
                if (total > 0) {
                    // Opening name if available
                    explorerData.opening?.let { opening ->
                        Text(
                            text = "${opening.eco}: ${opening.name}",
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA)
                        )
                    }

                    // Stats bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                    ) {
                        val whitePercent = explorerData.white.toFloat() / total
                        val drawPercent = explorerData.draws.toFloat() / total
                        val blackPercent = explorerData.black.toFloat() / total

                        // White wins
                        Box(
                            modifier = Modifier
                                .weight(whitePercent.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFFE0E0E0), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                        )
                        // Draws
                        if (drawPercent > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .weight(drawPercent)
                                    .fillMaxHeight()
                                    .background(Color(0xFF808080))
                            )
                        }
                        // Black wins
                        Box(
                            modifier = Modifier
                                .weight(blackPercent.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFF404040), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                        )
                    }

                    // Stats text
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "White: ${(explorerData.white * 100 / total)}%",
                            fontSize = 11.sp,
                            color = Color(0xFFCCCCCC)
                        )
                        Text(
                            text = "Draw: ${(explorerData.draws * 100 / total)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF888888)
                        )
                        Text(
                            text = "Black: ${(explorerData.black * 100 / total)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = "($total games)",
                            fontSize = 11.sp,
                            color = Color(0xFF555555)
                        )
                    }
                }

                // Top moves
                if (explorerData.moves.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Popular moves:",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )

                    explorerData.moves.take(5).forEach { move ->
                        OpeningMoveRow(
                            move = move,
                            onClick = { onMoveClick(move.uci) }
                        )
                    }
                }
            } else if (!isLoading) {
                Text(
                    text = "No opening data available",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}

@Composable
private fun OpeningMoveRow(
    move: OpeningExplorerMove,
    onClick: () -> Unit
) {
    val total = move.white + move.draws + move.black
    val winRate = if (total > 0) (move.white * 100 / total) else 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Move notation
        Text(
            text = move.san,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF6B9BFF),
            modifier = Modifier.width(60.dp)
        )

        // Mini stats bar
        Row(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .background(Color(0xFF252525), RoundedCornerShape(2.dp))
        ) {
            val whitePercent = move.white.toFloat() / total.coerceAtLeast(1)
            val drawPercent = move.draws.toFloat() / total.coerceAtLeast(1)
            val blackPercent = move.black.toFloat() / total.coerceAtLeast(1)

            if (whitePercent > 0.01f) {
                Box(
                    modifier = Modifier
                        .weight(whitePercent)
                        .fillMaxHeight()
                        .background(Color(0xFFCCCCCC))
                )
            }
            if (drawPercent > 0.01f) {
                Box(
                    modifier = Modifier
                        .weight(drawPercent)
                        .fillMaxHeight()
                        .background(Color(0xFF666666))
                )
            }
            if (blackPercent > 0.01f) {
                Box(
                    modifier = Modifier
                        .weight(blackPercent)
                        .fillMaxHeight()
                        .background(Color(0xFF333333))
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Game count
        Text(
            text = "$total",
            fontSize = 11.sp,
            color = Color(0xFF555555),
            modifier = Modifier.width(40.dp)
        )
    }
}
