package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS fetcher client. The post-DNS internal-address guard needs a
 * per-hop DNS hook (JVM-only OkHttp `Dns`); on iOS we rely on the
 * common [isBlockedName] literal-hostname guard that runs before the
 * request. A full per-hop IP guard here awaits an NSURLSession hook.
 */
actual fun createUrlFetcherClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = true
}
