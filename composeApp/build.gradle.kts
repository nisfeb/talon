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
            // OkHttp + okhttp-sse are pure JVM but both Android and
            // desktop targets are JVM-backed, so declaring them in
            // commonMain is correct here. UrbitChannel + UrbitSession
            // + S3Uploader + AiClient + LinkPreviewCache all consume
            // them. If a non-JVM target (iOS, Wasm) is ever added,
            // these need to move out of commonMain and the file-by-
            // file deps need expect/actual splits or replacement
            // libraries (Ktor for HTTP).
            implementation(libs.okhttp)
            implementation(libs.okhttp.sse)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
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
            // Debug-signed for now — composeApp produces a side-by-side
            // placeholder APK during the port. Stage F adds the proper
            // env-var-driven release signing config (mirroring
            // app/build.gradle.kts) once composeApp takes over as the
            // production module.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false  // composeApp doesn't have proguard rules yet
        }
    }

    packaging {
        // Match app/'s exclude — Kotlin stdlib, OkHttp, and a few
        // others bundle these license files; without the exclude,
        // resource merging emits a duplicate-file error once the
        // dep tree fully lands in B2.
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Mirror app/'s lint disable — the
        // NonNullableMutableLiveDataDetector crashes on this
        // Kotlin/Compose combo during release builds. Once
        // screens move into composeApp in B2 the same crash risk
        // applies.
        checkReleaseBuilds = false
    }
}

compose.desktop {
    application {
        mainClass = "io.nisfeb.talon.compose.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Talon"
            // Compose Desktop requires MAJOR > 0 for jpackage.
            // TODO Stage F: align packageVersion with versionName
            // in defaultConfig once composeApp takes over as prod.
            packageVersion = "1.0.0"
        }
    }
}
