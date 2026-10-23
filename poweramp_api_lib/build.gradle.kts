// Poweramp's public API library (src/ copied unchanged from github.com/maxmpz/powerampapi, see LICENSE.txt).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maxmpz.powerampapilib"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.eclipse.jdt:org.eclipse.jdt.annotation:2.3.100")
    api("androidx.annotation:annotation:1.9.1")
    api("androidx.core:core:1.17.0")
}
