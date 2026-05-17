package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-only "View" variant of the report prompt. Reached from the
 * 📝 Prompt tile on Report - view. The management-heavy view (with
 * copy / share / trace / Translation-compare ↔ icons) keeps living on
 * the [ReportsViewerScreen] section="prompt" path used elsewhere.
 *
 * Layout: a single hero typography card. The card carries a soft
 * Purple-to-Indigo gradient background, a ✍️ Prompt badge in the
 * header strip + the report's emoji on the right, then the prompt
 * body in markdown with a slightly larger font than other body cards
 * so it reads as a primary document. Language slicing: when
 * [language] is non-blank we look up the matching PROMPT TRANSLATE
 * row and render its content instead of the original.
 */
@Composable
fun PromptViewScreen(
    reportId: String,
    language: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    data class Loaded(val report: Report?, val translatedPrompt: String?)

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, null),
        reportId, language
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val translated = if (!language.isNullOrEmpty()) {
                SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSLATE)
                    .firstOrNull {
                        it.translateSourceKind == "PROMPT" &&
                            it.targetLanguage == language &&
                            !it.content.isNullOrBlank()
                    }?.content
            } else null
            Loaded(rep, translated)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val body = loaded.translatedPrompt ?: report?.prompt.orEmpty()
    val reportIcon = report?.icon

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "prompt_view_screen",
            title = "Prompt - view",
            onBackClick = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "✍️", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = "Prompt",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Purple,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!language.isNullOrEmpty()) {
                Text(
                    text = "🌍 $language",
                    fontSize = 12.sp, color = AppColors.Orange,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (report == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AppColors.Purple.copy(alpha = 0.32f),
                                AppColors.Indigo.copy(alpha = 0.08f)
                            )
                        )
                    )
                    .border(1.dp, AppColors.Purple.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✍️ Prompt",
                        color = AppColors.Purple,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = reportIcon?.takeIf { it.isNotBlank() } ?: "📊",
                        fontSize = 26.sp
                    )
                }
                if (body.isBlank()) {
                    Text(
                        text = "(no prompt recorded)",
                        color = AppColors.TextTertiary, fontSize = 14.sp
                    )
                } else {
                    // Body via the shared markdown pipeline so the
                    // prompt's own formatting (fences, tables, lists)
                    // renders properly.
                    ContentWithThinkSections(analysis = body)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
