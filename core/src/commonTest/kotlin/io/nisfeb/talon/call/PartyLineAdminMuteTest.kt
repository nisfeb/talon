package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyLineAdminMuteTest {
    private class FakeLink : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0 fake-offer"
        override suspend fun answerTo(remoteSdp: String): String = "v=0 fake-answer"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun line() = PartyLine(HttpClient(), links = { _, _ -> FakeLink() })
        .also { it.markConnectingForTests("room") }
    private fun userAdd(id: String, who: String): JsonObject = json.decodeFromString(
        """{"type":"user","kind":"add","id":"$id","username":"$who"}""",
    )

    /** What arrives after Galène relays the admin's broadcast: no `target`, `username` is the admin. */
    private fun relayed(admin: String, value: String): JsonObject = json.decodeFromString(
        """{"type":"usermessage","kind":"${PartyLine.ADMIN_MUTE_KIND}",
            "source":"c1","dest":"","username":"$admin","value":"$value"}""",
    )

    private fun members(l: PartyLine) =
        (l.state.value as? PartyState.Live)?.members.orEmpty().associateBy { it.ship }

    @Test
    fun theSubjectIsMutedNotTheAdmin() = runTest {
        val l = line()
        l.handle(userAdd("c1", "~admin"))
        l.handle(userAdd("c2", "~member"))
        l.handle(relayed("~admin", "true:~member"))
        assertTrue(members(l)["~member"]!!.mutedByAdmin, "the member should be muted by the admin")
        assertFalse(members(l)["~admin"]!!.mutedByAdmin, "the admin who sent it must not be marked")
        l.handle(relayed("~admin", "false:~member"))
        assertFalse(members(l)["~member"]!!.mutedByAdmin)
    }

    @Test
    fun aLegacyPayloadNamesNobody() = runTest {
        val l = line()
        l.handle(userAdd("c1", "~admin"))
        l.handle(userAdd("c2", "~member"))
        l.handle(relayed("~admin", "true"))
        assertFalse(members(l)["~admin"]!!.mutedByAdmin)
        assertFalse(members(l)["~member"]!!.mutedByAdmin)
    }

    @Test
    fun payloadParsing() {
        assertEquals(true to "~zod", PartyLine.parseAdminMute("true:~zod", null))
        assertEquals(false to "~zod", PartyLine.parseAdminMute("false:~zod", null))
        assertEquals(true to "~nec", PartyLine.parseAdminMute("true", "~nec"))
        assertNull(PartyLine.parseAdminMute("true", null))
        assertNull(PartyLine.parseAdminMute(null, null))
    }
}
