package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Installing the desk. The claim worth pinning is that kiln accepting
 * the poke is not the desk being here: it arrives over the network
 * afterwards, and a caller that stopped at the poke would report
 * success onto a ship with no app on it.
 */
class LatticeInstallTest {

    private fun http(installedAfter: Int): Pair<HttpClient, () -> Int> {
        var asks = 0
        val c = HttpClient(
            MockEngine {
                asks++
                if (asks > installedAfter) respond("{}", HttpStatusCode.OK)
                else respondError(HttpStatusCode.NotFound)
            },
        )
        return c to { asks }
    }

    private val yes: suspend (String, String, JsonElement) -> Boolean = { _, _, _ -> true }
    private val no: suspend (String, String, JsonElement) -> Boolean = { _, _, _ -> false }

    @Test
    fun `a refused poke is a refusal, not a wait`() = runTest {
        val (c, asks) = http(installedAfter = 0)
        val r = LatticeInstall.installAndWait(c, "https://ship", no, wait = {})
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("refused"))
        assertEquals(0, asks(), "nothing is asked for after a refusal")
    }

    @Test
    fun `success is the desk answering, not the poke landing`() = runTest {
        val (c, asks) = http(installedAfter = 2)
        var clock = 0L
        val r = LatticeInstall.installAndWait(
            c, "https://ship", yes,
            nowMs = { clock }, wait = { clock += it },
        )
        assertTrue(r.isSuccess)
        assertEquals(3, asks(), "it kept asking until the desk was there")
    }

    @Test
    fun `a timeout says it may still land`() = runTest {
        // Never installs. The message matters: a timeout is not a
        // refusal, and telling somebody it failed would be wrong.
        val (c, _) = http(installedAfter = Int.MAX_VALUE)
        var clock = 0L
        val r = LatticeInstall.installAndWait(
            c, "https://ship", yes,
            timeoutMs = 9_000,
            nowMs = { clock }, wait = { clock += it },
        )
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("still finish"))
    }

    @Test
    fun `the poke is the one kiln understands`() {
        val (app, mark, _) = LatticeInstall.installPoke()
        assertEquals("hood", app)
        assertEquals("kiln-install", mark)
    }
}
