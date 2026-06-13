package com.ai.ui.report.manage
import com.ai.ui.report.other.TargetLanguage
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

/** Which list a +Add overlay confirm should write to. The same
 *  overlays (showSelectAgent / showSelectFlock / showSelectSwarm /
 *  showSelectAllModels / showSelectFromReport) are reused by the
 *  "Find icons" picker flow and the Translate picker; this enum
 *  tells their `onConfirm` whether to push the picked row into the
 *  New-Report `models` list, the Find-icons `findIconsModels` list,
 *  or the Translate `translationModels` list. */
internal enum class PickerTarget { NEW_REPORT, FIND_ICONS, TRANSLATION }

/** Saver for the [ExportLanguage] sealed type so the HTML-preview
 *  language survives rotation alongside its saveable sibling
 *  (htmlPreviewDetail) instead of resetting to All. */
internal val ExportLanguageSaver: Saver<ExportLanguage, String> = Saver(
    save = {
        when (it) {
            ExportLanguage.All -> "All"
            ExportLanguage.Original -> "Original"
            is ExportLanguage.Single -> "S:${it.englishName}"
        }
    },
    restore = {
        when {
            it == "Original" -> ExportLanguage.Original
            it.startsWith("S:") -> ExportLanguage.Single(it.removePrefix("S:"))
            else -> ExportLanguage.All
        }
    }
)

// ===== rememberSaveable savers =====
//
// These are needed so the multi-step picker / scope / overlay state
// in ReportScreen survives the back-pop hop through the Help screen
// (and any other Compose Navigation destination that removes the
// AI_REPORTS Composable from the composition while it's painted).
// Without these the user lands back at the report root mid-flow.

internal val InternalPromptSaver: Saver<InternalPrompt?, Any> = listSaver(
    save = { p ->
        if (p == null) emptyList()
        else listOf(p.id, p.name, p.reference, p.category, p.agent, p.text, p.title)
    },
    restore = { l ->
        if (l.isEmpty()) null
        else InternalPrompt(
            id = l[0] as String,
            name = l[1] as String,
            reference = l[2] as Boolean,
            category = l[3] as String,
            agent = l[4] as String,
            text = l[5] as String,
            title = l[6] as String
        )
    }
)

internal val SecondaryScopeSaver: Saver<SecondaryScope, String> = Saver(
    save = { it.encode() },
    restore = { SecondaryScope.decodeOrAllReports(it) }
)

internal val SecondaryLanguageScopeSaver: Saver<SecondaryLanguageScope, Any> = listSaver(
    save = { sls ->
        when (sls) {
            is SecondaryLanguageScope.AllPresent -> listOf("ALL")
            is SecondaryLanguageScope.Selected -> listOf("SEL") + sls.languages.toList()
        }
    },
    restore = { l ->
        when {
            l.isEmpty() -> SecondaryLanguageScope.AllPresent
            l[0] == "SEL" -> SecondaryLanguageScope.Selected(l.drop(1).filterIsInstance<String>().toSet())
            else -> SecondaryLanguageScope.AllPresent
        }
    }
)

internal val AppServiceSaver: Saver<AppService?, String> = Saver(
    save = { it?.id ?: "" },
    restore = { s -> if (s.isBlank()) null else AppService.findById(s) }
)

/** Saver for the "Find alternative translation" target so the picker /
 *  candidate overlay survives rotation + process death. */
internal val AltTranslateTargetSaver: Saver<AltTranslateTarget?, Any> = listSaver(
    save = { t ->
        if (t == null) emptyList()
        else listOf(
            t.reportId, t.runId, t.itemId, t.isTitleKind.toString(),
            t.sourceText, t.traceType, t.targetLanguageName, t.persistedRowId ?: ""
        )
    },
    restore = { l ->
        if (l.isEmpty()) null
        else AltTranslateTarget(
            reportId = l[0],
            runId = l[1],
            itemId = l[2],
            isTitleKind = l[3].toBoolean(),
            sourceText = l[4],
            traceType = l[5],
            targetLanguageName = l[6],
            persistedRowId = l[7].takeIf { it.isNotEmpty() }
        )
    }
)

/** Saver for the per-screen selected-models list (and the parallel
 *  Find-icons list). The list backs the SelectionPhase's selected-
 *  rows column, which is `modelInfoClickable` — tapping a row pops
 *  out to Model Info. That nav removes AI_REPORTS from the active
 *  composition; without a Saver, plain `remember { mutableStateOf }`
 *  loses the list and the user comes back to an empty picker. Each
 *  ReportModel is flattened to 10 strings; lists with un-resolvable
 *  provider ids (deleted between save and restore) drop those rows
 *  silently. */
