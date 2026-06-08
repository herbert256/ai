package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.MetadataDefaults
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.DualActionCard
import com.ai.ui.shared.TitleBar

/**
 * Merged Housekeeping maintenance hub — the single entry that replaces the
 * former separate "Refresh" and "Reset" cards. Each card is one subject; a
 * subject with both an update and a clear/reset action shows both buttons.
 *
 * Pure dispatcher (like [ResetScreen] used to be): every button is a callback
 * the route layer wires either to an existing reset leaf route (keeping its
 * confirm dialog) or to a ViewModel refresh kick-off. No state is held here.
 */
@Composable
fun ManageDataScreen(
    onBack: () -> Unit,
    /** True when at least one provider has a non-blank API key. Gates the two
     *  refresh buttons that drive the per-provider Workers phase (they'd have
     *  nothing to act on otherwise); the reset buttons stay enabled. */
    hasAnyKeyedProvider: Boolean,
    onRefreshAll: () -> Unit,
    onRefreshWorkers: () -> Unit,
    onRefreshInfoProviders: () -> Unit,
    onResetApplication: () -> Unit,
    onRestoreProviders: () -> Unit,
    onClearInfoProviders: () -> Unit,
    onClearRuntime: () -> Unit,
    onClearConfiguration: () -> Unit,
    onRestoreAssets: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "manage_data", title = "Manage data", subject = "Update catalogs/workers, or clear data", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Whole-app scope: re-fetch every catalog + worker, or factory-reset.
            DualActionCard(
                icon = MetadataDefaults.RELOAD,
                title = "Whole app",
                subtitle = "Refresh every catalog + per-provider worker, or factory-reset the app (keeps API keys)",
                refreshLabel = "Refresh all",
                onRefresh = onRefreshAll,
                refreshEnabled = hasAnyKeyedProvider,
                clearLabel = "Reset",
                onClear = onResetApplication
            )
            // Providers / models / default agents.
            DualActionCard(
                icon = MetadataDefaults.SETTINGS,
                title = "Providers / models / agents",
                subtitle = "Re-test keys, refetch model lists and rewrite default agents — or restore provider definitions from assets",
                refreshLabel = "Refresh",
                onRefresh = onRefreshWorkers,
                refreshEnabled = hasAnyKeyedProvider,
                clearLabel = "Restore",
                onClear = onRestoreProviders
            )
            // Info providers — the one true dual subject (six pricing catalogs).
            DualActionCard(
                icon = MetadataDefaults.INFO,
                title = "Info providers",
                subtitle = "The six pricing/capability catalogs (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis)",
                refreshLabel = "Refresh",
                onRefresh = onRefreshInfoProviders,
                clearLabel = "Clear",
                onClear = onClearInfoProviders
            )
            // Reset-only subjects.
            DualActionCard(
                icon = MetadataDefaults.DELETE,
                title = "Runtime data",
                subtitle = "Delete logs, chats, traces, reports, prompt history and usage",
                clearLabel = "Clear",
                onClear = onClearRuntime
            )
            DualActionCard(
                icon = MetadataDefaults.CLEAR,
                title = "Configuration",
                subtitle = "Wipe providers, agents, prompts, parameters and overrides",
                clearLabel = "Clear",
                onClear = onClearConfiguration
            )
            DualActionCard(
                icon = MetadataDefaults.PACKAGE_BOX,
                title = "Bundled assets/*.json",
                subtitle = "Restore providers, prompts, examples, meta and workers from the shipped JSON",
                clearLabel = "Restore",
                onClear = onRestoreAssets
            )
        }
    }
}
