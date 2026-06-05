package com.ai.ui.report.manage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared live-batch stats strip — used by every running-batch screen
 * (Fan Out, Fan Meta, Tournament, Compare, Judge the judges,
 * Translation) so they all render an identical two-row label / value
 * block. Canonical column order:
 *
 *   Total · Done · Error · Run · [Bench] · Wait · Queue · Costs
 *
 * **Bench** appears only on fixed-model batches (Fan Out, Judge the
 * judges) where a benched model can't be substituted; the worker-swarm
 * batches fold benched cells into Error and omit the column. Each
 * caller builds the `(label, value, color)` triples in that order.
 */
@Composable
internal fun BatchStatsRow(stats: List<Triple<String, String, Color>>) {
    Column(
        // Bleed the stats block ~14dp into the screen's 16dp side
        // padding on each side — the 7–8 columns are tight, so the
        // stats get the extra width while the rest of the page keeps
        // its margins. Reports the un-widened width to the parent so
        // siblings still stack normally.
        modifier = Modifier.layout { measurable, constraints ->
            val extra = 28.dp.roundToPx()
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = constraints.maxWidth + extra,
                    maxWidth = constraints.maxWidth + extra
                )
            )
            layout(constraints.maxWidth, placeable.height) {
                placeable.place(-extra / 2, 0)
            }
        }
    ) {
        Row(Modifier.fillMaxWidth()) {
            stats.forEach { (label, _, color) ->
                Text(
                    label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center,
                    maxLines = 1, modifier = Modifier.weight(1f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            stats.forEach { (_, value, color) ->
                Text(
                    value, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
