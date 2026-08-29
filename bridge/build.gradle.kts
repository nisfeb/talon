// Talon party-line bridge. A headless participant that joins a line
// with its own ship and moves audio between it and a pluggable PCM
// source/sink — a WAV today, an Icecast or SIP stream behind the same
// interface later.
//
// Lives in this repo rather than its own because it *is* the call
// stack: PartyLine, CallController, TrunkWire, UrbitSession and
// DesktopPeerLink are used verbatim from :core. A separate repo would
// mean publishing :core as a library or duplicating the wire client,
// and a duplicated wire client is exactly how drift happens.
//
// Build with `./gradlew :bridge:installDist`, then
// `bridge/build/install/bridge/bin/bridge [config.properties]`.

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Repositories live in settings.gradle.kts (FAIL_ON_PROJECT_REPOS).

kotlin {
    // 17, matching :core and :relay. The launcher script uses whatever
    // `java` is on PATH, so a box with an older default JRE needs
    // JAVA_HOME set — same as the relay.
    jvmToolchain(17)
}

dependencies {
    // The whole point: the same call stack the app runs.
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // libwebrtc via JNI. :core exposes the API; the natives are
    // per-platform and only the host's are bundled, matching how the
    // desktop app is packaged.
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
    val natives = "dev.onvoid.webrtc:webrtc-java:" + libs.versions.webrtcJava.get() + ":" + webrtcNatives
    runtimeOnly(natives)
    // AudioPathTest moves real samples across a real peer connection,
    // so the tests need the natives too — runtimeOnly does not reach
    // the test runtime classpath.
    testRuntimeOnly(natives)

    // Silences OkHttp's "no SLF4J providers" banner on every start.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.nisfeb.talon.bridge.Bridge")
    applicationName = "talon-bridge"
}

tasks.test {
    useJUnitPlatform()
}
