package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.MetadataDefaults
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar

@Composable
fun HousekeepingScreen(
    onBackToHome: () -> Unit,
    /** True when at least one provider has a working API key (state ==
     *  "ok"). When false the screen folds to a minimal "first-run"
     *  shape: only Refresh + Reset + a Restore-only / Import-only
     *  pair remain. Trim by age is hidden because it has nothing
     *  meaningful to operate on (no reports, no traces yet). Backup is
     *  hidden because there's nothing worth backing up; Export is
     *  hidden because there's nothing to export. Reset stays visible
     *  unconditionally — it's a recovery tool the user might need to
     *  reach when something is wrong, including on a broken first run.
     *  The user can still Restore / Import to bring data in from
     *  another install. */
    hasActiveProvider: Boolean = true,
    /** True when there's at least one report, chat session, or trace
     *  file on disk for the Trim flow to delete. Even with an active
     *  provider, a freshly-installed-then-restored device may have
     *  nothing to trim until the user actually runs something — the
     *  Trim card hides itself in that window. */
    hasTrimmable: Boolean = true,
    onNavigateToBackupRestore: () -> Unit = {},
    onNavigateToImportExport: () -> Unit = {},
    onNavigateToRefresh: () -> Unit = {},
    onNavigateToTrimByAge: () -> Unit = {},
    onNavigateToReset: () -> Unit = {},
    onNavigateToTest: () -> Unit = {},
    onNavigateToUpdateFromCloud: () -> Unit = {},
    onNavigateToCosts: () -> Unit = {}
) {
    BackHandler { onBackToHome() }
    val mi = LocalMetadataIcons.current

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "housekeeping",
            title = "Housekeeping",
            subject = "Backup, cleanup and maintenance tools",
            onBackClick = onBackToHome,
            reportIcon = mi.housekeeping,
            reportIconGoesHome = true
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                Text(
                    "Maintenance tools for backups, imports, cleanup, diagnostics, cache refreshes, and recovery. Use these when moving devices, pruning old local data, or repairing app state.",
                    fontSize = 12.sp,
                    color = AppColors.TextTertiary,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                )
            }
            item {
                HousekeepingLinkCard(
                    emoji = MetadataDefaults.SAVE,
                    title = if (hasActiveProvider) "Backup & Restore" else "Restore",
                    subtitle = if (hasActiveProvider) "Create a full backup zip or restore one from storage" else "Restore an existing backup into this install",
                    onClick = onNavigateToBackupRestore
                )
            }
            item {
                HousekeepingLinkCard(
                    emoji = MetadataDefaults.SHARE,
                    title = if (hasActiveProvider) "Export & Import" else "Import",
                    subtitle = if (hasActiveProvider) "Move reports, prompts, settings, or other portable app data" else "Import reports, prompts, settings, or other portable app data",
                    onClick = onNavigateToImportExport
                )
            }
            if (hasActiveProvider && hasTrimmable) {
                item {
                    HousekeepingLinkCard(
                        emoji = MetadataDefaults.DELETE,
                        title = "Trim by age",
                        subtitle = "Delete old reports, chats, traces, logs, and other local data",
                        onClick = onNavigateToTrimByAge
                    )
                }
            }
            // Self-update: reads an APK from a user-picked storage
            // location (typically a Drive sync folder) and fires the
            // system PackageInstaller. One-time setup picks the file;
            // every subsequent tap installs whatever's currently there.
            item {
                HousekeepingLinkCard(
                    emoji = MetadataDefaults.CLOUD,
                    title = "Update from cloud",
                    subtitle = "Install the APK from a saved cloud or storage location",
                    onClick = onNavigateToUpdateFromCloud
                )
            }
            // Bulk manual-cost-override maintenance: cleanup + layered
            // CSV. Per-row override curation lives in AI Setup → Costs.
            // Both Costs and Test act on active providers/models — hide
            // them on first run when there's nothing to act on.
            if (hasActiveProvider) {
                item {
                    HousekeepingLinkCard(
                        emoji = MetadataDefaults.COST,
                        title = "Costs",
                        subtitle = "Maintain manual pricing overrides and layered cost data",
                        onClick = onNavigateToCosts
                    )
                }
                // Diagnostic test flows — currently "Test all models".
                item {
                    HousekeepingLinkCard(
                        emoji = MetadataDefaults.TEST,
                        title = "Test",
                        subtitle = "Run diagnostics such as Test all models and stress test",
                        onClick = onNavigateToTest
                    )
                }
            }
            // Refresh and Reset live together at the bottom — both
            // wholesale-state operations that finish with a forced app
            // restart popup.
            item {
                HousekeepingLinkCard(
                    emoji = MetadataDefaults.RELOAD,
                    title = "Refresh",
                    subtitle = "Refresh providers, model lists, pricing catalogs, and defaults",
                    onClick = onNavigateToRefresh
                )
            }
            item {
                HousekeepingLinkCard(
                    emoji = MetadataDefaults.CLEAR,
                    title = "Reset",
                    subtitle = "Clear runtime data, configuration, caches, or reset app state",
                    onClick = onNavigateToReset
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// Local LiteRT models + Local LLMs maintenance moved to AI Setup
// — see ui/settings/LocalRuntimeScreens.kt. They're configuration of
// on-device runtimes, not housekeeping, and naturally belong with
// the rest of the AI configuration cards.

/** Monitor-style navigation row for Housekeeping hub actions. */
@Composable
private fun HousekeepingLinkCard(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    val mi = LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                mi.forFactoryGlyph(emoji),
                fontSize = 28.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(42.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, fontSize = 11.sp, color = AppColors.TextTertiary)
            }
            Text("›", fontSize = 22.sp, color = AppColors.TextTertiary)
        }
    }
}
