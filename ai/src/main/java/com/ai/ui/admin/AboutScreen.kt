package com.ai.ui.admin

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.BuildConfig
import com.ai.R
import com.ai.ui.hub.HubCard
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** "About" hub reached from the home screen. Shows the AI logo,
 *  version + build date stamped into the APK at compile time, and
 *  two cards that fan out to the bundled documentation hubs (the
 *  end-user manual + the developer technical docs). */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenManual: () -> Unit,
    onOpenTechnicalDocs: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(
            helpTopic = "about",
            title = "About",
            onBackClick = onBack,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "AI App Logo",
                alpha = 0.75f,
                modifier = Modifier.size(180.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                fontSize = 16.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Built ${readBundledBuildStamp(context)}",
                fontSize = 13.sp, color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Installed ${formatAppInstalledTime(context)}",
                fontSize = 13.sp, color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Source-repository link — opens the system browser.
            // Rendered as plain underlined text rather than a chip
            // so it sits unobtrusively under the build stamp.
            Text(
                "github.com/herbert256/ai",
                fontSize = 13.sp, color = AppColors.Blue,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/herbert256/ai")))
                }
            )
            Spacer(modifier = Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                HubCard(icon = "📖", title = "Manual", onClick = onOpenManual)
                HubCard(icon = "🛠️", title = "Technical documentation", onClick = onOpenTechnicalDocs)
            }
            Spacer(modifier = Modifier.height(24.dp))
            // Copyright footer pinned at the bottom of the About
            // page; mirrors the home-help Copyright card wording so
            // both surfaces agree.
            Text(
                "Copyright © Herbert Groot Jebbink.",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Licensed under the GNU General Public License v2.0 — see the LICENSE file at the root of the source repository.",
                fontSize = 11.sp, color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Format the most-recent install / upgrade time of THIS APK as
 *  "yyyy-MM-dd HH:mm:ss z". Reads PackageInfo.lastUpdateTime —
 *  always matches the user's most recent `adb install`. Distinct
 *  from build time: install can lag build by minutes (release
 *  flow) or be hours apart (Play Store install of a prior CI
 *  build). */
internal fun formatAppInstalledTime(context: android.content.Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
        .format(java.util.Date(info.lastUpdateTime))
} catch (_: Exception) { "?" }

/** Read the build-time stamp written by the gradle
 *  `generateBuildStamp` task at `assets/build-timestamp.txt`
 *  (epoch millis) and format it in the DEVICE's default
 *  timezone — same zone the install-time stamp uses. The
 *  gradle task can't pre-format because that would bake in the
 *  build machine's tz (e.g. CEST on the dev laptop) and the
 *  two stamps would disagree on which zone they're in. Always
 *  fresh per build because the task carries
 *  `outputs.upToDateWhen { false }`. Falls back to "?" on
 *  missing / unparseable asset. */
internal fun readBundledBuildStamp(context: android.content.Context): String = try {
    val millis = context.assets.open("build-timestamp.txt")
        .bufferedReader().use { it.readText().trim() }.toLong()
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
        .format(java.util.Date(millis))
} catch (_: Exception) { "?" }
