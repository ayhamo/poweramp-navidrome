import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment: GitHub Actions fills it from the repository secrets,
// build.ps1 -Release from the local keystore/ folder. Without it the release APK is unsigned.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "com.ayham.navidrome"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ayham.poweramp.navidrome"
        minSdk = 26 // StorageManager.openProxyFileDescriptor
        targetSdk = 35
        // CI sets these from the tag and the run number; every release must have a higher versionCode.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "poweramp-navidrome"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":poweramp_api_lib"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
