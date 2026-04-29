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
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.security.crypto)
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
            // app/build.gradle.kts:versionName (also what the release
            // workflow's tag check reads).
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
 * Read app/build.gradle.kts's versionName, apply the "0.M.P → 1.M.P"
 * map for jpackage's MAJOR > 0 constraint. Falls back to "1.0.0"
 * if the file or property is missing — so a fresh checkout that
 * runs a desktop task before app/ exists still gets a valid
 * installer (relevant during Stage B-style restructures).
 */
fun derivePackageVersion(): String {
    val appBuild = rootProject.file("app/build.gradle.kts")
    if (!appBuild.exists()) return "1.0.0"
    val match = Regex("""versionName\s*=\s*"([^"]+)"""")
        .find(appBuild.readText()) ?: return "1.0.0"
    val raw = match.groupValues[1]
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
