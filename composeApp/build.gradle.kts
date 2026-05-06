import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
            // WorkManager periodic catch-up worker — survives force-
            // stop / app standby. The foreground service can't, so
            // this is the Layer 3 reconcile path for users who
            // killed Talon out of their recents.
            implementation(libs.androidx.work.runtime.ktx)
            // UnifiedPush connector — vendor-neutral push protocol.
            // Talks to whatever distributor app the user has
            // installed (ntfy / NextPush / Conversations / …) over
            // local IPC. Zero Google / Play Services dependency.
            //
            // Tink is excluded because the connector pulls it in for
            // RFC 8291 app-level webpush encryption — which we don't
            // use (our payloads are hint-only, the relay never carries
            // message content). Tink also drags in `protobuf-java`,
            // which collides with MediaPipe's `protobuf-javalite` at
            // packaging time. Dropping it here avoids the duplicate-
            // class explosion.
            implementation("org.unifiedpush.android:connector:${libs.versions.unifiedpush.get()}") {
                exclude(group = "com.google.crypto.tink")
            }
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
            // Compose Multiplatform UI test runner — `runComposeUiTest`,
            // `onNodeWithText`, `onNodeWithTag`, `performMouseInput`,
            // etc. Used by the pointer-input regression guards
            // (right-click handler dispatch) and the layout breakpoint
            // tests (ChatPaneScaffoldTest).
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}

// Force the desktopTest worker JVM to headless mode. CI runners and
// some local Linux dev installs ship a headless JDK with no
// `libawt_xawt.so`; runComposeUiTest still initialises AWT and would
// otherwise blow up with `Could not initialize class java.awt.Toolkit`.
// Skiko's software renderer drives the test composition either way —
// headless satisfies AWT's thread checks without requiring X11.
tasks.withType<Test>().configureEach {
    jvmArgs("-Djava.awt.headless=true")
}

// Single source of truth for the app version. `derivePackageVersion`
// reads this same value, which keeps desktop installer filenames in
// lockstep with the Android side. Earlier revisions hard-coded the
// version inside derivePackageVersion and silently drifted — every
// release between 0.7.14 and 0.7.23 shipped with stale .dmg/.msi/.deb
// filenames because nobody updated both literals.
val talonVersionCode = 95
val talonVersionName = "0.11.0-rc19"

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
        versionCode = talonVersionCode
        versionName = talonVersionName
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

    // Per-ABI APK splits. The 4 native libs (sqlite-bundled,
    // mediapipe text, onnxruntime, image-codecs from coil/skia) total
    // ~41 MB across all architectures. Each user only needs one
    // architecture. The universal APK stays enabled so the existing
    // GitHub Releases sideload flow keeps working unchanged; per-arch
    // APKs are produced alongside as smaller alternatives.
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
            // versionName still starts with "0." today (pre-1.0).
            // To keep installer filenames in lockstep with the
            // release tag, map "0.MINOR.PATCH" → "1.MINOR.PATCH"
            // until the project crosses 1.0 for real. After that
            // the read-through is straight identity. Source of
            // truth is composeApp/build.gradle.kts:versionName
            // (also what the release workflow's tag check reads).
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
            macOS {
                // .icns is generated on the macOS CI runner from icon.png
                // (see .github/workflows/release.yml's "Generate macOS
                // icon" step) since iconutil is macOS-only. The file
                // doesn't live in the repo because it's a build artifact.
                // When absent (any non-mac local build, or a CI run that
                // didn't go through the icns step), the macOS bundle
                // falls back to jpackage's default icon — but local
                // packageReleaseDmg from a Mac will still work if you
                // run scripts/build-macos-icns.sh first.
                val icnsFile = project.file("src/desktopMain/resources/icon.icns")
                if (icnsFile.exists()) {
                    iconFile.set(icnsFile)
                }
            }
        }
    }
}

/**
 * Map "0.M.P → 1.M.P" so jpackage's MAJOR > 0 constraint is satisfied
 * while the project is still pre-1.0. Reads `talonVersionName` (the
 * same constant `android.defaultConfig.versionName` reads) so the
 * Android APKs and desktop installers always agree. After the project
 * crosses 1.0 the map is straight identity.
 *
 * jpackage also requires strict NUM.NUM.NUM in the version string, so
 * any pre-release suffix on the patch component (e.g. "0-rc1") gets
 * stripped before joining. Android `versionName` keeps the full string
 * — only the desktop installer sees the truncated form.
 */
