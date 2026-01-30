import java.util.Calendar
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Generate version from timestamp: yy.DDD.MMMM (year.dayOfYear.minutesOfDay)
val versionFromTimestamp: String by lazy {
    val now = Calendar.getInstance()
    val yy = String.format("%02d", now.get(Calendar.YEAR) % 100)
    val ddd = now.get(Calendar.DAY_OF_YEAR).toString()
    val minutesOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    "$yy.$ddd.$minutesOfDay"
}

// Load keystore properties from local.properties
val keystoreProperties = Properties()
val keystoreFile = rootProject.file("local.properties")
if (keystoreFile.exists()) {
    keystoreProperties.load(keystoreFile.inputStream())
}

android {
    namespace = "com.ai"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val ksFile = keystoreProperties["KEYSTORE_FILE"]?.toString()
            if (ksFile != null) {
                storeFile = rootProject.file(ksFile)
                storePassword = keystoreProperties["KEYSTORE_PASSWORD"]?.toString()
                keyAlias = keystoreProperties["KEY_ALIAS"]?.toString()
                keyPassword = keystoreProperties["KEY_PASSWORD"]?.toString()
            }
        }
    }

    defaultConfig {
        applicationId = "com.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = versionFromTimestamp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
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

    debugImplementation(libs.androidx.ui.tooling)
}
