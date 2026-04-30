import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
            // Room 2.7 ships KMP-aware artifacts so the entities,
            // DAOs, and the @Database-annotated expect class can all
            // live in commonMain. Each leaf target (androidMain /
            // desktopMain) provides its own actual + driver wiring.
            implementation(libs.androidx.room.runtime)
            // sqlite-bundled exposes the SQLiteDriver API surface to
            // commonMain. Only desktop loads the bundled native
            // binary at runtime — Android uses the platform's
            // built-in SQLite via the Room Android compiler.
            implementation(libs.androidx.sqlite.bundled)
            // Coil 3 ships a multiplatform compose artifact that
            // commonMain Avatar.kt consumes via AsyncImage. The
            // okhttp-network artifact wires Coil's image loader to
            // the same OkHttp client we use elsewhere.
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            // sh.calvin.reorderable supplies the
            // rememberReorderableLazyListState + ReorderableItem APIs
            // DmListScreen uses for drag-to-reorder folders/groups.
            // The library is multiplatform.
            implementation(libs.reorderable)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.security.crypto)
            // ML Kit Entity Extraction backs EntityActions chip
            // detection. MediaPipe text tasks back the on-device
            // Embedder used by SemanticSearch + EmbeddingIndexer.
            implementation(libs.mlkit.entity.extraction)
            implementation(libs.mediapipe.tasks.text)
            // ExoPlayer powers MediaPlayerInline (inline audio/video
            // for chat attachments) and the voice preview row.
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            // Room KTX coroutine extensions used by the suspend DAOs.
            implementation(libs.androidx.room.ktx)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            // On-device sentence embedder for smart search +
            // important-message highlights. DJL (Deep Java Library)
            // wraps ONNX Runtime + HuggingFace tokenizers and resolves
            // the all-MiniLM-L6-v2 sentence-transformer model from
            // its zoo on first use. The model + tokenizer get cached
            // under ~/.djl.ai/cache (~30 MB) after the initial fetch.
            implementation(libs.djl.api)
            implementation(libs.djl.onnxruntime.engine)
            implementation(libs.djl.huggingface.tokenizers)
        }
        // commonTest carries shared kotlin.test assertions. Tests
        // here are picked up by both desktopTest and (when wired)
        // androidUnitTest source sets, so a parser test moved out of
        // app/src/test/ runs against the composeApp/commonMain copy
        // on every supported target.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // JUnit-on-JVM for the desktop target's unit tests; the
        // common kotlin.test surface delegates to JUnit on JVM
        // backends. Other target test source sets stay unconfigured
        // for now — Android composeApp tests land when that target
        // gets exercised end-to-end.
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
    }
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
        versionCode = 22
        versionName = "0.6.1"
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
        // Lint's NonNullableMutableLiveDataDetector crashes on this
        // Kotlin/compose combo — skip lint on release builds; we're not
        // shipping to Play Store yet.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

compose.desktop {
    application {
        mainClass = "io.nisfeb.talon.compose.MainKt"

        // ProGuard chokes on OkHttp's optional Android / Conscrypt /
        // BouncyCastle integrations (classes referenced from platform-
        // detection branches that never run on desktop). Disabling
        // shrinking is the cleanest fix — desktop executables aren't
        // download-size constrained the way an APK is, so the savings
        // wouldn't justify maintaining `-dontwarn` rules. Stage F can
        // revisit if startup matters more.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Talon"
            // jpackage requires MAJOR > 0, but the Android-side
            // versionName starts with "0." today (0.5.0). To keep
            // installer filenames in lockstep with the release tag,
            // map "0.MINOR.PATCH" → "1.MINOR.PATCH" until the project
            // crosses 1.0 for real. After that the read-through is
            // straight identity. Source of truth is
            // composeApp/build.gradle.kts:versionName (also what the
            // release workflow's tag check reads).
            packageVersion = derivePackageVersion()
            description = "Native chat client for Urbit"
            copyright = "© 2026 ~nisfeb"
            vendor = "nisfeb"

            // java.naming = JNDI for OkHttp DNS, java.sql = JDBC stubs
            // pulled in by androidx.sqlite-bundled. Without these the
            // jpackage runtime image can't open a connection at all.
            modules("java.naming", "java.sql")

            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
            // macOS .icns intentionally omitted — generating a real
            // ICNS needs `iconutil` (macOS) or `png2icns`. Mac builds
            // fall back to jpackage's default icon until a real
            // src/desktopMain/resources/icon.icns is dropped in.
        }
    }
}

/**
 * Map "0.M.P → 1.M.P" so jpackage's MAJOR > 0 constraint is satisfied
 * while the project is still pre-1.0. Reads versionName from this
 * very file to stay in lockstep with the Android side. After the
 * project crosses 1.0 the map is straight identity.
 */
fun derivePackageVersion(): String {
    val raw = "0.6.1"
    val parts = raw.split(".")
    if (parts.size < 3) return "1.0.0"
    val major = parts[0].toIntOrNull() ?: return "1.0.0"
    return if (major == 0) "1.${parts[1]}.${parts[2]}" else raw
}

// Room 2.7 KMP generates per-target. We attach the room-compiler
// KSP processor to each leaf target individually so the generated
// DAO impls + AppDatabase_Impl land in the right source set. The
// kotlin.sourceSets DSL doesn't expose ksp(...) helpers in this
// shape (yet), so the per-target configurations are referenced by
// name via the top-level dependencies block.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

ksp {
    // Export schemas so we can audit migrations. Desktop and
    // Android both write here; Room namespaces the JSON output by
    // database name + version, so the two targets coexist.
    arg("room.schemaLocation", "$projectDir/schemas")
}
