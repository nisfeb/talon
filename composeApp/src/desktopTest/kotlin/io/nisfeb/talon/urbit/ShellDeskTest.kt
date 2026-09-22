package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Orrery and armillary are desks of the Grubbery shell, published by
 * the same ship but not part of grubbery, so kiln can never fetch
 * them: `|install ~ricsul-bilwyt %grubbery` brings the shell and
 * nothing of theirs. What brings them is the shell's own route, which
 * is an authenticated call, which is why Talon can offer a button at
 * all.
 */
class ShellDeskTest {
    private fun ship(shellThere: Boolean, asked: MutableList<Pair<String, String>>) = HttpClient(
        MockEngine { req ->
            val path = req.url.encodedPath
            asked += path to ((req.body as? TextContent)?.text ?: "")
            when {
                path.endsWith("/manifest.webmanifest") ->
                    if (shellThere) respond("{}", HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
                else -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    )

    @Test
    fun `the desk is asked for by name and where its code lives`() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        assertTrue(LatticeInstall.addDesk(ship(true, asked), "https://ship.test", "orrery").isSuccess)
        val (path, body) = asked.last()
        assertEquals("/apps/grubbery/desks/add", path)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("orrery", o["name"]!!.jsonPrimitive.content)
        assertEquals(
            "~ricsul-bilwyt/apps/shell.shell/desks/orrery.desk/desk/code",
            o["code"]!!.jsonPrimitive.content,
            "the shell reads the code from the publisher's own tree",
        )
    }

    @Test
    fun `a shell already here is not installed again`() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        var pokes = 0
        val add = LatticeInstall.shellDesk(
            ship(shellThere = true, asked = asked),
            { "https://ship.test" },
            name = "armillary",
            answers = { true },
        ) { _, _, _ -> pokes++; true }
        assertTrue(add().isSuccess)
        assertEquals(0, pokes, "no kiln poke where the shell is already on the ship")
        assertTrue(asked.any { it.first == "/apps/grubbery/desks/add" })
    }

    @Test
    fun `a desk that has not answered yet says what is left to do`() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val add = LatticeInstall.shellDesk(
            ship(shellThere = true, asked = asked),
            { "https://ship.test" },
            name = "orrery",
            answers = { false },
            timeoutMs = 10,
        ) { _, _, _ -> true }
        val why = add().exceptionOrNull()?.message.orEmpty()
        // Not "the install failed": the shell syncs over the network,
        // and the roads the desk reaches are the owner's to approve.
        assertTrue("approve what it reaches" in why, why)
        assertTrue("/apps/orrery" in why, why)
    }
}
