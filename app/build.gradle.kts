import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.nisfeb.talon"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.nisfeb.talon"
        minSdk = 26
        // Play Store requires new apps to target "latest API - 1". 35
        // matches what RELEASE.md already documents and opts us into
        // Android 15 behavior changes (16 KB page sizes, edge-to-edge).
        targetSdk = 35
        versionCode = 21
        versionName = "0.6.0"
    }

    signingConfigs {
        create("release") {
            // Keystore lives outside the repo. Path comes from the
            // RELEASE_KEYSTORE_PROPS env var (file containing
            // storeFile / storePassword / keyAlias / keyPassword).
            // Falls back to debug signing when the env var is unset
            // so unsigned local debug builds still work.
            val propsPath = System.getenv("RELEASE_KEYSTORE_PROPS")
            if (propsPath != null) {
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

    buildTypes {
        release {
            val hasReleaseKeys = System.getenv("RELEASE_KEYSTORE_PROPS") != null
            signingConfig = signingConfigs.getByName(
                if (hasReleaseKeys) "release" else "debug"
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Lint's NonNullableMutableLiveDataDetector crashes on this
        // Kotlin/compose combo — skip lint on release builds; we're not
        // shipping to Play Store yet.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.reorderable)
    implementation(libs.androidx.security.crypto)
    implementation(libs.mlkit.entity.extraction)
    implementation(libs.mediapipe.tasks.text)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