internal val ReportModelListSaver: Saver<List<ReportModel>, Any> = listSaver(
    save = { list ->
        list.flatMap { m ->
            listOf(
                m.provider.id, m.model, m.type, m.sourceType, m.sourceName,
                m.sourceId ?: "", m.agentId ?: "", m.endpointId ?: "",
                m.agentApiKey ?: "", m.paramsIds.joinToString(",")
            )
        }
    },
    restore = { saved ->
        val out = mutableListOf<ReportModel>()
        var i = 0
        while (i + 10 <= saved.size) {
            val provider = AppService.findById(saved[i])
            if (provider != null) {
                out.add(
                    ReportModel(
                        provider = provider,
                        model = saved[i + 1],
                        type = saved[i + 2],
                        sourceType = saved[i + 3],
                        sourceName = saved[i + 4],
                        sourceId = saved[i + 5].takeIf { it.isNotEmpty() },
                        agentId = saved[i + 6].takeIf { it.isNotEmpty() },
                        endpointId = saved[i + 7].takeIf { it.isNotEmpty() },
                        agentApiKey = saved[i + 8].takeIf { it.isNotEmpty() },
                        paramsIds = saved[i + 9].takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
                    )
                )
            }
            i += 10
        }
        out.toList()
    }
)

/** Saver for the pre-generation [ReportWorkerConfig] draft on the
 *  "Report - select workers" step, so the picked modes + worker rows
 *  survive the nav hop through Help / Model Info like the model list
 *  above. Workers flatten to 5 strings each; the two list lengths ride
 *  in the header. */
internal val ReportWorkerConfigSaver: Saver<ReportWorkerConfig, Any> = listSaver(
    save = { cfg ->
        fun flat(ws: List<Worker>) = ws.flatMap { listOf(it.agent, it.provider, it.model, it.flock, it.swarm) }
        listOf(
            cfg.reportInfo.name, cfg.modelInfo.name, cfg.batches.name, cfg.workerSelection.name,
            cfg.metaBatches.name, cfg.metaWorkerSelection.name,
            cfg.reportInfoWorkers.size.toString(), cfg.batchWorkers.size.toString(), cfg.metaBatchWorkers.size.toString()
        ) + flat(cfg.reportInfoWorkers) + flat(cfg.batchWorkers) + flat(cfg.metaBatchWorkers)
    },
    restore = { saved ->
        fun enumOr(name: String, fallback: String) = name.ifBlank { fallback }
        fun workersAt(offset: Int, count: Int): List<Worker> = (0 until count).mapNotNull { k ->
            val i = offset + k * 5
            if (i + 5 <= saved.size) Worker(
                agent = saved[i], provider = saved[i + 1], model = saved[i + 2],
                flock = saved[i + 3], swarm = saved[i + 4]
            ) else null
        }
        val infoCount = saved.getOrNull(6)?.toIntOrNull() ?: 0
        val batchCount = saved.getOrNull(7)?.toIntOrNull() ?: 0
        val metaCount = saved.getOrNull(8)?.toIntOrNull() ?: 0
        ReportWorkerConfig(
            reportInfo = runCatching { ReportInfoMode.valueOf(enumOr(saved.getOrNull(0) ?: "", "PROMPT")) }.getOrDefault(ReportInfoMode.PROMPT),
            modelInfo = runCatching { ModelInfoMode.valueOf(enumOr(saved.getOrNull(1) ?: "", "PROMPT")) }.getOrDefault(ModelInfoMode.PROMPT),
            batches = runCatching { BatchWorkerMode.valueOf(enumOr(saved.getOrNull(2) ?: "", "PROMPT")) }.getOrDefault(BatchWorkerMode.PROMPT),
            workerSelection = runCatching { WorkerSelectionMode.valueOf(enumOr(saved.getOrNull(3) ?: "", "WHEN_AVAILABLE")) }.getOrDefault(WorkerSelectionMode.WHEN_AVAILABLE),
            metaBatches = runCatching { BatchWorkerMode.valueOf(enumOr(saved.getOrNull(4) ?: "", "PROMPT")) }.getOrDefault(BatchWorkerMode.PROMPT),
            metaWorkerSelection = runCatching { WorkerSelectionMode.valueOf(enumOr(saved.getOrNull(5) ?: "", "WHEN_AVAILABLE")) }.getOrDefault(WorkerSelectionMode.WHEN_AVAILABLE),
            reportInfoWorkers = workersAt(9, infoCount),
            batchWorkers = workersAt(9 + infoCount * 5, batchCount),
            metaBatchWorkers = workersAt(9 + infoCount * 5 + batchCount * 5, metaCount)
        )
    }
)

// ===== Navigation Wrapper =====

