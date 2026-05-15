package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ModelTestRunState
import com.ai.data.ModelTestState
import com.ai.data.TestStatus
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents

/**
 * L2 of the Test-all-models drill-in: every configured model of one
 * provider, each row showing its test status. Tapping a row opens L3.
 */
@Composable
internal fun ModelTestL2Screen(
    run: ModelTestRunState?,
    providerId: String,
    actions: ModelTestActions,
    onOpenModel: (String) -> Unit,
    onBack: () -> Unit
) {
    val service = AppService.findById(providerId)
    val subject = service?.id ?: providerId

    // Display order: running/queued → errored → done, then by model
    // name within each group.
    val rows: List<ModelTestState> = remember(run, providerId) {
        (run?.itemsForProvider(providerId) ?: emptyList()).sortedWith(
            compareBy(
                { p ->
                    when (p.status) {
                        TestStatus.RUNNING, TestStatus.PENDING -> 0
                        TestStatus.FAIL -> 1
                        TestStatus.PASS -> 2
                    }
                },
                { p -> p.model.lowercase() }
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "test_all_models_l2",
            title = "Test all models - provider",
            onBackClick = onBack,
            onInfo = service?.let { svc -> { actions.onNavigateToModelInfo(svc, svc.defaultModel) } }
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No models for this provider in the run.", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
        } else {
            val rowsTotalCost = rows.sumOf { it.totalCost }
            val allDone = rows.isNotEmpty() && rows.all { it.status == TestStatus.PASS }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onOpenModel(p.model) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!allDone) {
                            val icon = when (p.status) {
                                TestStatus.FAIL -> "❌"
                                TestStatus.PASS -> "✅"
                                TestStatus.RUNNING -> "⏳"
                                TestStatus.PENDING -> "🕓"
                            }
                            if (icon == "⏳") {
                                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                    AnimatedHourglass(fontSize = 16.sp)
                                }
                            } else {
                                Text(icon, fontSize = 16.sp, modifier = Modifier.width(20.dp))
                            }
                        }
                        Text(
                            p.model,
                            fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                        if (p.totalCost > 0.0) {
                            Text(
                                formatCents(p.totalCost), fontSize = 11.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                if (rowsTotalCost > 0.0) {
                    item(key = "l2-total-footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(20.dp))
                            Text(
                                "Total", fontSize = 14.sp, color = AppColors.Blue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                            Text(
                                formatCents(rowsTotalCost), fontSize = 11.sp,
                                color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
