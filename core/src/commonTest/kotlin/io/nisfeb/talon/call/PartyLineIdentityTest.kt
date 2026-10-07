package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who we are on a line, and what the room calls us. Galène's username is
 * the token's subject (a comet's mnemonym since trunk wire 10); the name
 * shown for us rides the join's `data`, and another member's lands on
 * their roster row.
 */
class PartyLineIdentityTest {
    private class FakeLink : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theJoinCarriesTheNameWeGoBy() {
        val line = PartyLine(HttpClient(), links = { _, _ -> FakeLink() }, displayName = { "Sampel" })
        assertEquals(
            """{"type":"join","kind":"join","group":"garden","token":"tok","data":{"name":"Sampel"}}""",
            line.joinFrame("garden", "tok").toString(),
        )
    }

    @Test
    fun aMemberNamesThemselvesOnTheirRow() = runTest {
        val line = PartyLine(HttpClient(), links = { _, _ -> FakeLink() }).also { it.markConnectingForTests("room") }
        line.handle(json.decodeFromString<JsonObject>("""{"type":"user","kind":"add","id":"c1","username":"$NYM","data":{"name":"Comet Carl"}}"""))
        line.handle(json.decodeFromString<JsonObject>("""{"type":"user","kind":"add","id":"c2","username":"~bus"}"""))
        val rows = (line.state.value as PartyState.Live).members.associateBy { it.ship }
        assertEquals("Comet Carl", rows.getValue(COMET).name, "the comet's row is its @p, named as it asked")
        assertNull(rows.getValue("~bus").name, "no name given, none made up")
    }

    @Test
    fun theTokenNamesUs() {
        val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"sub":"$NYM","permissions":["present","message"]}""".encodeToByteArray())
        assertEquals(NYM, TrunkWire.jwtSubject("e30.$payload.sig"))
        assertNull(TrunkWire.jwtSubject("tok"), "an opaque token names nobody")
    }

    companion object {
        const val COMET = "~foppel-fitdyn-doznux-fithut--somdur-famdev-forpet-daplyd"
        const val NYM = ".renewed.erupt.prepare.ablate.outdid.demote.disburse.ensures.perfects.imbue.defames.involve"
    }
}
