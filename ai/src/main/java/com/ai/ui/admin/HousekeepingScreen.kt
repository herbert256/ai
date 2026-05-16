package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
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
    onNavigateToAppLog: () -> Unit = {},
    onNavigateToTest: () -> Unit = {},
    onNavigateToUpdateFromCloud: () -> Unit = {}
) {
    BackHandler { onBackToHome() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "housekeeping", title = "Housekeeping", onBackClick = onBackToHome)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavCard(
                title = if (hasActiveProvider) "Backup & Restore" else "Restore",
                onClick = onNavigateToBackupRestore
            )
            NavCard(
                title = if (hasActiveProvider) "Export & Import" else "Import",
                onClick = onNavigateToImportExport
            )
            if (hasActiveProvider && hasTrimmable) {
                NavCard("Trim by age", onClick = onNavigateToTrimByAge)
            }
            // In-app log viewer — daily-rotating file under filesDir/applog/.
            // Available even on first run (no active provider) because the
            // log is populated immediately on app start (bootstrap line).
            NavCard("Application log", onClick = onNavigateToAppLog)
            // Diagnostic test flows — currently "Test all models".
            NavCard("Test", onClick = onNavigateToTest)
            // Self-update: reads an APK from a user-picked storage
            // location (typically a Drive sync folder) and fires the
            // system PackageInstaller. One-time setup picks the file;
            // every subsequent tap installs whatever's currently there.
            NavCard("Update from cloud", onClick = onNavigateToUpdateFromCloud)
            // Refresh and Reset live together at the bottom — both
            // wholesale-state operations that finish with a forced app
            // restart popup.
            NavCard("Refresh", onClick = onNavigateToRefresh)
            NavCard("Reset", onClick = onNavigateToReset)
        }
    }
}

// Local LiteRT models + Local LLMs maintenance moved to AI Setup
// — see ui/settings/LocalRuntimeScreens.kt. They're configuration of
// on-device runtimes, not housekeeping, and naturally belong with
// the rest of the AI configuration cards.

/** Card that doesn't expand — clicking the whole row fires [onClick].
 *  Every Housekeeping row is now a NavCard: each one drills into its
 *  own full screen with its own help topic. */
@Composable
private fun NavCard(title: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Text(">", color = AppColors.Blue, fontSize = 16.sp)
        }
    }
}