fun derivePackageVersion(): String {
    val parts = talonVersionName.split(".")
    if (parts.size < 3) return "1.0.0"
    val major = parts[0].toIntOrNull() ?: return "1.0.0"
    val patch = parts[2].substringBefore('-')
    return if (major == 0) "1.${parts[1]}.$patch" else "${parts[0]}.${parts[1]}.$patch"
}

// Manual smoke task for the desktop notifier — emits a real native
// notification so the path can be verified against the user's DE.
// Not part of any default lifecycle; run explicitly:
//     ./gradlew :composeApp:notifierSmoke
tasks.register<JavaExec>("notifierSmoke") {
    description = "Fire a single native notification via SystemNotifier (manual UX check)."
    group = "verification"
    val desktopMain = kotlin.targets.getByName("desktop")
        .compilations.getByName("main")
    classpath = files(
        desktopMain.output.allOutputs,
        desktopMain.runtimeDependencyFiles,
    )
    mainClass.set("io.nisfeb.talon.notify.SystemNotifierSmokeKt")
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

// ─── Native-lib slimming for the desktop release distributable ───
//
// DJL's onnxruntime-engine + huggingface tokenizers ship single fat
// JARs containing native libraries for every supported platform
// (linux-x64, linux-aarch64, osx-x64, osx-aarch64, win-x64). A
// jpackage installer for one host platform doesn't need the others —
// each platform's CI matrix entry produces a single .deb / .dmg /
// .msi from the same source distributable, and we want each artifact
// to ship only its host's natives.
//
// The slim step rewrites the bundled JARs in place, post-
// createReleaseDistributable but pre-package*. ONNX Runtime detects
// host platform and loads from a single subdir; absent subdirs are
// never enumerated, so removing them is invisible to the runtime.
//
// Pure JVM impl (no `zip` binary dep) so this works the same on
// every CI runner.

// Material Icons Extended keep-list, shared by [slimReleaseDistributable]
// (which strips everything not on the list) and [auditIconKeepList]
// (which verifies every Icons.X.Y reference in source resolves to either
// material-icons-core OR this list — runtime-NoClassDefFoundError guard).
//
// Sources of truth for additions:
//   grep -rhoE 'Icons\.(AutoMirrored\.)?(Filled|Outlined|Rounded|Sharp|TwoTone)\.[A-Za-z0-9_]+' \
//     --include='*.kt' composeApp/src | sort -u
// and verify each one against the contents of the
// material-icons-core-desktop JAR — anything found there does NOT
// belong in this list.
val iconsExtendedKeep = setOf(
    "androidx/compose/material/icons/automirrored/filled/LogoutKt.class",
    "androidx/compose/material/icons/filled/AttachFileKt.class",
    "androidx/compose/material/icons/filled/DownloadKt.class",
    "androidx/compose/material/icons/filled/DragHandleKt.class",
    "androidx/compose/material/icons/filled/ErrorOutlineKt.class",
    "androidx/compose/material/icons/filled/ExpandMoreKt.class",
    "androidx/compose/material/icons/filled/ImageKt.class",
    "androidx/compose/material/icons/filled/MicKt.class",
    "androidx/compose/material/icons/filled/NotificationsOffKt.class",
    "androidx/compose/material/icons/filled/PauseKt.class",
    "androidx/compose/material/icons/filled/PeopleKt.class",
    "androidx/compose/material/icons/filled/PushPinKt.class",
    "androidx/compose/material/icons/filled/ScheduleKt.class",
    "androidx/compose/material/icons/filled/StopKt.class",
    "androidx/compose/material/icons/filled/TopicKt.class",
    "androidx/compose/material/icons/filled/VisibilityKt.class",
    "androidx/compose/material/icons/filled/VisibilityOffKt.class",
)

val slimReleaseDistributable = tasks.register("slimReleaseDistributable") {
    description = "Strip non-host-platform native libs from bundled DJL/ONNX/tokenizers JARs in the release distributable."
    dependsOn("createReleaseDistributable")
    val distDirProvider = layout.buildDirectory.dir("compose/binaries/main-release/app/Talon")
    outputs.dir(distDirProvider)
    // Capture the keep-list into a local val at configuration time so
    // the doLast closure's serialized form holds a Set<String> value
    // instead of a free-variable reference to the script-level val
    // (which the configuration cache rejects as a "Gradle script
    // object reference"). Lifting iconsExtendedKeep to script scope
    // for the audit task to share broke the slim task's previously-
    // local capture.
    val keep: Set<String> = iconsExtendedKeep
    doLast {
        // All helpers inlined inside doLast so the configuration
        // cache doesn't have to serialize a reference back to the
        // build script's class for top-level helper functions
        // (which the cache rejects as "Gradle script object
        // references are not supported").
        val distDir = distDirProvider.get().asFile
        val libApp = File(distDir, "lib/app")
        if (!libApp.isDirectory) {
            println("slimReleaseDistributable: $libApp not found, skipping")
            return@doLast
        }
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val isArm = "aarch64" in osArch || "arm64" in osArch
        val (onnxKeep, tokKeep) = when {
            "linux" in osName && isArm -> "linux-aarch64" to "linux-aarch64"
            "linux" in osName -> "linux-x64" to "linux-x86_64"
            "mac" in osName && isArm -> "osx-aarch64" to "osx-aarch64"
            "mac" in osName -> "osx-x64" to "osx-x86_64"
            "windows" in osName -> "win-x64" to "win-x86_64"
            else -> error(
                "slimReleaseDistributable: unsupported host OS/arch: $osName/$osArch — " +
                    "add a mapping for it in build.gradle.kts"
            )
        }
        println("==> slimReleaseDistributable: keep onnx=$onnxKeep, tokenizers=$tokKeep")

        fun humanBytes(b: Long): String = when {
            b >= 1_000_000_000 -> "%.1f GB".format(b / 1_000_000_000.0)
            b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
            b >= 1_000 -> "%.1f kB".format(b / 1_000.0)
            else -> "$b B"
        }

        fun slimJarInPlace(jar: File, keep: (String) -> Boolean) {
            val before = jar.length()
            val tmp = File(jar.parentFile, jar.name + ".slim.tmp")
            var droppedBytes = 0L
            var droppedCount = 0
            ZipFile(jar).use { input ->
                ZipOutputStream(tmp.outputStream().buffered()).use { output ->
                    val entries = input.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!keep(entry.name)) {
                            droppedBytes += entry.size.coerceAtLeast(0)
                            droppedCount += 1
                            continue
                        }
                        val newEntry = ZipEntry(entry.name)
                        newEntry.time = entry.time
                        output.putNextEntry(newEntry)
                        input.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            if (droppedCount == 0) {
                tmp.delete()
                return
            }
            Files.move(
                tmp.toPath(), jar.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            val after = jar.length()
            println(
                "  slim ${jar.name.padEnd(55)} ${humanBytes(before)} → ${humanBytes(after)} " +
                    "(-$droppedCount entries, ${humanBytes(droppedBytes)} uncompressed)"
            )
        }

        libApp.listFiles { _, n -> n.startsWith("onnxruntime-") && n.endsWith(".jar") }
            ?.forEach { jar ->
                slimJarInPlace(jar) { entry ->
                    !entry.startsWith("ai/onnxruntime/native/") ||
                        entry.startsWith("ai/onnxruntime/native/$onnxKeep/")
                }
            }
        libApp.listFiles { _, n -> n.startsWith("tokenizers-") && n.endsWith(".jar") }
            ?.forEach { jar ->
                slimJarInPlace(jar) { entry ->
                    !entry.startsWith("native/lib/") ||
                        entry.startsWith("native/lib/$tokKeep/")
                }
            }

        // Material Icons Extended ships every Material icon (~11k
        // class files, 37 MB on desktop). Android R8 strips the
        // unused ones; jpackage has no equivalent step. The rest of
        // the icons we use come from the small material-icons-core
        // JAR we leave alone. Keep just the [iconsExtendedKeep]
        // entries plus the manifest. The [auditIconKeepList] task
        // (wired as a dependency below) verifies every Icons.X.Y
        // reference in source code resolves to either core or the
        // keep-list — so adding a new icon without updating the
        // list fails CI, not the user's runtime.
        libApp.listFiles { _, n ->
            n.startsWith("material-icons-extended-desktop-") && n.endsWith(".jar")
        }?.forEach { jar ->
            // Verify keep-list members all exist in the source JAR so
            // a typo or upstream rename surfaces here, not as a
            // ClassNotFoundException at runtime.
            val present = ZipFile(jar).use { zf ->
                keep.filter { zf.getEntry(it) != null }.toSet()
            }
            val missing = keep - present
            check(missing.isEmpty()) {
                "icons-extended slim: keep-list entries not found in ${jar.name}: $missing"
            }
            slimJarInPlace(jar) { entry ->
                when {
                    entry.endsWith("/") -> true
                    entry == "META-INF/MANIFEST.MF" -> true
                    entry.endsWith(".class") -> entry in keep
                    else -> false
                }
            }
        }
    }
}

afterEvaluate {
    listOf(
        "packageReleaseDeb", "packageReleaseDmg", "packageReleaseMsi",
        "packageReleaseAppImage", "packageReleaseDistributableForCurrentOS",
    ).forEach { name ->
        tasks.matching { it.name == name }.configureEach {
            dependsOn(slimReleaseDistributable)
        }
    }
}

// ─── Icons keep-list audit ───
//
// Greps composeApp/src for `Icons.X.Y` references and verifies each
// resolves to either the material-icons-core JAR (shipped whole) or
// the [iconsExtendedKeep] set above (preserved by the slim task). A
// reference that lives only in material-icons-extended without a
// keep-list entry is silently stripped at release build time and
// crashes the user's first paint with NoClassDefFoundError —
// regression class that bit 0.8.7 (PushPin), 0.8.7 (Logout), and
// 0.10.0-rc5 (People). This audit fails the build instead.
//
// Resolves the core JAR via a `Configuration` walk to stay
// independent of where Gradle caches the artifact.
val auditIconKeepList = tasks.register("auditIconKeepList") {
    description = "Verify every Icons.X.Y reference in composeApp/src resolves to material-icons-core or iconsExtendedKeep."
    group = "verification"
    val srcDir = file("src")
    val keepList = iconsExtendedKeep
    // Capture the resolved core jar path lazily via a Provider so the
    // configuration cache stays valid (no project state captured into
    // the action).
    val coreJarProvider: Provider<File?> = providers.provider {
        configurations
            .filter { it.isCanBeResolved && it.name.startsWith("desktop") }
            .firstNotNullOfOrNull { conf ->
                runCatching {
                    conf.resolvedConfiguration.resolvedArtifacts
                        .firstOrNull { it.name == "material-icons-core-desktop" }
                        ?.file
                }.getOrNull()
            }
    }
    inputs.dir(srcDir)
    inputs.property("keepList", keepList.toSortedSet().joinToString(","))
    doLast {
        val iconRegex = Regex(
            """Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined|Rounded|Sharp|TwoTone)\.[A-Za-z0-9_]+"""
        )
        val srcRefs = sortedSetOf<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                iconRegex.findAll(file.readText()).forEach { srcRefs.add(it.value) }
            }
        if (srcRefs.isEmpty()) {
            println("auditIconKeepList: no Icons.X.Y references found; skipping.")
            return@doLast
        }
        // Icons.Filled.PushPin -> androidx/compose/material/icons/filled/PushPinKt.class
        // Icons.AutoMirrored.Filled.Logout -> androidx/compose/material/icons/automirrored/filled/LogoutKt.class
        fun toClassPath(ref: String): String {
            val parts = ref.removePrefix("Icons.").split(".")
            val name = parts.last()
            val variantPath = parts.dropLast(1).joinToString("/") { it.lowercase() }
            return "androidx/compose/material/icons/$variantPath/${name}Kt.class"
        }
        val coreJar = coreJarProvider.orNull
        if (coreJar == null || !coreJar.isFile) {
            println("auditIconKeepList: material-icons-core-desktop jar not resolvable; skipping.")
            return@doLast
        }
        val coreClasses: Set<String> = ZipFile(coreJar).use { zf ->
            buildSet {
                val entries = zf.entries()
                while (entries.hasMoreElements()) add(entries.nextElement().name)
            }
        }
        val missing = mutableListOf<Pair<String, String>>()
        for (ref in srcRefs) {
            val classPath = toClassPath(ref)
            if (classPath !in coreClasses && classPath !in keepList) {
                missing.add(ref to classPath)
            }
        }
        check(missing.isEmpty()) {
            buildString {
                appendLine("auditIconKeepList: ${missing.size} icon reference(s) live in material-icons-extended without a keep-list entry:")
                missing.forEach { (ref, path) -> appendLine("  $ref  ->  $path") }
                appendLine()
                appendLine("Add each missing class path to `iconsExtendedKeep` in composeApp/build.gradle.kts.")
                appendLine("Without this, the desktop release build will strip the class and crash on first paint")
                appendLine("with NoClassDefFoundError — regression that bit 0.8.7 (PushPin/Logout) and 0.10.0-rc5 (People).")
            }
        }
        println("auditIconKeepList: ${srcRefs.size} icon reference(s) verified — all resolve to core or keep-list.")
    }
}

// Run the audit before the slim task strips and before the standard
// `check` verification target — so a missing keep-list entry fails
// CI at PR review time, not at the user's first launch of the
// release build.
slimReleaseDistributable.configure { dependsOn(auditIconKeepList) }
tasks.named("check") { dependsOn(auditIconKeepList) }
