package io.nisfeb.talon.util

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

/** iOS binds the shared HttpClient to Darwin (NSURLSession). */
actual fun httpEngineFactory(): HttpClientEngineFactory<*> = Darwin
