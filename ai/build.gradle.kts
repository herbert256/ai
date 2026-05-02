import java.util.Calendar
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

        // Network timeouts in seconds — tuned for streaming (long reads), short connect.
        buildConfigField("int", "NETWORK_CONNECT_TIMEOUT_SEC", "30")
        buildConfigField("int", "NETWORK_READ_TIMEOUT_SEC", "600")
        buildConfigField("int", "NETWORK_WRITE_TIMEOUT_SEC", "30")
        buildConfigField("int", "TEST_CONNECTION_READ_TIMEOUT_SEC", "120")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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

    // Markdown rendering
    implementation(libs.compose.markdown)

    // Navigation
    implementation(libs.navigation.compose)

    // Baseline profile installer (apply AOT profiles when bundled)
    implementation(libs.androidx.profileinstaller)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.ui.tooling)

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
