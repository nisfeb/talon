// Shared, headless Talon: the Urbit channel transport and the call
// stack, with no UI and no database.
//
// Split out so a headless process — the party-line bridge, a recorder
// — can join a line using the same client the app uses, rather than a
// second implementation of the same wire. Duplicating a wire client is
// how the two halves drift, and every drift so far has surfaced as a
// control that silently did nothing.
//
// Package names are unchanged, so nothing that already imports these
// needs editing: only the module boundary moved.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64())
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // libwebrtc via JNI. The base jar is pure API; natives ship
            // per-platform, and only the host's are bundled.
            implementation(libs.webrtc.java)
            val webrtcNatives = run {
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                when {
                    os.contains("linux") -> "linux-x86_64"
                    os.contains("mac") && arch == "aarch64" -> "macos-aarch64"
                    os.contains("mac") -> "macos-x86_64"
                    else -> "windows-x86_64"
                }
            }
            runtimeOnly("dev.onvoid.webrtc:webrtc-java:" + libs.versions.webrtcJava.get() + ":" + webrtcNatives)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "io.nisfeb.talon.core"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
