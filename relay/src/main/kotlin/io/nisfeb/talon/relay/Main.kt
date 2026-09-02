package io.nisfeb.talon.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Talon notification push relay entry point.
 *
 * Push transport is UnifiedPush — devices register with a local
 * distributor (ntfy, NextPush, …) and hand us the resulting HTTPS
 * endpoint URL. We POST to it. Zero Google dependency.
 *
 * Required env:
 *   RELAY_MASTER_SECRET   — encrypts ship cookies at rest. Rotate
 *                           and every cookie row goes opaque; users
 *                           must re-register.
 *
 * Optional env:
 *   RELAY_PORT  — default 8080.
 *   RELAY_DB    — sqlite path, default ./relay.db.
 */
fun main() {
    val log = LoggerFactory.getLogger("Main")
    val port = (System.getenv("RELAY_PORT") ?: "8080").toInt()
    val dbPath = System.getenv("RELAY_DB") ?: "relay.db"
    val masterSecret = System.getenv("RELAY_MASTER_SECRET")
        ?: error("RELAY_MASTER_SECRET env var is required")

    val db = Db(dbPath).also { it.migrate() }
    // APNs VoIP is optional: set the four APNS_* vars to enable native
    // iOS call ringing. Absent, the relay runs UnifiedPush-only and an
    // ios-voip device's rings are dropped with a warning.
    val apns = buildApns(log)
    val push = Push(apns)
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    val pool = ConnectionPool(db = db, push = push, masterSecret = masterSecret)

    log.info("starting relay on :$port (db=$dbPath, unifiedpush + apns=${apns != null})")
    pool.startAll()

    val server = embeddedServer(Netty, port = port) {
        install(CallLogging)
        installRoutes(db = db, pool = pool, masterSecret = masterSecret, httpClient = httpClient)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("shutting down")
        pool.stopAll()
        server.stop(1_000, 5_000)
    })
    server.start(wait = true)
}

/**
 * Build the APNs VoIP sender from env, or null if not fully
 * configured. All four required vars must be present; APNS_P8 is the
 * .p8 key's PEM contents, or APNS_P8_FILE a path to it.
 *
 *   APNS_TEAM_ID      Apple team id (10 chars)
 *   APNS_KEY_ID       the .p8 key's id (10 chars)
 *   APNS_P8 / APNS_P8_FILE   the .p8 PEM, inline or by path
 *   APNS_BUNDLE_ID    default io.nisfeb.talon; topic is "<id>.voip"
 *   APNS_PRODUCTION   "true" (default) for TestFlight/App Store
 */
private fun buildApns(log: org.slf4j.Logger): Apns? {
    val teamId = System.getenv("APNS_TEAM_ID")
    val keyId = System.getenv("APNS_KEY_ID")
    val p8 = System.getenv("APNS_P8")
        ?: System.getenv("APNS_P8_FILE")?.let { java.io.File(it).takeIf(java.io.File::exists)?.readText() }
    if (teamId.isNullOrBlank() || keyId.isNullOrBlank() || p8.isNullOrBlank()) {
        log.info("APNs not configured (set APNS_TEAM_ID/APNS_KEY_ID/APNS_P8); iOS VoIP disabled")
        return null
    }
    val bundleId = System.getenv("APNS_BUNDLE_ID") ?: "io.nisfeb.talon"
    val production = (System.getenv("APNS_PRODUCTION") ?: "true").toBoolean()
    return Apns(teamId, keyId, p8, bundleId, production).also {
        log.info("APNs VoIP enabled (team=$teamId key=$keyId bundle=$bundleId prod=$production)")
    }
}
