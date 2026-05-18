package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Flock
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToAgentView
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.shortModelName

/**
 * Read-only "View" sibling of [FlockEditScreen]. The Agents card
 * lists each member with provider/model — tap a row to open that
 * agent in [AgentViewScreen].
 */
@Composable
fun FlockViewScreen(
    flockId: String,
    aiSettings: Settings,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val flock: Flock? = aiSettings.flocks.firstOrNull { it.id == flockId }
    val navAgent = LocalNavigateToAgentView.current

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = flock?.name,
            screenTitle = "Flock",
            subject = null,
            helpTopic = "flock_view",
            onBack = onBack
        )
        if (flock == null) {
            MissingEntityBox("Flock not found")
            return@Column
        }
        val agents = flock.agentIds.mapNotNull { id -> aiSettings.agents.firstOrNull { it.id == id } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { FlockHero(flock, agentCount = agents.size) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.CardBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Agents (${agents.size})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                    if (agents.isEmpty()) {
                        Text("No agents in this flock", fontSize = 12.sp, color = AppColors.TextTertiary)
                    } else {
                        agents.forEach { a ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { navAgent(a.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🤖", fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(a.name, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${a.provider.id} / ${shortModelName(a.model)}",
                                        fontSize = 11.sp, color = AppColors.TextTertiary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("›", fontSize = 14.sp, color = AppColors.TextTertiary)
                            }
                        }
                    }
                }
            }
            item {
                WorkerSharedCards(
                    aiSettings = aiSettings,
                    paramsIds = flock.paramsIds,
                    systemPromptId = flock.systemPromptId
                )
            }
        }
    }
}

@Composable
private fun FlockHero(flock: Flock, agentCount: Int) {
    Box(
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
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "🪶", fontSize = 56.sp)
            Text(
                text = flock.name,
                fontSize = 22.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$agentCount agent${if (agentCount == 1) "" else "s"}",
                fontSize = 13.sp,
                color = AppColors.TextSecondary
            )
        }
    }
}
