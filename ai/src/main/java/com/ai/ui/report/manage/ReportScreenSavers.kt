package com.ai.ui.report.manage
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

internal val TargetLanguageSaver: Saver<TargetLanguage?, Any> = listSaver(
    save = { tl -> if (tl == null) emptyList() else listOf(tl.name, tl.native) },
    restore = { l -> if (l.isEmpty()) null else TargetLanguage(l[0] as String, l[1] as String) }
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
            val providerId = saved[i] as? String
            val provider = providerId?.let { AppService.findById(it) }
            if (provider != null) {
                out.add(
                    ReportModel(
                        provider = provider,
                        model = saved[i + 1] as? String ?: "",
                        type = saved[i + 2] as? String ?: "",
                        sourceType = saved[i + 3] as? String ?: "",
                        sourceName = saved[i + 4] as? String ?: "",
                        sourceId = (saved[i + 5] as? String)?.takeIf { it.isNotEmpty() },
                        agentId = (saved[i + 6] as? String)?.takeIf { it.isNotEmpty() },
                        endpointId = (saved[i + 7] as? String)?.takeIf { it.isNotEmpty() },
                        agentApiKey = (saved[i + 8] as? String)?.takeIf { it.isNotEmpty() },
                        paramsIds = (saved[i + 9] as? String)?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
                    )
                )
            }
            i += 10
        }
        out.toList()
    }
)

// ===== Navigation Wrapper =====

