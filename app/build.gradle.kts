import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.visualizer.montaz"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.visualizer.montaz"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.8.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The user manual lives once in docs/ and is copied into the APK at build time.
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/manualAssets"))
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val copyManual by tasks.registering(Copy::class) {
    from(rootProject.file("docs")) { include("instrukcja*.html") }
    into(layout.buildDirectory.dir("generated/manualAssets"))
}
tasks.named("preBuild") { dependsOn(copyManual) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 1.11 is the latest line compatible with AGP 8.13 / compileSdk 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
