package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The block people paste around. The two numbers are the whole point:
 * a digest and a signature are atoms far past any integer type, so they
 * are carried as their digits and must survive the round trip exactly.
 *
 * The wire half is pinned too: the ship's /verify route reads life,
 * digest and sig as JSON numbers, so they must go up unquoted, and a
 * ship without lattice 31 must say so rather than fail mysteriously.
 */
class LatticeSignTest {
    private val digest = "7".repeat(78)
    private val sig = "9".repeat(154)
    private val rec = SignedRecord(
        ship = "~sampel-palnet", life = "3", alg = "ed25519", salt = "lattice", digest = digest, sig = sig,
    )

    @Test
    fun `a record is found in the middle of a pasted message`() {
        val pasted = "here you go:\n\n${rec.armor()}\n\nthat's the file I mentioned"
        assertEquals(rec, signedRecordIn(pasted))
    }

    @Test
    fun `text with no block, or a broken one, reads as nothing`() {
        assertNull(signedRecordIn("no signature here"))
        assertNull(signedRecordIn(rec.armor().replace(sig, "not-a-number")))
        assertNull(signedRecordIn(rec.armor().substringBefore("-----END")))
    }

    private fun signClient(
        body: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): LatticeSign =
        LatticeSign(
            HttpClient(MockEngine { respond(body, status) }),
            "https://ship",
        )

    @Test
    fun `a ship without lattice 31 is too old to sign`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            signClient(status = HttpStatusCode.NotFound).sign("hi")
        }
        assertTrue(e.message!!.contains("too old"), e.message)
    }

    @Test
    fun `the verdict comes from the ok field`() = runTest {
        assertEquals(Verdict.Ok, signClient("""{"ok":"true"}""").verify(rec))
        assertEquals(
            Verdict.No("sig does not match"),
            signClient("""{"ok":"false","reason":"sig does not match"}""").verify(rec),
        )
    }

    @Test
    fun `life digest and sig go up as bare numbers`() = runTest {
        var seen: String? = null
        val http = HttpClient(
            MockEngine { req ->
                seen = (req.body as TextContent).text
                respond("""{"ok":"true"}""", HttpStatusCode.OK)
            },
        )
        LatticeSign(http, "https://ship").verify(rec, "the file")
        // The route slavs these as JSON numbers: quoting any of them
        // turns the check into a 400. life rides along unquoted too —
        // it is digit-validated at parse time (signedRecordIn).
        assertEquals(
            "{\"ship\":\"~sampel-palnet\",\"life\":3,\"digest\":$digest," +
                "\"sig\":$sig,\"content\":\"the file\"}",
            seen,
        )
    }
}
