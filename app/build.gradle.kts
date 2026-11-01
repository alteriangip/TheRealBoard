plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

android {
    namespace = "com.refboard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.refboard.app"
        minSdk = 29
        targetSdk = 34
        // Minutes since 2024 at build time: always higher than any earlier
        // build, whichever repo or account built it, so every new APK
        // installs as an update. (Run numbers restart in a new repo.)
        versionCode = ((System.currentTimeMillis() / 1000 - 1_704_067_200L) / 60).toInt()
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One app per site. Different application ids, so both install side by side.
    flavorDimensions += "site"
    productFlavors {
        create("pornpics") {
            dimension = "site"
            resValue("string", "app_name", "RefBoard")
            buildConfigField("String", "SITE", "\"pornpics\"")
        }
        create("erome") {
            dimension = "site"
            applicationId = "com.refboard.erome"
            resValue("string", "app_name", "EroBoard")
            buildConfigField("String", "SITE", "\"erome\"")
        }
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The release lint check's built-in Kotlin analyser predates Kotlin 2.2.
    lint { checkReleaseBuilds = false }
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
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
