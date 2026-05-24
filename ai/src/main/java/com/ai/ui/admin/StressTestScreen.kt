package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.StressTestEngine

/** Housekeeping → Test → Stress test. Confirm → wipe runtime data → one
 *  AI report per Example Prompt with swarm "Level 2", sequentially. The
 *  whole run lives on [StressTestEngine]; this screen just drives it and
 *  reflects its state. */
@Composable
fun StressTestScreen(
    engine: StressTestEngine,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val state by engine.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "stress_test",
            title = "Stress test",
            subject = "Wipe runtime data, then report every example prompt with swarm \"Level 2\"",
            onBackClick = onBack,
            onSettings = onSettings
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val s = state
            when (s?.phase) {
                StressTestEngine.Phase.CLEARING -> {
                    Text("Clearing runtime data…", color = Color.White, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                StressTestEngine.Phase.GENERATING -> {
                    Text("Generating report ${s.current} / ${s.total}", color = Color.White, fontWeight = FontWeight.Bold)
                    if (s.currentTitle.isNotBlank()) {
                        Text(s.currentTitle, color = AppColors.TextSecondary, fontSize = 13.sp)
                    }
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.current.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { engine.cancel() },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                    ) { Text("Stop", maxLines = 1, softWrap = false) }
                }
                StressTestEngine.Phase.DONE -> {
                    Text(
                        "Stress test finished — ${s.total} report${if (s.total == 1) "" else "s"} created.",
                        color = AppColors.Green, fontWeight = FontWeight.SemiBold
                    )
                }
                StressTestEngine.Phase.ERROR -> {
                    Text("Stress test could not run", color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(s.errorMessage ?: "Unknown error", color = AppColors.TextSecondary, fontSize = 13.sp)
                }
                null -> {
                    Text("Runs a full end-to-end stress test:", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "1. Clears ALL runtime data (logs, chats, traces, reports, prompt history, " +
                            "usage stats, test runs) — exactly like Housekeeping → Reset → Clear runtime data.\n\n" +
                            "2. Generates one AI report per Example Prompt, each using the models of swarm " +
                            "\"Level 2\", one at a time.\n\n" +
                            "Configuration (providers, agents, swarms, prompts, keys) is preserved.",
                        color = AppColors.TextSecondary, fontSize = 13.sp
                    )
                }
            }

            // Start / Run-again — hidden while a run is in flight.
            val running = s?.phase == StressTestEngine.Phase.CLEARING || s?.phase == StressTestEngine.Phase.GENERATING
            if (!running) {
                Button(
                    onClick = { showConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                ) {
                    Text(
                        if (s?.phase == StressTestEngine.Phase.DONE || s?.phase == StressTestEngine.Phase.ERROR)
                            "Run again" else "Start stress test",
                        maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Start stress test?") },
            text = {
                Text(
                    "This DELETES all runtime data (reports, chats, traces, logs, prompt history, " +
                        "usage stats), then generates one report per Example Prompt with swarm \"Level 2\". " +
                        "This can be a lot of API calls."
                )
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; engine.start(context) }) {
                    Text("Start", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
