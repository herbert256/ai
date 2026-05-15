package com.ai.ui.report

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ai.data.AppService
import com.ai.viewmodel.ModelTestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Navigation state inside the "Test all models" flow. Mirrors
 * [FanOutNav]'s sealed-class + Saver + BackHandler + when-routing
 * shape.
 *  - [L1]: every active provider, the stats panel + progress bar +
 *    the "Test all models" button.
 *  - [Select]: the provider picker shown before a fresh run starts.
 *  - [L2]: one provider's full configured model list.
 *  - [L3]: one model's test result detail.
 */
sealed class ModelTestNav {
    object L1 : ModelTestNav()
    object Select : ModelTestNav()
    data class L2(val providerId: String) : ModelTestNav()
    data class L3(val providerId: String, val model: String) : ModelTestNav()
}

/** Saver — serialises to a 3-string list so rememberSaveable
 *  survives rotation + process death. */
private val modelTestNavSaver: Saver<ModelTestNav, Any> = Saver(
    save = { nav ->
        when (nav) {
            is ModelTestNav.L1 -> listOf("L1", "", "")
            is ModelTestNav.Select -> listOf("Select", "", "")
            is ModelTestNav.L2 -> listOf("L2", nav.providerId, "")
            is ModelTestNav.L3 -> listOf("L3", nav.providerId, nav.model)
        }
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val l = list as List<String>
        when (l[0]) {
            "Select" -> ModelTestNav.Select
            "L2" -> ModelTestNav.L2(l[1])
            "L3" -> ModelTestNav.L3(l[1], l[2])
            else -> ModelTestNav.L1
        }
    }
)

/** Bundle of callbacks the L1/L2/L3 screens need, packed into one
 *  parameter so each screen's signature stays small (mirrors
 *  [FanOutActions]). */
data class ModelTestActions(
    val onStartRun: (Set<String>) -> Unit = {},
    val onCancelRun: () -> Unit = {},
    val onCheckRun: () -> Unit = {},
    val onNavigateToTraceFile: (String) -> Unit = {},
    val onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
)

/**
 * Parent of the Test-all-models drill-in. Watches the
 * [ModelTestEngine]'s run + throttled-keys flows, holds the
 * [ModelTestNav] state, and routes to one of the three level
 * Composables. Reached from Housekeeping → Test → "Test all models".
 */
@Composable
fun ModelTestScreen(
    engine: ModelTestEngine,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val run by engine.run.collectAsState()
    val throttledKeys by engine.throttledKeys.collectAsState()

    // Hydrate on first entry so L1 shows the last persisted run.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { engine.hydrate(context) }
    }

    var nav by rememberSaveable(stateSaver = modelTestNavSaver) {
        mutableStateOf<ModelTestNav>(ModelTestNav.L1)
    }
    BackHandler {
        nav = when (val n = nav) {
            ModelTestNav.L1 -> { onBack(); return@BackHandler }
            is ModelTestNav.Select -> ModelTestNav.L1
            is ModelTestNav.L2 -> ModelTestNav.L1
            is ModelTestNav.L3 -> ModelTestNav.L2(n.providerId)
        }
    }

    val actions = ModelTestActions(
        onStartRun = { ids -> engine.startRun(context, ids) },
        onCancelRun = { engine.cancel(context) },
        onCheckRun = {
            val msg = when (engine.resumeRun(context)) {
                ModelTestEngine.ResumeOutcome.ALREADY_RUNNING -> "Test run is active — still running"
                ModelTestEngine.ResumeOutcome.RESUMED -> "Test run had stalled — restarted the unfinished models"
                ModelTestEngine.ResumeOutcome.ALREADY_COMPLETE -> "Test run already finished"
                ModelTestEngine.ResumeOutcome.NO_RUN -> "No test run to check"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        },
        onNavigateToTraceFile = onNavigateToTraceFile,
        onNavigateToModelInfo = onNavigateToModelInfo
    )

    when (val n = nav) {
        is ModelTestNav.L1 -> ModelTestL1Screen(
            run = run,
            throttledKeys = throttledKeys,
            actions = actions,
            onOpenProvider = { pid -> nav = ModelTestNav.L2(pid) },
            onOpenSelect = { nav = ModelTestNav.Select },
            onBack = onBack
        )
        is ModelTestNav.Select -> {
            val providers = remember { engine.testableProviders() }
            ModelTestSelectScreen(
                providers = providers,
                onStart = { ids ->
                    actions.onStartRun(ids)
                    nav = ModelTestNav.L1
                },
                onBack = { nav = ModelTestNav.L1 }
            )
        }
        is ModelTestNav.L2 -> ModelTestL2Screen(
            run = run,
            providerId = n.providerId,
            actions = actions,
            onOpenModel = { model -> nav = ModelTestNav.L3(n.providerId, model) },
            onBack = { nav = ModelTestNav.L1 }
        )
        is ModelTestNav.L3 -> ModelTestL3Screen(
            run = run,
            providerId = n.providerId,
            model = n.model,
            actions = actions,
            onStepModel = { model -> nav = ModelTestNav.L3(n.providerId, model) },
            onBack = { nav = ModelTestNav.L2(n.providerId) }
        )
    }
}
