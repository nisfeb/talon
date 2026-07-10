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
    }
}
