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
    val push = Push()
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    val pool = ConnectionPool(db = db, push = push, masterSecret = masterSecret)

    log.info("starting relay on :$port (db=$dbPath, transport=unifiedpush)")
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
