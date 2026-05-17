package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ai.data.AppService
import com.ai.data.InternalPromptIconCache
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-only "View" sibling of [SecondaryResultDetailScreen] for META
 * rows. Reached from a Meta tile on Report - view; the management-heavy
 * detail screen (delete, regenerate, multi-language delete chooser,
 * trace icon, alt-icon picker, …) stays as the Manage path.
 *
 * Layout: a "❓ Question" hero card up top showing the originating
 * report prompt with the matching internal-prompt emoji on the left,
 * then a single large "🧠 <prompt name>" answer card below carrying
 * the meta's content rendered through the shared
 * [ContentWithThinkSections]. A subtle gradient on the question card
 * separates the two visually without competing for attention.
 *
 * Language slicing: when [language] is non-blank we look up a matching
 * META TRANSLATE row (translateSourceKind="META",
 * translateSourceTargetId=resultId, targetLanguage=language) and
 * render its content in place of the original. Null/"" = Original
 * (the meta row's own content).
 */
@Composable
fun MetaViewScreen(
    reportId: String,
    resultId: String,
    language: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    data class Loaded(
        val result: SecondaryResult?,
        val translatedContent: String?,
        val report: Report?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, null, null),
        reportId, resultId, language
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, reportId, resultId)
            val translates = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
            val rep = ReportStorage.getReport(context, reportId)
            val translated = if (!language.isNullOrEmpty()) {
                translates.firstOrNull {
                    it.translateSourceKind == "META" &&
                        it.translateSourceTargetId == resultId &&
                        it.targetLanguage == language &&
                        !it.content.isNullOrBlank()
                }?.content
            } else null
            Loaded(r, translated, rep)
        }
    }
    val loaded = loadedState.value
    val result = loaded.result
    val report = loaded.report
    val activeContent = loaded.translatedContent
        ?: result?.content.orEmpty()
    val title = result?.metaPromptName?.takeIf { it.isNotBlank() }
        ?: result?.let { com.ai.data.legacyKindDisplayName(it.kind) }
        ?: "Meta"
    val provider = result?.let { AppService.findById(it.providerId)?.id ?: it.providerId }.orEmpty()
    val rowIcon = result?.icon?.takeIf { it.isNotBlank() }
    val cachedIcon = result?.metaPromptName?.let { InternalPromptIconCache.get(it, title) }
    val displayedEmoji = rowIcon ?: cachedIcon ?: "🧠"

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Meta - view",
            subject = result?.metaPromptName?.takeIf { it.isNotBlank() },
            helpTopic = "meta_view",
            onBack = onBack,
            onLogoClick = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayedEmoji,
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Purple,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!language.isNullOrEmpty()) {
                Text(
                    text = "🌍 $language",
                    fontSize = 12.sp,
                    color = AppColors.Orange,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (result == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    "Loading…",
                    color = AppColors.TextTertiary, fontSize = 14.sp
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            // Hero "❓ Question" card — the report prompt rendered as
            // the question that produced this meta answer. Purple
            // gradient + report icon on the left ties it visually to
            // the meta tile that just got tapped.
            item {
                QuestionHero(
                    promptText = report?.prompt.orEmpty(),
                    reportIcon = report?.icon
                )
            }
            // Answer card — large body with the meta's content rendered
            // through the shared markdown pipeline. Per-provider /
            // -model attribution sits on the header row so the user
            // sees who answered.
            item {
                AnswerCard(
                    emoji = displayedEmoji,
                    title = title,
                    provider = provider,
                    model = result.model,
                    body = activeContent
                )
            }
        }
    }
}

@Composable
private fun QuestionHero(promptText: String, reportIcon: String?) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(AppColors.Purple.copy(alpha = 0.32f), AppColors.Purple.copy(alpha = 0.06f))
                )
            )
            .border(1.dp, AppColors.Purple.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = reportIcon?.takeIf { it.isNotBlank() } ?: "❓",
                fontSize = 26.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Question",
                color = AppColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (promptText.isBlank()) {
            Text(
                text = "(no prompt recorded)",
                color = AppColors.TextTertiary, fontSize = 13.sp
            )
        } else {
            // Markdown renderer — prompt may contain inline formatting
            // when it's been pasted from a larger document.
            ContentWithThinkSections(analysis = promptText)
        }
    }
}

@Composable
private fun AnswerCard(
    emoji: String,
    title: String,
    provider: String,
    model: String,
    body: String
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = emoji,
                fontSize = 22.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AppColors.Purple,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$provider / ${shortModelName(model)}",
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        if (body.isBlank()) {
            Text(
                text = "(no content)",
                color = AppColors.TextTertiary, fontSize = 13.sp
            )
        } else {
            // Reuse the shared markdown + <think> pipeline so tables,
            // headings, code blocks render the same as everywhere else.
            ContentWithThinkSections(analysis = body)
        }
    }
}
