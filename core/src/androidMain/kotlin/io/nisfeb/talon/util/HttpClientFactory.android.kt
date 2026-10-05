package io.nisfeb.talon.util

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import android.os.Build
import okhttp3.DnsCache
import okhttp3.OkHttpClient
import okhttp3.android.AndroidDns
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

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
    .apply { echDnsOrNull()?.let(::dns) }
    .build()

/**
 * Encrypted Client Hello where Android does it (17, API 37): AndroidDns
 * also fetches a host's HTTPS record, which carries its ECH keys, and the
 * network security config turns ECH on (targetSdk is below 37). Null
 * below 37, where OkHttp keeps its usual resolver. Addresses are not
 * cached (AndroidDns would keep them 10 s), so a ship on a LAN name
 * follows a network switch at once. A host only gets ECH if it publishes
 * the keys, which CDNs do and ships rarely do.
 */
fun echDnsOrNull(): okhttp3.Dns? =
    if (Build.VERSION.SDK_INT >= 37) {
        AndroidDns(dnsCache = DnsCache(minimumTimeToLive = Duration.ZERO, failureTimeToLive = Duration.ZERO))
    } else {
        null
    }

/**
 * Every client built from this shares [sharedOkHttp], so DO NOT CLOSE
 * one. Closing a Ktor client built on a preconfigured OkHttpClient
 * shuts down that client's dispatcher, which is process-wide: every
 * request the app makes afterwards fails with "executor rejected"
 * until it is restarted, and nothing about the failure points back
 * here. Build one client and keep it, or use the app's own.
 */
actual fun httpEngineFactory(): HttpClientEngineFactory<*> =
    object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
            OkHttp.create {
                preconfigured = sharedOkHttp
                block()
            }
    }
