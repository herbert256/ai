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

/** Housekeeping → Test → Stress test. Confirm → submit one AI report per
 *  Example Prompt with swarm "Level 2", fire-and-forget (they generate
 *  concurrently in the background). Existing runtime data is left untouched.
 *  The whole run lives on [StressTestEngine]; this screen just drives it and
 *  reflects its state. */
@Composable
fun StressTestScreen(
    engine: StressTestEngine,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onStarted: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val state by engine.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "stress_test",
            title = "Stress test",
            subject = "Report every example prompt with swarm \"Level 2\"",
            onBackClick = onBack,
            onSettings = onSettings
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val s = state
            when (s?.phase) {
                StressTestEngine.Phase.SUBMITTING -> {
                    Text("Submitting ${s.total} report${if (s.total == 1) "" else "s"}…", color = Color.White, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                StressTestEngine.Phase.DONE -> {
                    Text(
                        "Submitted ${s.total} report${if (s.total == 1) "" else "s"} — generating in the background.",
                        color = AppColors.Green, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Open the reports list to watch them fill in. They run concurrently — this is the stress load.",
                        color = AppColors.TextSecondary, fontSize = 13.sp
                    )
                }
                StressTestEngine.Phase.ERROR -> {
                    Text("Stress test could not run", color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(s.errorMessage ?: "Unknown error", color = AppColors.TextSecondary, fontSize = 13.sp)
                }
                null -> {
                    Text("Runs an end-to-end stress test:", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Submits one AI report per Example Prompt, each using the models of swarm " +
                            "\"Level 2\". The reports are fired off all at once and generate concurrently in " +
                            "the background — this screen finishes as soon as they're submitted.\n\n" +
                            "Existing runtime data (reports, chats, etc.) is left untouched — the new reports " +
                            "are added alongside it.",
                        color = AppColors.TextSecondary, fontSize = 13.sp
                    )
                }
            }

            // Start / Run-again — hidden only during the brief submit window.
            val running = s?.phase == StressTestEngine.Phase.SUBMITTING
            if (!running) {
                Button(
                    onClick = { showConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
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
                    "Generates one report per Example Prompt with swarm \"Level 2\", all at once. " +
                        "This can be a lot of API calls. Existing data is left untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; engine.start(context); onStarted() }) {
                    Text("Start", color = AppColors.Green, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
