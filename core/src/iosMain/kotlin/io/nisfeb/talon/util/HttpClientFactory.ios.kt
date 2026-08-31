package io.nisfeb.talon.util

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig

/**
 * iOS binds the shared HttpClient to Darwin (NSURLSession) — with the
 * idle timeout disabled, which is not optional.
 *
 * NSURLSession's timeoutIntervalForRequest defaults to 60 seconds and
 * is an IDLE timer: it fires whenever no data has arrived for that
 * long, which is precisely what a quiet SSE stream is. Desktop and
 * Android configure OkHttp with readTimeout(0) for exactly this
 * reason; iOS shipped with a bare Darwin and never got the
 * equivalent.
 *
 * The field failure this caused was call signaling, not chat. Chat's
 * stream is busy and re-scries missed state on every reconnect, so a
 * killed connection healed invisibly. The calls channel is silent
 * between calls — cut every ~60s of quiet, reopened under a fresh
 * channel id with no backfill — so any %ring or %accept that landed
 * in a reconnect gap was gone. Android-to-iOS calls never rang, and
 * an iOS caller never learned the far end answered.
 *
 * Per-call limits still apply through Ktor's HttpTimeout plugin
 * (connect 15s; scries and pokes set their own request timeouts), the
 * same division of labour as the desktop factory.
 */
private const val WEEK_SECS = 604_800.0

actual fun httpEngineFactory(): HttpClientEngineFactory<*> =
    object : HttpClientEngineFactory<DarwinClientEngineConfig> {
        override fun create(block: DarwinClientEngineConfig.() -> Unit): HttpClientEngine =
            Darwin.create {
                configureSession {
                    setTimeoutIntervalForRequest(WEEK_SECS)
                    setTimeoutIntervalForResource(WEEK_SECS)
                }
                block()
            }
    }
