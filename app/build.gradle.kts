plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

android {
    namespace = "com.refboard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.refboard.app"
        minSdk = 29
        targetSdk = 34
        // CI builds count up, so each build installs as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // One fixed key so every GitHub build installs over the previous one
        // without uninstalling. If the REFBOARD_KEYSTORE_* secrets are set,
        // the workflow writes the key from them; otherwise the key committed
        // in keystore/ is used.
        create("shared") {
            val password = System.getenv("REFBOARD_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "refboard"
            storeFile = rootProject.file(System.getenv("REFBOARD_KEYSTORE_FILE")?.takeIf { it.isNotEmpty() } ?: "keystore/refboard.jks")
            storePassword = password
            keyAlias = System.getenv("REFBOARD_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "refboard"
            keyPassword = password
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            // Strips unused code (mostly the big icon set) for a smaller APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("gallery-dl")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
