package com.ai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ChatHistoryManager
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun ChatsHubScreen(
    aiSettings: Settings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToAgentSelect: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChatHistory: () -> Unit,
    onNavigateToChatSearch: () -> Unit,
    onNavigateToDualChat: () -> Unit,
    onResumeSession: (String) -> Unit = {},
    onNavigateToManage: () -> Unit = {}
) {
    BackHandler { onNavigateBack() }

    val hasAgents = remember(aiSettings.agents) {
        aiSettings.agents.any {
            aiSettings.getEffectiveApiKeyForAgent(it).isNotBlank() && aiSettings.isProviderActive(it.provider)
        }
    }
    val historyVersion by ChatHistoryManager.historyVersion.collectAsState()
    val hasChatHistory by produceState(initialValue = false, historyVersion) {
        value = ChatHistoryManager.getSessionCountAsync() > 0
    }
    val allSessionsForHub by produceState<List<com.ai.data.ChatSession>>(initialValue = emptyList(), historyVersion) {
        value = ChatHistoryManager.getAllSessionsAsync().sortedByDescending { it.updatedAt }
    }
    val pinnedSessions = allSessionsForHub.filter { it.pinned }
    val recentSessions = allSessionsForHub.filter { !it.pinned }.take(3)
    // "Unfinished" chats — the user-visible last message is from the
    // user, no assistant response landed. Happens when the user
    // navigates away mid-stream: the user message was already saved
    // before the stream started, the cancellation re-throws before the
    // assistant message is appended.
    val unfinishedSessions = allSessionsForHub.filter { s ->
        val lastUserVisible = s.messages.lastOrNull { it.role != "system" }
        lastUserVisible?.role == "user"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "AI Chat", onBackClick = onNavigateBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(24.dp))

        if (unfinishedSessions.isNotEmpty()) {
            UnfinishedChatPill(
                count = unfinishedSessions.size,
                onResume = { onResumeSession(unfinishedSessions.first().id) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        StartChatGroup(
            hasAgents = hasAgents,
            onAgentChat = onNavigateToAgentSelect,
            onNewChat = onNavigateToNewChat,
            onDualChat = onNavigateToDualChat
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChatHubCard(
            icon = "\uD83D\uDCDA", title = "Continue Existing Chat",
            description = "Resume a previous chat session",
            onClick = onNavigateToChatHistory, enabled = hasChatHistory
        )
        if (pinnedSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ChatListCard(title = "Pinned", icon = "📌", sessions = pinnedSessions, onResume = onResumeSession)
        }
        if (recentSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ChatListCard(title = "Recent", icon = "🕘", sessions = recentSessions, onResume = onResumeSession)
        }
        Spacer(modifier = Modifier.height(12.dp))
        ChatHubCard(
            icon = "\uD83D\uDD0D", title = "Search Chats",
            description = "Search across all chat messages",
            onClick = onNavigateToChatSearch, enabled = hasChatHistory
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChatHubCard(
            icon = "\uD83E\uDDF9", title = "Manage",
            description = "Delete old chats or export a backup",
            onClick = onNavigateToManage, enabled = hasChatHistory
        )
    }
}

/** Card listing chat sessions \u2014 used by both the Pinned and Recent
 *  sections. Each row shows the first user message preview, provider /
 *  model, and updated timestamp; tap resumes that session. */
@Composable
private fun ChatListCard(title: String, icon: String?, sessions: List<com.ai.data.ChatSession>, onResume: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                if (icon != null) {
                    Text(icon, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
            }
            sessions.forEach { s ->
                Text(
                    text = s.preview,
                    fontSize = 13.sp, color = Color.White,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResume(s.id) }
                        .padding(vertical = 3.dp)
                )
            }
        }
    }
}

/** Surfaces a pill at the top of the hub when at least one chat
 *  ended on a user message with no assistant reply (the user
 *  navigated away mid-stream, structured cancellation cut the
 *  response before it could be appended). Tap resumes the most recent
 *  such session so the user can continue from where they left off. */
@Composable
private fun UnfinishedChatPill(count: Int, onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onResume() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F3340))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✉️", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            val label = if (count == 1) "1 chat awaiting reply" else "$count chats awaiting reply"
            Text(label, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("Resume", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold)
        }
    }
}

/** Folds the three "begin a chat" entry points (agent-driven, configure
 *  on the fly, and dual-AI) into a single Start card so the hub
 *  doesn't show three loose creation rows for variants of the same
 *  step. Mirrors the Start card on the AI Reports hub. */
@Composable
private fun StartChatGroup(
    hasAgents: Boolean,
    onAgentChat: () -> Unit,
    onNewChat: () -> Unit,
    onDualChat: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Start", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp))
            ChatStartRow(icon = "\uD83E\uDD16", title = "New Chat with Agent", enabled = hasAgents, onClick = onAgentChat)
            ChatStartRow(icon = "\uD83D\uDCAC", title = "New Chat \u2013 Configure On The Fly", enabled = true, onClick = onNewChat)
            ChatStartRow(icon = "\uD83E\uDD1C\uD83E\uDD1B", title = "Dual AI Chat", enabled = true, onClick = onDualChat)
        }
    }
}

@Composable
private fun ChatStartRow(icon: String, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 22.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else AppColors.TextDim)
    }
}

@Composable
private fun ChatHubCard(
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF3A3A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 32.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.5f))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.White else AppColors.TextDim
                )
                Text(
                    text = description, fontSize = 13.sp,
                    color = if (enabled) AppColors.TextSecondary else AppColors.TextVeryDim
                )
            }
            if (enabled) {
                Text(text = ">", fontSize = 18.sp, color = AppColors.Blue, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
