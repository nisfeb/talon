package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `a poke that does not land starts no wait`() = runTest {
        val (c, asks) = http(installedAfter = 0)
        val r = LatticeInstall.installAndWait(c, "https://ship", no, wait = {})
        assertTrue(r.isFailure)
        // Not "your ship refused it": a poke fails when the channel is
        // down or the session has gone, and blaming the ship for that
        // sent people looking in the wrong place.
        assertTrue(r.exceptionOrNull()!!.message!!.contains("did not take the install"))
        assertEquals(0, asks(), "nothing is asked for after a poke that did not land")
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

    @Test
    fun `another desk without a probe is refused up front`() {
        // The default probe reads the lattice manifest, which only the
        // lattice desk serves — watching it for any other desk would
        // wait out the whole timeout and report failure onto a desk
        // that arrived long ago.
        val e = assertFailsWith<IllegalArgumentException> {
            LatticeInstall.installer(
                HttpClient(MockEngine { respond("{}") }),
                { "https://ship" },
                desk = "wiki",
                poke = yes,
            )
        }
        assertTrue(e.message!!.contains("installed"), e.message)
    }

    @Test
    fun `another desk waits on its own probe, not the lattice manifest`() = runTest {
        var httpAsks = 0
        var probes = 0
        var pokedDesk: String? = null
        val install = LatticeInstall.installer(
            HttpClient(MockEngine { httpAsks++; respond("{}") }),
            { "https://ship" },
            desk = "wiki",
            installed = { ++probes > 2 },
            poke = { _, _, body ->
                pokedDesk = body.jsonObject["desk"]?.jsonPrimitive?.content
                true
            },
        )
        val r = install()
        assertTrue(r.isSuccess)
        assertEquals(3, probes, "it kept probing until the desk was there")
        assertEquals(0, httpAsks, "the lattice manifest probe never fired")
        assertEquals("wiki", pokedDesk, "kiln was asked for the right desk")
    }

    @Test
    fun `a grubbery app is not installed as a desk of its own`() {
        // The calendar, mail and lattice live inside the grubbery desk.
        // Asking kiln for a desk called "calendar" is a poke the ship
        // takes and nothing arrives from, which is a button that never
        // finishes and the reason two apps "failed to install".
        for (app in LatticeInstall.GRUBBERY_APPS - LatticeInstall.DESK) {
            assertFailsWith<IllegalArgumentException>("installing $app as a desk must not compile past here") {
                LatticeInstall.installer(
                    HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
                    { "https://ship" },
                    desk = app,
                    installed = { true },
                ) { _, _, _ -> true }
            }
        }
    }

    /** A ship's Grubbery, with or without its shell, for [LatticeInstall.grubbery]. */
    private class Shell(var here: Boolean, var synced: Boolean = false, var pendingAsks: Boolean = false) {
        val posts = mutableListOf<String>()
        val cookies = mutableListOf<String?>()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            if (req.method == io.ktor.http.HttpMethod.Post) { posts += path; cookies += req.headers["Cookie"] }
            when {
                !here -> respond("", HttpStatusCode.NotFound)
                path.endsWith("/desks/sync-defaults") -> { synced = true; respond("syncing") }
                path.endsWith("/desks/stock") -> respond(
                    """[{"name":"lattice","synced":$synced},{"name":"auspex","synced":$synced}]""",
                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                )
                path.endsWith("/asks.json") -> respond(if (pendingAsks) """[{"app":"/apps/x","poke":[{"road":"/sys/eyre/"}],"peek":[],"make":[]}]""" else "[]")
                path.endsWith("/approved.json") -> respond("{}")
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    // A user had grubbery and none of its apps: installing grubbery again
    // only synced it from its publisher, and the apps stayed missing.
    @Test
    fun `a ship with the shell is not given grubbery again, only its apps`() = runTest {
        val shell = Shell(here = true)
        var poked = false
        val install = LatticeInstall.grubbery(shell.http, { "https://ship" }, cookie = { "session=placeholder" }, answers = { shell.synced }) { _, _, _ -> poked = true; true }
        assertTrue(install().isSuccess)
        assertEquals(false, poked, "kiln was not asked for grubbery")
        assertEquals(listOf("/apps/grubbery/desks/sync-defaults"), shell.posts)
        assertEquals(listOf<String?>("session=placeholder"), shell.cookies, "the shell is asked as its owner")
    }

    @Test
    fun `a ship without the shell gets grubbery first, then its apps`() = runTest {
        val shell = Shell(here = false)
        var asked: Triple<String, String, JsonElement>? = null
        val install = LatticeInstall.grubbery(shell.http, { "https://ship" }, cookie = { null }, answers = { shell.synced }) { app, mark, body ->
            asked = Triple(app, mark, body)
            shell.here = true
            true
        }
        assertTrue(install().isSuccess)
        assertEquals("kiln-install", asked?.second)
        assertEquals(LatticeInstall.DESK, (asked?.third as JsonObject)["desk"]?.jsonPrimitive?.content)
        assertEquals(listOf("/apps/grubbery/desks/sync-defaults"), shell.posts)
    }

    // A fetched desk answers nothing until the owner approves what it
    // reaches, so waiting out the clock would only say "still waiting".
    @Test
    fun `apps fetched and waiting on the owner say so`() = runTest {
        val shell = Shell(here = true, pendingAsks = true)
        val install = LatticeInstall.grubbery(shell.http, { "https://ship" }, cookie = { null }, answers = { false }) { _, _, _ -> true }
        val r = install()
        assertEquals(LatticeInstall.APPROVE_APPS, r.exceptionOrNull()?.message)
    }

    // A probe that failed read as absent: the Apps page offered an
    // install over a working desk, and adding a shell desk sent kiln an
    // install of grubbery onto a ship that had it.
    @Test
    fun `only a 404 is absent, and a probe that could not ask is not knowing`() = runTest {
        fun answering(status: HttpStatusCode?) = HttpClient(MockEngine {
            if (status == null) throw IllegalStateException("offline") else respond("{}", status)
        })
        for ((status, want) in listOf(HttpStatusCode.OK to true, HttpStatusCode.NotFound to false,
            HttpStatusCode.Forbidden to null, HttpStatusCode.InternalServerError to null, null to null)) {
            assertEquals(want, LatticeInstall.installedOrUnknown(answering(status), "https://ship"), "lattice, $status")
            assertEquals(want, GroupsInstall.installedOrUnknown(answering(status), "https://ship"), "groups, $status")
        }
        assertEquals(false, LatticeInstall.isInstalled(answering(HttpStatusCode.InternalServerError), "https://ship"), "the arrival poll keeps waiting")
    }

    @Test
    fun `a shell desk asked for where the shell could not be probed installs no grubbery`() = runTest {
        var installs = 0
        val http = HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("manifest.webmanifest")) respond("down", HttpStatusCode.BadGateway)
            else respond("{}", HttpStatusCode.OK)
        })
        LatticeInstall.shellDesk(http, { "https://ship" }, name = "calendar", answers = { true }, timeoutMs = 1_000) { _, _, _ -> installs++; true }()
        assertEquals(0, installs)
    }
}
