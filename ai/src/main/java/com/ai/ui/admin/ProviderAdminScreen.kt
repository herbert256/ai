package com.ai.ui.admin

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ai.data.AppService
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * One-row-per-provider view of every provider that has an API key
 * configured, regardless of state. Each row tap-opens the provider's
 * Admin URL in the user's default browser. Reached from Housekeeping →
 * Provider administration; useful when the user needs to top up
 * credits, rotate a key, or check usage on the provider's console
 * without hunting through the per-provider settings screens.
 */
@Composable
fun ProviderAdminScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    // Sort: state == "ok" first, then keyed-but-failing, then inactive.
    // Within each bucket, alphabetical by display name.
    val rows = remember(aiSettings) {
        AppService.entries
            .filter { aiSettings.getApiKey(it).isNotBlank() }
            .map { it to aiSettings.getProviderState(it) }
            .sortedWith(
                compareBy(
                    { stateRank(it.second) },
                    { it.first.displayName.lowercase() }
                )
            )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Provider administration", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Every provider with an API key configured. Tap a row to open the provider's admin console.",
            fontSize = 12.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No providers with API keys yet", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (provider, state) ->
                ProviderAdminRow(
                    provider = provider, state = state,
                    onOpen = {
                        val url = provider.adminUrl
                        if (url.isBlank()) {
                            Toast.makeText(context, "No admin URL configured for ${provider.displayName}", Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Couldn't open: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun stateRank(state: String): Int = when (state) {
    "ok" -> 0
    "error" -> 1
    "not-used" -> 2
    "inactive" -> 3
    else -> 4
}

@Composable
private fun ProviderAdminRow(provider: AppService, state: String, onOpen: () -> Unit) {
    val stateEmoji = when (state) {
        "ok" -> "🔑"
        "error" -> "❌"
        "inactive" -> "💤"
        else -> "⭕"
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.displayName, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(stateEmoji, fontSize = 14.sp)
            }
            val url = provider.adminUrl
            if (url.isNotBlank()) {
                Text(
                    url,
                    fontSize = 12.sp, color = Color(0xFF64B5F6), fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("(no admin URL configured)", fontSize = 12.sp, color = AppColors.TextTertiary)
            }
        }
    }
}
