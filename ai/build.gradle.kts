import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val versionFromTimestamp: String by lazy {
    val now = Calendar.getInstance()
    val yy = String.format("%02d", now.get(Calendar.YEAR) % 100)
    val ddd = now.get(Calendar.DAY_OF_YEAR).toString()
    val minutesOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    "$yy.$ddd.$minutesOfDay"
}

val keystoreProperties = Properties()
val keystoreFile = rootProject.file("local.properties")
if (keystoreFile.exists()) {
    keystoreProperties.load(keystoreFile.inputStream())
}

android {
    namespace = "com.ai"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    val keystoreFileName = keystoreProperties["KEYSTORE_FILE"]?.toString()
    signingConfigs {
        create("release") {
            if (keystoreFileName != null) {
                storeFile = rootProject.file(keystoreFileName)
                storePassword = keystoreProperties["KEYSTORE_PASSWORD"]?.toString()
                keyAlias = keystoreProperties["KEY_ALIAS"]?.toString()
                keyPassword = keystoreProperties["KEY_PASSWORD"]?.toString()
            }
        }
    }
    // Fail loudly rather than emit an unsigned APK when the release build is requested without keys.
    if (keystoreFileName == null) {
        gradle.taskGraph.whenReady {
            val releaseTaskNames = setOf("assembleRelease", "packageRelease", "bundleRelease", "installRelease")
            if (allTasks.any { it.name in releaseTaskNames }) {
                throw GradleException(
                    "Release build requested but local.properties is missing KEYSTORE_FILE, " +
                    "KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD. Add them before running assembleRelease."
                )
            }
        }
    }

    defaultConfig {
        applicationId = "com.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = versionFromTimestamp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Ship only arm64-v8a — the only ABI any modern Android device
        // (and the standard emulator AVDs) actually runs. Dropping the
        // other three saves ~150 MB of native libraries in the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Network timeouts in seconds — tuned for streaming (long reads), short connect.
        // The read-timeout values double as the first-run defaults for the
        // user-tunable GeneralSettings.{streaming,nonStreaming}ReadTimeoutSec —
        // changes here propagate only to fresh installs.
        buildConfigField("int", "NETWORK_CONNECT_TIMEOUT_SEC", "30")
        buildConfigField("int", "NETWORK_READ_TIMEOUT_SEC", "600")
        buildConfigField("int", "NETWORK_NONSTREAMING_READ_TIMEOUT_SEC", "120")
        buildConfigField("int", "NETWORK_WRITE_TIMEOUT_SEC", "30")
        // Provider-test calls (Refresh-all per-provider tests, the
        // per-provider Test button, the Developer screen's raw-JSON
        // submit) get this read timeout instead of the 10-minute
        // streaming default — a hung provider would otherwise gate the
        // whole Refresh-all step on the slowest server.
        buildConfigField("int", "TEST_CONNECTION_READ_TIMEOUT_SEC", "30")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Let unit tests exercise code that touches android.util.Log etc. without Robolectric;
            // unstubbed framework calls return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }
    // Generated assets dir holding `build-timestamp.txt` — written
    // on every build by the never-up-to-date `generateBuildStamp`
    // task below. Read at runtime via
    // `context.assets.open("build-timestamp.txt")`. This avoids the
    // configuration-cache trap that made the old
    // BuildConfig.BUILD_TIMESTAMP go stale (the const string was
    // evaluated at config time, then reused across builds whenever
    // the config cache survived).
    sourceSets {
        getByName("main") {
            // Plain File path (not a Provider) — AGP's SourceSet API
            // rejects providers; the variants API would carry task
            // deps automatically but the explicit
            // `tasks.matching { preBuild }.dependsOn(generateBuildStamp)`
            // below covers it.
            assets.srcDir(layout.buildDirectory.dir("generated/buildStamp/assets").get().asFile)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The MediaPipe LLM Inference native library is 26 MB on
            // arm64-v8a — biggest single artifact in the APK and only
            // needed when the user actually runs a local .task model.
            // Strip it; LlmRuntime downloads it on demand from Google
            // Maven into <filesDir>/native/, and LocalLlm pre-loads
            // it via System.load before touching any MediaPipe type.
            excludes += "**/libllm_inference_engine_jni.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Always-runs task that writes the current wall-clock time into a
// generated asset. `outputs.upToDateWhen { false }` makes Gradle
// re-execute the task even when the config cache survives, so the
// stamp matches the moment `assembleDebug` ran (not the moment the
// config cache was populated).
val generateBuildStamp = tasks.register("generateBuildStamp") {
    val outFile = layout.buildDirectory.file("generated/buildStamp/assets/build-timestamp.txt")
    outputs.file(outFile)
    outputs.upToDateWhen { false }
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
    }
}

// Wire the stamp generation in front of every preBuild — covers
// all variants without depending on AGP-internal task names.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateBuildStamp)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Core Material icons (Icons.Filled.KeyboardArrowLeft / Right
    // and the ~360 most-common glyphs). The full extended set is a
    // ~30 MB artifact we don't need — core covers everything used.
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Navigation
    implementation(libs.navigation.compose)

    // Baseline profile installer (apply AOT profiles when bundled)
    implementation(libs.androidx.profileinstaller)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    // MediaPipe Tasks Text — wraps a LiteRT runtime so the app can run
    // .tflite text-embedder models locally for on-device semantic
    // search. The Tasks API hides tokenisation / device delegation
    // and the bundled native lib is ~5-6 MB. Models live as
    // user-supplied .tflite files under filesDir/local_models/.
    implementation("com.google.mediapipe:tasks-text:0.10.35")

    // MediaPipe Tasks GenAI — LLM Inference API. Runs Gemma / Phi /
    // Llama / Falcon / StableLM in MediaPipe's .task bundle format
    // entirely on-device. Models are user-supplied .task files
    // (mostly licence-gated downloads) imported via the SAF picker
    // on the Housekeeping → Local LLMs card.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // Apache Commons Compress — used by the Local LLM import flow to
    // unwrap a .task file from a downloaded .zip / .tar / .tar.gz /
    // .tgz archive. Kaggle ships some Gemma bundles as .tgz; this
    // saves the user a round-trip through a desktop unzipper.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // PDFBox-Android — Apache PDFBox port for Android. Used by the
    // Knowledge ingestion pipeline to extract text from .pdf files
    // before chunking + embedding. ~6 MB native + Java.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Jsoup — HTML parser used by the Knowledge ingestion pipeline
    // to fetch a web page and extract its readable text content.
    implementation("org.jsoup:jsoup:1.18.1")

    debugImplementation(libs.androidx.ui.tooling)

    // Pin Guava to a Truth-compatible version. MediaPipe transitively
    // pulls Guava 27.0.1-android and AGP's consistent-resolution
    // downgrades the test classpath to match, which breaks Truth 1.4.5
    // at runtime (VerifyError on Subject because Subject's bytecode
    // calls Guava 33+ APIs missing from 27). Forcing 33+ on both
    // classpaths keeps Truth working without affecting MediaPipe.
    constraints {
        implementation("com.google.guava:guava:33.4.3-android") {
            because("Truth 1.4.5 requires Guava 33+; MediaPipe's transitive 27 breaks tests")
        }
    }

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.truth)

    // Instrumented tests (run on a connected device / emulator).
    // The Compose BOM is applied here too so ui-test-junit4 picks up
    // the same Compose version as the app.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // ui-test-manifest registers ComponentActivity in a debug manifest
    // merge so createComposeRule() can host content under test.
    debugImplementation(libs.compose.ui.test.manifest)
}
