package io.nisfeb.talon.urbit

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grubbery's stock apps on the way in: a ship that signs in gets the ones
 * it lacks, by name, and nothing it has is touched. The shell's own
 * parser (grubbery desk/gub/nex/shell.hoon): POST /desks/sync reads
 * `(jstr jon 'name')`; GET /desks/stock is `[{name, synced, …}]`.
 */
class DefaultAppsTest {
    private val ship = FakeShip("~zod")
    private val url = "https://ship.test"
    private val pokes = mutableListOf<FakeShip.Poke>()
    private var takePoke = true

    @BeforeTest
    fun quick() {
        DefaultApps.pollMs = 20
        DefaultApps.waitMs = 2_000
    }

    @AfterTest
    fun back() {
        DefaultApps.pollMs = 20_000
        DefaultApps.waitMs = 5 * 60_000
    }

    private fun stock(vararg apps: Pair<String, Boolean>) =
        apps.joinToString(",", "[", "]") { (n, s) -> """{"name":"$n","kind":"github","repo":"nisfeb/$n","ref":"main","synced":$s}""" }

    private val lacksTwo = stock("lattice" to true, "auspex" to true, "calendar" to true, "armillary" to false, "orrery" to false)

    private fun ensure(): List<String> = runBlocking {
        DefaultApps.ensure(ship.http, url, poke = { app, mark, body ->
            pokes += FakeShip.Poke(app, mark, body, "~zod")
            takePoke
        })
    }

    private fun syncs() = ship.api.filter { it.startsWith("POST /apps/grubbery/desks/sync ") }

    @Test
    fun `asks only for the apps the ship lacks, by name`() {
        ship.answerApi = { m, p, _ -> if (m == "GET" && p == "/apps/grubbery/desks/stock") lacksTwo else if (m == "POST") "syncing" else null }
        assertEquals(listOf("armillary", "orrery"), ensure())
        assertEquals(
            listOf(
                """POST /apps/grubbery/desks/sync {"name":"armillary"}""",
                """POST /apps/grubbery/desks/sync {"name":"orrery"}""",
            ),
            syncs(),
        )
        assertTrue(pokes.isEmpty(), "Grubbery is there: no install")
        assertTrue(ship.api.none { "sync-defaults" in it }, "never all at once: that repoints a desk that follows elsewhere")
    }

    @Test
    fun `a ship with every app is asked for nothing`() {
        ship.answerApi = { m, p, _ ->
            if (m == "GET" && p == "/apps/grubbery/desks/stock") stock("lattice" to true, "armillary" to true, "orrery" to true) else null
        }
        assertEquals(emptyList(), ensure())
        assertEquals(emptyList(), syncs())
        assertTrue(pokes.isEmpty())
    }

    @Test
    fun `no Grubbery installs it from ricsul, then asks for what its shell lacks`() {
        ship.answerApi = { m, p, _ ->
            when {
                // eyre's 404 for its owner until the desk is here.
                m == "GET" && p == "/apps/grubbery/desks/stock" -> if (pokes.isEmpty()) null else lacksTwo
                m == "POST" -> "syncing"
                else -> null
            }
        }
        assertEquals(listOf("armillary", "orrery"), ensure())
        val install = pokes.single()
        assertEquals("hood", install.app)
        assertEquals("kiln-install", install.mark)
        val o = install.json.jsonObject
        assertEquals(listOf("grubbery", "~ricsul-bilwyt", "grubbery"), listOf("local", "ship", "desk").map { o[it]!!.jsonPrimitive.content })
        assertEquals(2, syncs().size)
    }

    @Test
    fun `an install the ship does not take asks for nothing more`() {
        takePoke = false
        ship.answerApi = { _, _, _ -> null }
        assertEquals(emptyList(), ensure())
        assertEquals(1, pokes.size)
        assertEquals(emptyList(), syncs())
    }

    @Test
    fun `a Grubbery that never comes up is left to the Apps page`() {
        ship.answerApi = { _, _, _ -> null }
        assertEquals(emptyList(), ensure())
        assertEquals(1, pokes.size, "installed once, not again on every look")
        assertEquals(emptyList(), syncs())
    }

    @Test
    fun `an answer that is not a clear yes or no is left alone`() {
        // Not the owner, a ship that is down, a busy one, and a lapsed
        // login, which eyre sends to its login page: none is "no Grubbery".
        for ((status, type) in listOf(
            HttpStatusCode.Forbidden to "text/html",
            HttpStatusCode.BadGateway to "text/html",
            HttpStatusCode.InternalServerError to "text/plain",
            HttpStatusCode.OK to "text/html",
        )) {
            ship.answerApiOther = { m, p -> if (m == "GET" && p == "/apps/grubbery/desks/stock") status to type else null }
            assertEquals(emptyList(), ensure(), "$status $type")
        }
        assertTrue(pokes.isEmpty(), "no install over a ship that may have Grubbery")
        assertEquals(emptyList(), syncs())
    }

    @Test
    fun `a sync the shell refuses is not counted, and the rest still go`() {
        ship.answerApi = { m, p, _ -> if (m == "GET" && p == "/apps/grubbery/desks/stock") lacksTwo else if (m == "POST") "syncing" else null }
        // The request is logged before it is answered, so the last one is this one.
        ship.answerApiOther = { m, _ -> if (m == "POST" && "armillary" in ship.api.last()) HttpStatusCode.InternalServerError to "text/plain" else null }
        assertEquals(listOf("orrery"), ensure())
        assertEquals(2, syncs().size)
    }

    @Test
    fun `the approvals banner hears once the new apps ask for theirs`() {
        val before = DefaultApps.asked.value
        ship.answerApi = { m, p, _ ->
            when {
                m == "GET" && p == "/apps/grubbery/desks/stock" -> lacksTwo
                m == "POST" -> "syncing"
                // Nothing waits before the ask; the two new apps wait after it.
                p == "/apps/grubbery/asks.json" ->
                    if (syncs().size < 2) "[]"
                    else """[{"app":"armillary","poke":[],"peek":[],"make":[]},{"app":"orrery","poke":[],"peek":[],"make":[]}]"""
                p == "/apps/grubbery/approved.json" -> "{}"
                else -> null
            }
        }
        ensure()
        assertEquals(before + 1, DefaultApps.asked.value)
    }

    @Test
    fun `nothing asked for leaves the banner be`() {
        val before = DefaultApps.asked.value
        ship.answerApi = { m, p, _ ->
            if (m == "GET" && p == "/apps/grubbery/desks/stock") stock("armillary" to true) else null
        }
        ensure()
        assertEquals(before, DefaultApps.asked.value)
    }
}
