package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun SetupScreen(
    aiSettings: Settings,
    huggingFaceApiKey: String = "",
    openRouterApiKey: String = "",
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (Settings) -> Unit = {},
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {}
) {
    BackHandler { onBackToSettings() }
    val context = LocalContext.current
    val hasApiKey = remember(aiSettings) { aiSettings.hasAnyApiKey() }
    val modelProviderCount = remember(aiSettings) {
        AppService.entries.count { aiSettings.getProvider(it).models.isNotEmpty() }
    }
    val agentCount = remember(aiSettings.agents) { aiSettings.agents.count { aiSettings.isProviderActive(it.provider) } }
    val costCount = remember { PricingCache.getAllManualPricing(context).size }
    val externalCount = remember(huggingFaceApiKey, openRouterApiKey) {
        (if (huggingFaceApiKey.isNotBlank()) 1 else 0) + (if (openRouterApiKey.isNotBlank()) 1 else 0)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "AI Setup", onBackClick = onBackToSettings, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SetupNavCard("\u2699\uFE0F", "Providers", "Configure API keys and models", "${AppService.entries.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) })
            SetupNavCard("\uD83E\uDDE0", "Models", "Default model, source, and model list per provider", "$modelProviderCount",
                onClick = { onNavigate(SettingsSubScreen.AI_MODELS) })
            SetupNavCard("\uD83E\uDD16", "Agents", "Named AI model configurations", "$agentCount",
                onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) }, enabled = hasApiKey)
            SetupNavCard("\uD83E\uDD86", "Flocks", "Groups of agents", "${aiSettings.flocks.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_FLOCKS) }, enabled = hasApiKey)
            SetupNavCard("\uD83D\uDC1D", "Swarms", "Groups of provider/model pairs", "${aiSettings.swarms.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_SWARMS) }, enabled = hasApiKey)
            SetupNavCard("\uD83C\uDFDB\uFE0F", "Parameters", "Parameter presets", "${aiSettings.parameters.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PARAMETERS) })
            SetupNavCard("\uD83D\uDDE8\uFE0F", "System Prompts", "Reusable system prompts", "${aiSettings.systemPrompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_SYSTEM_PROMPTS) })
            SetupNavCard("\uD83D\uDCDD", "Internal Prompts", "Prompts for agents", "${aiSettings.prompts.size}",
                onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS) })
            SetupNavCard("\uD83D\uDCB0", "Costs", "Manual pricing configuration", "$costCount",
                onClick = onNavigateToCostConfig)
            SetupNavCard("\uD83D\uDD11", "External Services", "HuggingFace, OpenRouter keys", "$externalCount",
                onClick = { onNavigate(SettingsSubScreen.AI_EXTERNAL_SERVICES) })
        }
    }
}

@Composable
private fun SetupNavCard(icon: String, title: String, description: String, count: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp, modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else AppColors.TextDim)
                Text(description, fontSize = 12.sp, color = if (enabled) AppColors.TextTertiary else AppColors.TextVeryDim)
            }
            if (count.isNotBlank()) {
                Text(count, fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (enabled) Text(">", fontSize = 16.sp, color = AppColors.Blue)
        }
    }
}

// ===== Providers List =====

@Composable
fun ProvidersScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onProviderSelected: (AppService) -> Unit
) {
    BackHandler { onBackToAiSetup() }
    val allProviders = AppService.entries
    // "Active" = providers that have been tested and have a working API key (state == "ok").
    var showActiveOnly by remember { mutableStateOf(true) }
    val activeCount = remember(aiSettings) { allProviders.count { aiSettings.getProviderState(it) == "ok" } }

    val visibleProviders = remember(showActiveOnly, aiSettings) {
        (if (showActiveOnly) allProviders.filter { aiSettings.getProviderState(it) == "ok" } else allProviders)
            .sortedBy { it.displayName }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Providers", onBackClick = onBackToAiSetup, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = showActiveOnly,
                onClick = { showActiveOnly = true },
                label = { Text("Active ($activeCount)") }
            )
            FilterChip(
                selected = !showActiveOnly,
                onClick = { showActiveOnly = false },
                label = { Text("All (${allProviders.size})") }
            )
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (visibleProviders.isEmpty()) {
                Text(
                    "No active providers yet. Switch to All and set an API key.",
                    fontSize = 13.sp, color = AppColors.TextTertiary
                )
            }
            visibleProviders.forEach { provider ->
                val state = aiSettings.getProviderState(provider)
                val stateEmoji = when (state) {
                    "ok" -> "\uD83D\uDD11"; "error" -> "\u274C"; "inactive" -> "\uD83D\uDCA4"; else -> "\u2B55"
                }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onProviderSelected(provider) },
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.displayName, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (state == "ok") {
                                val model = aiSettings.getModel(provider)
                                if (model.isNotBlank()) Text(model, fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(stateEmoji, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

// ===== External Services =====

@Composable
fun ExternalServicesScreen(
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    onSaveHuggingFaceApiKey: (String) -> Unit,
    onSaveOpenRouterApiKey: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var hfKey by remember { mutableStateOf(huggingFaceApiKey) }
    var orKey by remember { mutableStateOf(openRouterApiKey) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "External Services", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("HuggingFace", fontWeight = FontWeight.Bold, color = Color.White)
                Text("Used for model information lookup", fontSize = 12.sp, color = AppColors.TextTertiary)
                OutlinedTextField(
                    value = hfKey, onValueChange = { hfKey = it; onSaveHuggingFaceApiKey(it) },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )

                HorizontalDivider(color = AppColors.DividerDark)

                Text("OpenRouter", fontWeight = FontWeight.Bold, color = Color.White)
                Text("Used for pricing data and model specifications", fontSize = 12.sp, color = AppColors.TextTertiary)
                OutlinedTextField(
                    value = orKey, onValueChange = { orKey = it; onSaveOpenRouterApiKey(it) },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}
