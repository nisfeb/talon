import java.io.FileInputStream
import java.util.Properties

// The Android application shell.
//
// It has no source of its own: MainActivity, TalonApplication, the
// services and receivers all still live in :composeApp, whose library
// manifest merges into this one. What lives here is the set of things
// only an application can declare — applicationId, signing, ABI
// splits, R8 — which is precisely what could not stay in a module
// that also applies the Kotlin Multiplatform plugin.
plugins {
    // No org.jetbrains.kotlin.android here. With the AGP-9 built-in
    // Kotlin support that the KMP migration turns back on, AGP compiles
    // Kotlin itself and applying the standalone plugin is an error.
    // This module has no Kotlin source anyway — it is manifest and
    // config only.
    alias(libs.plugins.android.application)
}

// Shared with :composeApp via gradle.properties: that module bakes the
// same values into TalonBuild and the desktop package, and release.yml
// reads them to name artifacts.
val talonVersionCode = (property("talon.versionCode") as String).trim().toInt()
val talonVersionName = (property("talon.versionName") as String).trim()

android {
    // Not the applicationId: that stays io.nisfeb.talon so installs
    // upgrade in place. This only namespaces this module's own R and
    // BuildConfig, and must differ from :composeApp's namespace.
    namespace = "io.nisfeb.talon.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.nisfeb.talon"
        minSdk = 26
        // Play requires new apps to target "latest API - 1"; 36 opts us
        // into Android 15 behaviour (16 KB pages, edge-to-edge) without
        // taking Android 17 changes in the same bump.
        targetSdk = 36
        versionCode = talonVersionCode
        versionName = talonVersionName
    }

    signingConfigs {
        create("release") {
            // Keystore lives outside the repo; the path comes from
            // RELEASE_KEYSTORE_PROPS. Unset (every dev box) falls back
            // to debug signing so local release builds still work —
            // and must never be uploaded, see RELEASE.md.
            val propsPath = System.getenv("RELEASE_KEYSTORE_PROPS")
            if (propsPath != null) {
                // Imported at the top: inside this block `java`
                // resolves to Gradle's java extension, not the package.
                val props = Properties().apply {
                    FileInputStream(propsPath).use { load(it) }
                }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // Per-ABI APKs alongside the universal one. The native libs
    // (sqlite-bundled, mediapipe text, onnxruntime, image codecs) total
    // ~41 MB across architectures and each user needs one. The
    // universal APK stays so the GitHub Releases sideload flow is
    // unchanged.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            val hasReleaseKeys = System.getenv("RELEASE_KEYSTORE_PROPS") != null
            signingConfig = signingConfigs.getByName(
                if (hasReleaseKeys) "release" else "debug",
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Kotlin stdlib, OkHttp and others each bundle these; without
        // the exclude, resource merging fails on duplicates.
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint {
        // NonNullableMutableLiveDataDetector crashes on this
        // Kotlin/Compose combination.
        checkReleaseBuilds = false
        abortOnError = false
    }
}


dependencies {
    implementation(project(":composeApp"))
}
