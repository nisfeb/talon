package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A link's preview is remembered only once the page has answered. A
 * fetch that failed, or was cancelled because its row scrolled away,
 * was remembered as "no preview", and the link never showed one again
 * until the app restarted.
 */
class LinkPreviewRetryTest {
    private val page = """<html><head><meta property="og:title" content="Seed swap"></head></html>"""
    private val answering = HttpClient(MockEngine { respond(page, HttpStatusCode.OK, headersOf("Content-Type", "text/html")) })

    @Test
    fun `a fetch that failed is asked again, not remembered as no preview`() = runBlocking {
        val url = "https://failed.example/${System.nanoTime()}"
        val offline = HttpClient(MockEngine { error("offline") })
        assertNull(LinkPreviewCache.await(offline, url))
        assertEquals("Seed swap", LinkPreviewCache.await(answering, url)?.title)
    }

    @Test
    fun `a fetch cancelled mid-way is asked again`() = runBlocking {
        val url = "https://cancelled.example/${System.nanoTime()}"
        val slow = HttpClient(MockEngine { awaitCancellation() })
        val row = launch { LinkPreviewCache.await(slow, url) }
        delay(100)
        row.cancel()
        row.join()
        assertEquals("Seed swap", LinkPreviewCache.await(answering, url)?.title)
    }
}
