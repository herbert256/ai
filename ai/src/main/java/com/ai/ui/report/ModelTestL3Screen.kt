package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ModelTestRunState
import com.ai.data.TestStatus
import com.ai.data.modelTestKey
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents

/**
 * L3 of the Test-all-models drill-in: one model's test result —
 * pass/fail, error message, latency, cost, trace link, and the
 * model's actual reply to the "Reply with exactly: OK" probe.
 * Prev/Next arrows step through the same provider-scoped list L2
 * shows.
 */
@Composable
internal fun ModelTestL3Screen(
    run: ModelTestRunState?,
    providerId: String,
    model: String,
    actions: ModelTestActions,
    onStepModel: (String) -> Unit,
    onBack: () -> Unit
) {
    val item = run?.items?.get(modelTestKey(providerId, model))
    val service = AppService.findById(providerId)

    if (item == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(
                helpTopic = "test_all_models_l3",
                title = "Test all models - model",
                onBackClick = onBack
            )
            Text("Test result no longer exists.", color = AppColors.TextTertiary)
        }
        return
    }

    // Provider-scoped list for prev/next — same ordering as L2.
    val siblings = remember(run, providerId) {
        (run.itemsForProvider(providerId)).sortedWith(
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
    val curIdx = siblings.indexOfFirst { it.key == item.key }
    val prev = if (curIdx > 0) siblings[curIdx - 1] else null
    val next = if (curIdx in 0 until siblings.size - 1) siblings[curIdx + 1] else null

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "test_all_models_l3",
            title = "Test all models - model",
            subject = model,
            onBackClick = onBack,
            onInfo = service?.let { svc -> { actions.onNavigateToModelInfo(svc, model) } },
            onTrace = if (ApiTracer.isTracingEnabled && item.traceFilename != null) {
                { actions.onNavigateToTraceFile(item.traceFilename!!) }
            } else null
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Provider: ${service?.id ?: providerId}", color = AppColors.TextSecondary, fontSize = 13.sp)
            Text("Model: $model", color = AppColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            // Status line.
            when (item.status) {
                TestStatus.PASS -> Text("✅ Passed", color = AppColors.Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                TestStatus.FAIL -> Text("❌ Failed", color = AppColors.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                TestStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedHourglass(fontSize = 16.sp)
                    Text("  Running…", color = AppColors.Orange, fontSize = 15.sp)
                }
                TestStatus.PENDING -> Text("🕓 Queued", color = AppColors.TextTertiary, fontSize = 15.sp)
            }

            if (!item.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(item.errorMessage, color = AppColors.Red, fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))
            item.durationMs?.let {
                Text("Latency: $it ms", color = AppColors.TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            if (item.totalCost > 0.0) {
                Text(
                    "Cost: ${formatCents(item.totalCost, decimals = 4)} ¢",
                    color = AppColors.TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Prompt", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                com.ai.data.AnalysisRepository.TEST_PROMPT,
                fontSize = 13.sp, color = Color.White
            )

            if (!item.responseText.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Response", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(item.responseText, fontSize = 13.sp, color = Color.White)
            }
        }

        // Prev / Next arrows.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(
                onClick = { prev?.let { onStepModel(it.model) } },
                enabled = prev != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("← Prev", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Spacer(Modifier.padding(horizontal = 4.dp))
            Button(
                onClick = { next?.let { onStepModel(it.model) } },
                enabled = next != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Next →", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }
}
