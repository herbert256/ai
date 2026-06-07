package com.ai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ChatHistoryManager
import com.ai.data.MetadataDefaults
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar

@Composable
fun ChatsHubScreen(
    aiSettings: Settings,
    experimentalFeatures: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToAgentSelect: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChatHistory: () -> Unit,
    onNavigateToChatSearch: () -> Unit,
    onNavigateToDualChat: () -> Unit,
    onResumeSession: (String) -> Unit = {},
    onNavigateToManage: () -> Unit = {},
    onNavigateToLocalLlmChat: (String) -> Unit = {},
    /** Called when the user has just taken a photo via the
     *  "📸 Start with photo" entry. Caller stages the (mime, base64)
     *  into UiState.chatStarterImageBase64/Mime and navigates to
     *  AI_CHAT_PROVIDER (the configure-on-the-fly chain). The chat
     *  session screen reads the staging and seeds the first user
     *  turn's image. */
    onStartWithPhoto: (mime: String, base64: String) -> Unit = { _, _ -> }
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    // availableLlms reads filesDir/local_llms — keep it off the
    // main thread so cold opens / large model libraries don't
    // jitter the hub.
    val installedLocalLlms by produceState(initialValue = emptyList<String>(), refreshTick) {
        value = withContext(Dispatchers.IO) { com.ai.data.local.LocalLlm.availableLlms(context) }
    }

    val hasAgents = remember(aiSettings.agents) {
        aiSettings.agents.any {
            aiSettings.getEffectiveApiKeyForAgent(it).isNotBlank() && aiSettings.isProviderActive(it.provider)
        }
    }
    val historyVersion by ChatHistoryManager.historyVersion.collectAsState()
    val allSessionsForHub by produceState<List<com.ai.data.ChatSession>>(initialValue = emptyList(), historyVersion) {
        value = ChatHistoryManager.getAllSessionsAsync().sortedByDescending { it.updatedAt }
    }
    val hasChatHistory = allSessionsForHub.isNotEmpty()
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
    var photoError by remember { mutableStateOf<String?>(null) }
    val launchCamera = com.ai.ui.shared.rememberCameraCaptureLauncher(
        onCaptured = { mime, b64 -> photoError = null; onStartWithPhoto(mime, b64) },
        onError = { photoError = it }
    )

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "chat_hub", title = "Chat", subject = "Start or resume a chat with a model", onBackClick = onNavigateBack)

        if (unfinishedSessions.isNotEmpty()) {
            UnfinishedChatPill(
                count = unfinishedSessions.size,
                onResume = { onResumeSession(unfinishedSessions.first().id) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        photoError?.let {
            Text(it, color = AppColors.DangerAccent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        StartChatGroup(
            hasAgents = hasAgents,
            onAgentChat = onNavigateToAgentSelect,
            onNewChat = onNavigateToNewChat,
            onDualChat = onNavigateToDualChat,
            onStartWithPhoto = launchCamera
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChatHubCard(
            icon = MetadataDefaults.LIBRARY, title = "Continue Existing Chat",
            description = "Resume a previous chat session",
            onClick = onNavigateToChatHistory, enabled = hasChatHistory
        )

        // Show only when at least one .task LLM lives in
        // filesDir/local_llms/. The card embeds a dropdown so a
        // single tap picks the model and navigates straight to the
        // chat session \u2014 no separate picker screen.
        if (experimentalFeatures && installedLocalLlms.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            LocalLlmChatCard(installed = installedLocalLlms, onPick = onNavigateToLocalLlmChat)
        }
        if (pinnedSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ChatListCard(title = "Pinned", icon = com.ai.data.MetadataIconsHolder.current.pin, sessions = pinnedSessions, onResume = onResumeSession)
        }
        if (recentSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ChatListCard(title = "Recent", icon = com.ai.data.MetadataIconsHolder.current.clockRecent, sessions = recentSessions, onResume = onResumeSession)
        }
        Spacer(modifier = Modifier.height(12.dp))
        ChatHubCard(
            icon = MetadataDefaults.SEARCH, title = "Search Chats",
            description = "Search across all chat messages",
            onClick = onNavigateToChatSearch, enabled = hasChatHistory
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChatHubCard(
            icon = MetadataDefaults.OPEN_MANAGE, title = "Manage",
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
                    text = s.title.ifBlank { s.preview },
                    fontSize = 13.sp, color = AppColors.TextPrimary,
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

/** Hub card for chatting with a locally-installed MediaPipe LLM
 *  (.task bundle). Tap opens a dropdown; selecting a model navigates
 *  to the chat session pre-wired to that local model. The card is
 *  hidden entirely when no local LLMs are installed (the user lands
 *  on Housekeeping → Local LLMs to add one). */
@Composable
private fun LocalLlmChatCard(installed: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChatHubCard(
            icon = MetadataDefaults.DEVICE, title = "Chat with a local LLM",
            description = "Run a .task model fully on-device — nothing leaves the phone",
            onClick = {
                // Explicit branching: a lone installed model opens directly,
                // any other count (including a races-to-empty list) opens the
                // dropdown rather than blindly indexing first().
                val only = installed.singleOrNull()
                if (only != null) onPick(only) else open = true
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            installed.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { open = false; onPick(name) }
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
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(com.ai.data.MetadataIconsHolder.current.mail, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            val label = if (count == 1) "1 chat awaiting reply" else "$count chats awaiting reply"
            Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("Resume", fontSize = 12.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.Bold)
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
    onDualChat: () -> Unit,
    onStartWithPhoto: () -> Unit
) {
    val mi = LocalMetadataIcons.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    mi.forFactoryGlyph(MetadataDefaults.ADD),
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(30.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
            }
            ChatStartRow(icon = MetadataDefaults.AGENT, title = "New Chat with Agent", enabled = hasAgents, onClick = onAgentChat)
            ChatStartRow(icon = MetadataDefaults.CHAT, title = "New Chat – Configure On The Fly", enabled = true, onClick = onNewChat)
            ChatStartRow(icon = MetadataDefaults.HANDSHAKE, title = "Dual Chat", enabled = true, onClick = onDualChat)
            ChatStartRow(icon = MetadataDefaults.CAMERA, title = "Start with photo", enabled = true, onClick = onStartWithPhoto)
        }
    }
}

@Composable
private fun ChatStartRow(icon: String, title: String, enabled: Boolean, onClick: () -> Unit) {
    val mi = LocalMetadataIcons.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mi.forFactoryGlyph(icon),
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(42.dp).then(if (enabled) Modifier else Modifier.alpha(0.4f))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) AppColors.TextPrimary else AppColors.TextDim)
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
    val mi = LocalMetadataIcons.current
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mi.forFactoryGlyph(icon),
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp).then(if (enabled) Modifier else Modifier.alpha(0.5f))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (enabled) AppColors.TextPrimary else AppColors.TextDim
                )
                Text(
                    text = description, fontSize = 13.sp,
                    color = if (enabled) AppColors.TextSecondary else AppColors.TextVeryDim
                )
            }
            if (enabled) {
                Text(text = ">", fontSize = 18.sp, color = AppColors.InfoAccent, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
