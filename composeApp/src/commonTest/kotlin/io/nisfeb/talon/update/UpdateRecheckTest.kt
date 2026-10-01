package io.nisfeb.talon.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "users are reporting that the desktop client is not prompting them
 * upgrade and it is not upgrading itself." Desktop asked only at launch,
 * and Talon lives in the tray for days.
 */
class UpdateRecheckTest {
    private fun manifest(code: Int) = """{"versionCode":$code,"versionName":"1.8.$code","url":"https://example.test/t.apk",
        "sha256":"${"a".repeat(64)}","minSdk":26}"""

    @Test
    fun a_release_that_comes_out_while_the_app_runs_is_offered() = runTest {
        var published: String? = null
        var asked = 0
        val checker = object : UpdateChecker {
            override suspend fun check(): UpdateManifest? { asked++; return published?.let(UpdateManifest::parse) }
        }
        val offered = mutableListOf<Int>()
        val running = launch { keepCheckingForUpdates(checker, UPDATE_RECHECK_MS) { offered += it.versionCode } }
        runCurrent()
        assertEquals(1, asked, "asked at launch")
        published = manifest(442) // released an hour later, the app still open
        advanceTimeBy(UPDATE_RECHECK_MS + 1)
        runCurrent()
        assertEquals(listOf(442), offered)
        running.cancel()
    }

    @Test
    fun the_network_is_asked_only_as_often_as_the_throttle_allows() = runTest {
        var requests = 0
        var clock = 10 * UPDATE_MIN_INTERVAL_MS
        var last = 0L
        val checker = HttpUpdateChecker(
            http = HttpClient(MockEngine { requests++; respond(manifest(441), HttpStatusCode.OK) }),
            url = "https://example.test/latest.json",
            now = { clock },
            lastCheckedAtMs = { last },
            recordCheckedAt = { last = it },
            minIntervalMs = UPDATE_MIN_INTERVAL_MS,
        )
        assertTrue(checker.check() != null)
        clock += UPDATE_RECHECK_MS
        assertEquals(null, checker.check(), "an hour on: not asked again")
        clock += UPDATE_MIN_INTERVAL_MS
        assertTrue(checker.check() != null)
        assertEquals(2, requests)
    }
}
