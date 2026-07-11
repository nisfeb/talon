package io.nisfeb.talon.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout

/** Platform HTTP engine: OkHttp on JVM (Android + desktop), Darwin on iOS. */
expect fun httpEngineFactory(): HttpClientEngineFactory<*>

/**
 * The app's shared HttpClient — Ktor over the platform engine, replacing
 * the OkHttpClient that used to be threaded through commonMain.
 *
 * No request-timeout default: the SSE channel (UrbitChannel) must stay
 * open indefinitely, so scry/poke set their own per-request timeout the
 * way OkHttp's per-call timeout used to. Cookies aren't installed here;
 * UrbitSession derives its own cookie-scoped client via HttpClient.config.
 */
fun createAppHttpClient(): HttpClient = HttpClient(httpEngineFactory()) {
    expectSuccess = false
    followRedirects = true
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        // socketTimeoutMillis is the max gap between reads, not the total
        // request time. Left unset, the engine default (OkHttp = 10s) leaks
        // in and undercuts every per-call requestTimeoutMillis — a poke to a
        // slow ship that stays silent >10s dies with "socket timeout has
        // expired" long before its own 30s budget. Streaming (SSE, AI) never
        // hit it because bytes keep arriving. Raise it well past the longest
        // per-call budget (MCP = 120s) so the per-call timeout is the real
        // authority; the SSE already tolerates the old 10s, so this only
        // relaxes it.
        socketTimeoutMillis = 180_000
    }
}
