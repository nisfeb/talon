package io.nisfeb.talon.notify

import io.nisfeb.talon.urbit.baseNotifyCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Talon's side of trunk wire 12 (sneagan, 2026-10-07: "I want trunk to do
 * as much as possible. no one should have to rely on a relay"). Shapes as
 * trunk-5f sent them and as ~tuc's debug.json answered.
 */
class Wire12Test {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // The fact trunk-5f saw on /v4 after a DM, and after the read.
    @Test
    fun the_badge_is_the_ship_s_base_notify_count() {
        assertEquals(3, baseNotifyCount(obj("""{"ship/~tuc":{"notify-count":3},"base":{"notify-count":3,"count":3}}""")))
        assertEquals(0, baseNotifyCount(obj("""{"base":{"notify-count":0}}""")))
        assertNull(baseNotifyCount(obj("""{"ship/~tuc":{"notify-count":1}}""")), "no base, no number")
    }

    @Test
    fun a_notice_is_shown_by_its_tag() {
        assertEquals(
            ShipPushMessage("Leave now", "Opti sail at 5", "cal-abc"),
            noticeOf(obj("""{"event":"notice","patp":"~zod","tag":"cal-abc","title":"Leave now","body":"Opti sail at 5","open":null}""")),
        )
        assertNull(noticeOf(obj("""{"event":"new-message","patp":"~zod","whom":"~bus"}""")))
        assertNull(noticeOf(obj("""{"event":"notice","tag":"x"}""")), "no title, nothing to show")
    }

    @Test
    fun an_iphone_says_what_it_understands() {
        assertEquals(listOf("notice", "read", "badge"), TrunkPush.iosCaps(badges = true))
        assertEquals(listOf("notice", "read"), TrunkPush.iosCaps(badges = false))
        assertEquals(
            """{"push-register":{"id":"d","platform":"ios-gateway","gateway":"https://r.test","handle":"h","secret":"s","caps":["notice","read","badge"]}}""",
            TrunkPush.registerGateway("d", "https://r.test", "h", "s", TrunkPush.iosCaps(true)).toString(),
        )
    }

    private val now = 1_791_337_459_472L
    private fun debug(devices: String, drops: String = "[]") =
        Json.parseToJsonElement("""{"wire":12,"now":$now,"devices":$devices,"drops":$drops}""")

    @Test
    fun the_status_says_when_the_ship_last_pushed_here() {
        assertEquals(
            "Last notification 2 min ago.",
            shipPushStatusLine(debug("""[{"id":"me","sent":{"at":${now - 120_000},"kind":"message"},"last":{"at":${now - 120_000},"code":200}}]"""), "me"),
        )
        assertEquals("No notification sent here yet.", shipPushStatusLine(debug("""[{"id":"me","sent":null,"last":null}]"""), "me"))
        assertEquals(
            "The last notification 5 min ago got 503 from the push service; your ship tries again.",
            shipPushStatusLine(debug("""[{"id":"me","sent":{"at":${now - 3_600_000},"kind":"message"},"last":{"at":${now - 300_000},"code":503}}]"""), "me"),
        )
        assertEquals(
            "The last notification just now got no answer from the push service; your ship tries again.",
            shipPushStatusLine(debug("""[{"id":"me","sent":null,"last":{"at":$now,"code":0}}]"""), "me"),
        )
    }

    @Test
    fun a_device_the_ship_dropped_is_said_with_why() {
        assertEquals(
            "Your ship stopped pushing here (its push service answered 410). Talon sets it up again when it next starts.",
            shipPushStatusLine(debug("[]", """[{"at":$now,"id":"me","platform":"unifiedpush","reason":"410"}]"""), "me"),
        )
        assertEquals(
            "Your ship does not have this device yet. Talon sets it up again when it next starts.",
            shipPushStatusLine(debug("[]"), "me"),
        )
        assertNull(shipPushStatusLine(null, "me"), "an older trunk has no status")
        assertNull(shipPushStatusLine(Json.parseToJsonElement("""{"wire":12}"""), "me"))
    }
}
