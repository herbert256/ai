package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** Static, build-time inventory of every tool and library the app +
 *  build process depend on, with the version that's actually active.
 *  Reached from the About screen. Values are baked in here by hand —
 *  they mirror `gradle/libs.versions.toml` and `ai/build.gradle.kts`
 *  (dependency versions aren't exposed at runtime), so they must be
 *  kept in step when those files change. See help topic
 *  "dependencies". */
@Composable
fun DependenciesScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)) {
        TitleBar(
            helpTopic = "dependencies",
            title = "Dependencies",
            subject = "Active versions used by the app and the build",
            onBackClick = onBack,
            // 📦 left glyph — matches the Dependencies card on the About page.
            reportIcon = "📦",
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            DEPENDENCY_SECTIONS.forEach { (title, rows) ->
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    color = AppColors.WarningAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(AppColors.CardBackground, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    rows.forEach { (label, value) -> VersionRow(label, value) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.weight(0.55f))
        Text(
            value,
            color = AppColors.TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.45f)
        )
    }
}

/** Label → active-version pairs, grouped by section. Mirror of the
 *  tables in the build files; update alongside any version bump. */
private val DEPENDENCY_SECTIONS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Build toolchain" to listOf(
        "Gradle" to "9.8.0",
        "Android Gradle Plugin" to "9.4.1",
        "Kotlin" to "2.4.20",
        "Compose compiler" to "2.4.20",
        "Android build-tools" to "37.0.0",
        "JDK / Java target" to "25 (LTS)",
        "Kotlin JVM target" to "25"
    ),
    "Android platform / SDK" to listOf(
        "compileSdk" to "37.2 (Android 17)",
        "targetSdk" to "36 (Android 16)",
        "minSdk" to "36 (Android 16)",
        "ABI" to "arm64-v8a, x86_64"
    ),
    "AndroidX & Compose" to listOf(
        "compose-bom" to "2026.09.00",
        "material3" to "1.4.0 (via BOM)",
        "material-icons-core" to "1.7.8 (via BOM)",
        "core-ktx" to "1.19.1",
        "activity-compose" to "1.13.0",
        "lifecycle-runtime-ktx" to "2.11.0",
        "lifecycle-viewmodel-compose" to "2.11.0",
        "navigation-compose" to "2.10.2",
        "profileinstaller" to "1.4.1",
        "emoji2-emojipicker" to "1.7.0"
    ),
    "Networking / serialization" to listOf(
        "Retrofit" to "3.0.0",
        "OkHttp" to "5.5.0",
        "Gson" to "2.14.0",
        "SnakeYAML" to "2.7"
    ),
    "Coroutines" to listOf(
        "kotlinx-coroutines" to "1.11.0"
    ),
    "On-device ML / documents" to listOf(
        "MediaPipe tasks-text" to "1.0.0",
        "MediaPipe tasks-genai" to "0.10.35",
        "commons-compress" to "1.28.0",
        "pdfbox-android" to "2.0.27.0",
        "jsoup" to "1.23.2"
    ),
    "Testing" to listOf(
        "JUnit4" to "4.13.2",
        "Truth" to "1.4.5",
        "Guava (test constraint)" to "33.7.1-android",
        "test ext-junit" to "1.3.0",
        "test runner / rules / core" to "1.7.0",
        "espresso-core" to "3.7.0"
    )
)
