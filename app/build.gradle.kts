import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release signing: CI passes the key through environment variables (GitHub secrets); locally the same values
// are read from ~/keystores/visualizer_apk-release.properties. The keystore itself never enters the repository.
val signing: Map<String, String> = run {
    val keys = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val fromEnv = keys.associateWith { System.getenv("SIGNING_$it").orEmpty() }
    if (fromEnv.values.all { it.isNotEmpty() }) fromEnv else {
        val file = File(System.getProperty("user.home"), "keystores/visualizer_apk-release.properties")
        if (!file.exists()) emptyMap() else Properties().apply { file.inputStream().use(::load) }
            .let { props -> keys.associateWith { props.getProperty(it).orEmpty() } }
            .takeIf { it.values.all(String::isNotEmpty) } ?: emptyMap()
    }
}

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

    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = file(signing.getValue("KEYSTORE_FILE"))
            storePassword = signing.getValue("KEYSTORE_PASSWORD")
            keyAlias = signing.getValue("KEY_ALIAS")
            keyPassword = signing.getValue("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
