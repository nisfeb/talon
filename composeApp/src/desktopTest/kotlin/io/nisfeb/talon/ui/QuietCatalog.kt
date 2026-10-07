package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.ai.ModelCatalog

/**
 * A model catalog that reaches no network: AI settings fetches each
 * provider's models when it opens, and a test must not ask a real
 * provider. Every list is refused, so nothing a test set up changes.
 */
fun quietCatalog() = ModelCatalog(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
