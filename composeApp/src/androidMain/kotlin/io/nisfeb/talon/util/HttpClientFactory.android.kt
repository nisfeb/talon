package io.nisfeb.talon.util

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The pre-Ktor build ran every request — pokes, acks, scries and the
 * SSE — through one hand-tuned OkHttpClient with no read timeout. The
 * bare Ktor `OkHttp` engine instead inherits OkHttp's default 10s read
 * timeout and builds its own client, which undercut per-call timeouts
 * (slow-ship pokes failed) and diverged from that shared-connection
 * transport (posts felt slower). Hand Ktor the same client as
 * `preconfigured` so desktop/android networking matches master exactly:
 * readTimeout=0, 15s connect/write, one shared connection pool.
 */
private val sharedOkHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

actual fun httpEngineFactory(): HttpClientEngineFactory<*> =
    object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
            OkHttp.create {
                preconfigured = sharedOkHttp
                block()
            }
    }
