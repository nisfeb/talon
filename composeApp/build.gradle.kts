import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.okhttp)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.okhttp)
        }
    }
}

android {
    namespace = "io.nisfeb.talon.compose"
    compileSdk = 35

    defaultConfig {
        // Different applicationId from the production app/ module so
        // both APKs can be installed on the same device during the
        // port without overwriting each other. After Stage B3 deletes
        // app/, this will be flipped to "io.nisfeb.talon".
        applicationId = "io.nisfeb.talon.compose"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-port"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // Mirror app/'s env-var-driven signing fallback. With no
            // RELEASE_KEYSTORE_PROPS set, debug-signed release builds
            // still work for local testing.
            val hasReleaseKeys = System.getenv("RELEASE_KEYSTORE_PROPS") != null
            signingConfig = signingConfigs.getByName(
                if (hasReleaseKeys) "release" else "debug"
            )
            isMinifyEnabled = false  // composeApp doesn't have proguard rules yet
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.nisfeb.talon.compose.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Talon"
            // Compose Desktop requires MAJOR > 0 for jpackage.
            packageVersion = "1.0.0"
        }
    }
}
