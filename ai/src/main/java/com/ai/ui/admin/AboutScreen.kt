package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
                "Built ${BuildConfig.BUILD_TIMESTAMP}",
                fontSize = 13.sp, color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                HubCard(icon = "📖", title = "Manual", onClick = onOpenManual)
                HubCard(icon = "🛠️", title = "Technical documentation", onClick = onOpenTechnicalDocs)
            }
        }
    }
}
