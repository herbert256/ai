package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.ai.data.AppService
import com.ai.model.Agent
import com.ai.model.Parameters
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.modelInfoViewClickable
import com.ai.ui.shared.shortModelName

/**
 * Read-only "View" sibling of [AgentEditScreen]. Mirrors the View
 * family aesthetic (purple gradient hero card, blue section headers
 * on CardBackground tiles). The agent's model name is clickable →
 * View Model Info, closing the navigation loop.
 */
@Composable
fun AgentViewScreen(
    agentId: String,
    aiSettings: Settings,
    onOpenManage: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val agent: Agent? = aiSettings.agents.firstOrNull { it.id == agentId }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = agent?.name,
            screenTitle = "Agent",
            subject = null,
            helpTopic = "agent_view",
            onOpenManage = onOpenManage,
            onBack = onBack
        )
        if (agent == null) {
            MissingEntityBox("Agent not found")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { AgentHeroCard(agent = agent) }
            item { AgentDetailsCard(agent = agent) }
            // System prompt + params shared across all three worker
            // view screens — render via the [WorkerSharedCards]
            // helper from CommonViewWorkerCards.kt.
            item {
                WorkerSharedCards(
                    aiSettings = aiSettings,
                    paramsIds = agent.paramsIds,
                    systemPromptId = agent.systemPromptId
                )
            }
        }
    }
}

@Composable
private fun AgentHeroCard(agent: Agent) {
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
            Text(text = "🤖", fontSize = 56.sp)
            Text(
                text = agent.name,
                fontSize = 22.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${agent.provider.id} / ${shortModelName(agent.model)}",
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.modelInfoViewClickable(agent.provider, agent.model)
            )
        }
    }
}

@Composable
private fun AgentDetailsCard(agent: Agent) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Details", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Provider", fontSize = 13.sp, color = AppColors.TextTertiary)
            Text(agent.provider.id, fontSize = 13.sp, color = Color.White)
        }
        Row(
            modifier = Modifier.fillMaxWidth().modelInfoViewClickable(agent.provider, agent.model),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Model", fontSize = 13.sp, color = AppColors.TextTertiary)
            Text(shortModelName(agent.model), fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
        }
        if (!agent.endpointId.isNullOrBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Endpoint", fontSize = 13.sp, color = AppColors.TextTertiary)
                Text(agent.endpointId, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (agent.apiKey.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("API key", fontSize = 13.sp, color = AppColors.TextTertiary)
                Text("•••• (set)", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

@Composable
internal fun MissingEntityBox(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(message, color = AppColors.TextTertiary, fontSize = 14.sp)
    }
}
