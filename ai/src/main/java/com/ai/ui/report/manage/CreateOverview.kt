package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * "Create" — the full-screen launcher opened by the bottom-bar 🆕 icon on the
 * Manage hub. The big-icon + explanatory-text sibling of the old Create pop-up:
 * one row per general secondary-result kind (Meta / Rerank / Moderation /
 * Compare / Translate). Fan Out has its own Manage title-bar icon because
 * reusing an existing Fan Out is the common path. Tapping a row does exactly
 * what the pop-up button did — the close-then-open is baked into each callback
 * at the [ReportRunScreen] call site. Disabled rows (no prompt configured, or
 * a single-shot kind already present) render dimmed and non-clickable.
 *
 * Drawn as a layer-on-top overlay in [ReportRunScreen] (mirrors
 * [ReportEditOverviewScreen]); [publishBottomBar] is false so the hub keeps its
 * own bottom bar underneath.
 */
@Composable
internal fun ReportCreateOverviewScreen(
    metaEnabled: Boolean,
    rerankEnabled: Boolean,
    moderationEnabled: Boolean,
    compareEnabled: Boolean,
    onMeta: () -> Unit,
    onRerank: () -> Unit,
    onModeration: () -> Unit,
    onTranslate: () -> Unit,
    onCompare: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_create_overview",
            title = "Create",
            subject = "Add a secondary result",
            onBackClick = onBack,
            publishBottomBar = false
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            CreateRow("🔗", "Meta", "Compare, critique or synthesize the answers", metaEnabled, onMeta)
            CreateRow("🏆", "Rerank", "Rank the model answers best-first", rerankEnabled, onRerank)
            CreateRow("🚦", "Moderation", "Safety-check the answers", moderationEnabled, onModeration)
            CreateRow("🧮", "Compare with meta", "Score each answer's similarity to a meta result", compareEnabled, onCompare)
            CreateRow("🌐", "Translate", "Translate the report into other languages", true, onTranslate)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Tournament launcher opened by the Manage report bottom-bar tournament icon.
 * Kept separate from Create so head-to-head tools have their own full-screen
 * entry point.
 */
@Composable
internal fun ReportTournamentOverviewScreen(
    tournamentEnabled: Boolean,
    judgeJudgesEnabled: Boolean,
    onTournament: () -> Unit,
    onJudgeJudges: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "tournament_l1",
            title = "Tournament",
            subject = "Head-to-head tools",
            onBackClick = onBack,
            publishBottomBar = false
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            CreateRow("🥊", "Tournament", "Head-to-head judge every pair of answers", tournamentEnabled, onTournament)
            CreateRow("⚖️", "Judge the judges", "Score the judge models by how they judge 25 head-to-heads", judgeJudgesEnabled, onJudgeJudges)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** One launcher row: big emoji + title + one-line explanation. Dimmed and
 *  non-clickable when [enabled] is false. */
@Composable
private fun CreateRow(
    icon: String,
    title: String,
    explanation: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 32.sp, modifier = Modifier.padding(end = 14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                explanation, fontSize = 13.sp, color = AppColors.TextTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
