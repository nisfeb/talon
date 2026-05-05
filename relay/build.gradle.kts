// Talon notification push relay. JVM-only Ktor server packaged as
// a fat-runnable distribution (./gradlew :relay:installDist) and as
// a Docker image (relay/Dockerfile).
//
// See docs/notifications-bulletproof.md for the design rationale.
// In short: the relay maintains an SSE connection to each registered
// user's ship 24×7, and POSTs to that user's UnifiedPush distributor
// endpoint when %activity events arrive. This is the layer that
// survives OEM background-killing, app force-stop, and process
// death — the things client-side code can't recover from on its own.
//
// Zero Google dependency by design — no FCM, no Play Services. The
// device registers with whatever UnifiedPush distributor it has
// installed (ntfy, NextPush, Conversations, etc.) and hands us the
// resulting HTTPS endpoint URL. We POST to it.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Repositories live in settings.gradle.kts (FAIL_ON_PROJECT_REPOS).

dependencies {
    // Ktor server + JSON serialization. Netty engine is the most
    // boring well-trodden choice; CIO would also work but the call
    // log + status-pages plugin matrix is best-tested on Netty.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    // OkHttp client for talking back to user ships (SSE + scry/poke).
    // Same library Talon-the-app uses, so the wire-shape parsers can
    // be cross-built later if we ever want to share them.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Per-user device registry. SQLite is plenty for the relay's
    // expected fan-out (1000s of users), and a single-file db keeps
    // the Docker volume mount story trivial.
    implementation(libs.sqlite.jdbc)

    // No Firebase / FCM. Push dispatch is plain HTTP POST to the
    // UnifiedPush distributor endpoint each device registered with
    // us — see Push.kt and https://unifiedpush.org for the protocol.

    // Slf4j-simple is the no-nonsense backend. Ktor logs go through
    // it; structured-logging is a follow-up if/when ops needs it.
    implementation(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

application {
    mainClass.set("io.nisfeb.talon.relay.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnit()
}
