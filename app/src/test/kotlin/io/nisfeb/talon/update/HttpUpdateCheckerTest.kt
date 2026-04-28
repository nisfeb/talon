package io.nisfeb.talon.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HttpUpdateCheckerTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    private val sample = """
        {
          "versionCode": 21,
          "versionName": "0.5.0",
          "url": "https://example.com/talon-0.5.0.apk",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "minSdk": 26,
          "changelog": "x",
          "mandatory": false
        }
    """.trimIndent()

    @Test fun `returns parsed manifest on 200`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 0L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = {},
            minIntervalMs = 0,
        )
        val m = checker.check()!!
        assertEquals(21, m.versionCode)
    }

    @Test fun `returns null on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 0L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = {},
            minIntervalMs = 0,
        )
        assertNull(checker.check())
    }

    @Test fun `respects rate limit`() = runBlocking {
        // Last check was 1s ago, min interval is 12h — should skip the network.
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 1_000L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = {},
            minIntervalMs = 12 * 60 * 60 * 1000L,
        )
        assertNull(checker.check())
        // Server was not hit:
        assertEquals(0, server.requestCount)
    }

    @Test fun `records last-checked timestamp on success`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        var recorded: Long? = null
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 12_345L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = { recorded = it },
            minIntervalMs = 0,
        )
        checker.check()
        assertEquals(12_345L, recorded)
    }
}
