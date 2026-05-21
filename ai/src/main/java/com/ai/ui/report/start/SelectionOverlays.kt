package com.ai.ui.report.start
import com.ai.ui.report.manage.*
import com.ai.ui.other.*
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wrapper around [ReportSelectFromReportScreen] for the +Report
 *  flow's previous-report picker overlay. Hosts the model-list
 *  conversion (Agent → ReportModel, with deletion fallback) plus
 *  the per-row 🔧 / 👁 callback wiring pulled from
 *  [com.ai.ui.shared.LocalReportListIconBundle]. Extracted out of
 *  [ReportsScreen] so the overlay block stays out of that
 *  function's bytecode — which already sits at the JVM 64 KB
 *  per-method ceiling. */
@Composable
internal fun ReportSelectFromReportOverlay(
    aiSettings: Settings,
    onClose: () -> Unit,
    onCommit: (List<ReportModel>) -> Unit
) {
    val rowIcons = com.ai.ui.shared.LocalReportListIconBundle.current
    val onNavigateHome = com.ai.ui.shared.LocalNavigateHome.current
    ReportSelectFromReportScreen(
        onConfirm = { report ->
            // Saved-agent path preserves provenance ("via swarm X");
            // fall back to a direct (provider, model) ReportModel
            // entry when the agent has been deleted since the
            // report ran.
            val copied = report.agents.mapNotNull { ra ->
                val savedAgent = aiSettings.getAgentById(ra.agentId)
                if (savedAgent != null) expandAgentToModel(savedAgent, aiSettings)
                else AppService.findById(ra.provider)?.let { prov ->
                    toReportModel(prov, ra.model)
                }
            }
            onCommit(copied)
            onClose()
        },
        onBack = onClose,
        onNavigateHome = onNavigateHome,
        // Per-row 🔧 / 👁 jump to the referenced report — close the
        // picker first so back from the destination pops to the
        // +Report flow's surrounding screen, not the picker overlay.
        onOpenReportManage = { rid ->
            onClose()
            rowIcons.onOpenManage(rid)
        },
        onOpenReportView = { rid ->
            onClose()
            rowIcons.onOpenView(rid)
        }
    )
}

/**
 * The +Add selection-overlay waterfall (Flock / Agent / Swarm /
 * Provider / per-provider model / All models / FromReport), lifted out
 * of [ReportsScreen] to keep that method under the JVM 64 KB ceiling.
 * Reads/writes picker state through [ReportsScreenState] so no state
 * threading is needed. Returns true when an overlay rendered (the
 * caller then `return`s, mirroring the in-line `return` short-circuits).
 */
@Composable
internal fun SelectionOverlayDialogs(
    st: ReportsScreenState,
    aiSettings: Settings,
    recentReportPairs: List<Pair<AppService, String>>,
    recentReportModels: List<String>,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onNavigateToFlocksEdit: () -> Unit,
    onNavigateToAgentsEdit: () -> Unit,
    onNavigateToSwarmsEdit: () -> Unit,
    onNavigateHome: () -> Unit,
    onRecordRecentReportModel: (String, String) -> Unit
): Boolean {
    var models by st.models
    var findIconsModels by st.findIconsModels
    var translationModels by st.translationModels
    var showSelectFlock by st.showSelectFlock
    var showSelectAgent by st.showSelectAgent
    var showSelectSwarm by st.showSelectSwarm
    var showSelectProvider by st.showSelectProvider
    var pendingProvider by st.pendingProvider
    var showSelectAllModels by st.showSelectAllModels
    var showSelectFromReport by st.showSelectFromReport
    val pickerTarget by st.pickerTarget

    // Deposit picked rows into whichever list the active picker is
    // bound to. The same overlays serve the New-Report SelectionPhase
    // and the Find-icons picker; [pickerTarget] is what decides.
    val addToActiveTarget: (List<ReportModel>) -> Unit = { added ->
        when (pickerTarget) {
            PickerTarget.NEW_REPORT -> models = deduplicateModels(models + added)
            PickerTarget.FIND_ICONS -> findIconsModels = deduplicateModels(findIconsModels + added)
            PickerTarget.TRANSLATION -> translationModels = deduplicateModels(translationModels + added)
        }
    }

    if (showSelectFlock) {
        ReportSelectFlockScreen(
            aiSettings = aiSettings,
            onSelectFlock = {
                addToActiveTarget(expandFlockToModels(it, aiSettings))
                showSelectFlock = false
            },
            onNavigateToModelInfo = onNavigateToModelInfo,
            onBack = { showSelectFlock = false },
            onEditFlocks = onNavigateToFlocksEdit
        )
        return true
    }
    if (showSelectAgent) {
        ReportSelectAgentScreen(
            aiSettings = aiSettings,
            onSelectAgent = {
                expandAgentToModel(it, aiSettings)?.let { m -> addToActiveTarget(listOf(m)) }
                showSelectAgent = false
            },
            onBack = { showSelectAgent = false },
            onEditAgents = onNavigateToAgentsEdit
        )
        return true
    }
    if (showSelectSwarm) {
        ReportSelectSwarmScreen(
            aiSettings = aiSettings,
            onSelectSwarm = {
                addToActiveTarget(expandSwarmToModels(it, aiSettings))
                showSelectSwarm = false
            },
            onNavigateToModelInfo = onNavigateToModelInfo,
            onBack = { showSelectSwarm = false },
            onEditSwarms = onNavigateToSwarmsEdit
        )
        return true
    }
    if (showSelectProvider) { ReportSelectProviderDialog(aiSettings, onSelectProvider = { pendingProvider = it; showSelectProvider = false }, onDismiss = { showSelectProvider = false }); return true }
    if (pendingProvider != null) {
        val prov = pendingProvider!!
        val recentForProv = remember(recentReportModels, prov) {
            recentReportPairs.filter { it.first == prov }.map { it.second }
        }
        ReportSelectModelDialog(
            prov, aiSettings,
            onSelectModel = {
                addToActiveTarget(listOf(toReportModel(prov, it)))
                pendingProvider = null
            },
            onDismiss = { pendingProvider = null },
            recentModels = recentForProv,
            onRecordRecent = { onRecordRecentReportModel(prov.id, it) }
        )
        return true
    }
    if (showSelectAllModels) {
        val activeList = when (pickerTarget) {
            PickerTarget.FIND_ICONS -> findIconsModels
            PickerTarget.TRANSLATION -> translationModels
            PickerTarget.NEW_REPORT -> models
        }
        val already = remember(activeList) { activeList.map { it.provider to it.model }.toSet() }
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            alreadyAdded = already,
            recentEntries = recentReportPairs,
            onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
            onConfirm = { (prov, m) ->
                addToActiveTarget(listOf(toReportModel(prov, m)))
                showSelectAllModels = false
            },
            onBack = { showSelectAllModels = false },
            onNavigateHome = onNavigateHome
        )
        return true
    }
    if (showSelectFromReport) {
        ReportSelectFromReportOverlay(
            aiSettings = aiSettings,
            onClose = { showSelectFromReport = false },
            onCommit = { copied -> addToActiveTarget(copied) }
        )
        return true
    }
    return false
}
