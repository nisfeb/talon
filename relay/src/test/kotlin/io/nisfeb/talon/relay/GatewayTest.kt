package io.nisfeb.talon.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Apple hop for an iPhone its own ship pushes to (sneagan, 2026-10-06:
 * "can iOS switch too and just use my relay for the apple requirement").
 * Its answers are the ones trunk acts on (gwbtc/trunk#1): 404/410 drop
 * the device, 401 drops it, 409 is logged, 200 is sent.
 */
class GatewayTest {
    private val path = Files.createTempFile("relay-gateway-", ".db").toFile().also { it.delete() }.absolutePath
    private val db = Db(path).also { it.migrate() }
    private val alerts = mutableListOf<Pair<String, GatewayPush>>()
    private val badges = mutableListOf<Int?>()
    private val rings = mutableListOf<Pair<String, String>>()
    private var apns = ApnsResult(200, "")
    private val gateway = Gateway(
        db,
        alert = { t, p, badge -> alerts += t to p; badges += badge; apns },
        voip = { t, p -> rings += t to p; apns },
    )

    @AfterTest fun drop() { java.io.File(path).delete() }

    private fun enrolled(token: String = "aa11|bb22"): GatewayDevice = gateway.enroll(GatewayEnroll(token)).second!!

    private fun alert(d: GatewayDevice, secret: String = d.secret, nonce: String? = null) =
        GatewayPush(d.handle, secret, "alert", patp = "~zod", whom = "~bus", postId = "~bus/1", title = "~bus", body = "hi", nonce = nonce)

    @Test
    fun `an enrolled phone's alert goes to its alert token`() {
        val d = enrolled()
        assertEquals(200, gateway.push(alert(d)))
        assertEquals("bb22", alerts.single().first)
        assertEquals("hi", alerts.single().second.body)
    }

    @Test
    fun `a ring goes to the VoIP token with the ship's own payload`() {
        val d = enrolled()
        val ring = Json.parseToJsonElement("""{"event":"ring","patp":"~zod","from":"~bus","callId":"c1"}""").jsonObject
        assertEquals(200, gateway.push(GatewayPush(d.handle, d.secret, "voip", payload = ring)))
        assertEquals("aa11", rings.single().first)
        assertEquals(ring, Json.parseToJsonElement(rings.single().second))
    }

    @Test
    fun `a wrong secret is 401 and an unknown handle 404, and neither reaches APNs`() {
        val d = enrolled()
        assertEquals(401, gateway.push(alert(d, secret = "guess")))
        assertEquals(404, gateway.push(alert(d.copy(handle = "nope"))))
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a push the phone has no token for is 409`() {
        val d = enrolled("aa11|")
        assertEquals(409, gateway.push(alert(d)))
        val v = enrolled("|bb22")
        assertEquals(409, gateway.push(GatewayPush(v.handle, v.secret, "voip", payload = Json.parseToJsonElement("{}").jsonObject)))
    }

    @Test
    fun `a token APNs calls dead is 410, so trunk drops the device`() {
        val d = enrolled()
        apns = ApnsResult(400, "BadDeviceToken")
        assertEquals(410, gateway.push(alert(d)))
        apns = ApnsResult(410, "Unregistered")
        assertEquals(410, gateway.push(alert(d)))
        apns = ApnsResult(429, "TooManyRequests")
        assertEquals(502, gateway.push(alert(d)))
        apns = ApnsResult(0, "timeout")
        assertEquals(502, gateway.push(alert(d)))
    }

    @Test
    fun `new tokens replace the old behind the same handle, only with its secret`() {
        val d = enrolled()
        assertEquals(401, gateway.enroll(GatewayEnroll("cc33|dd44", d.handle, "guess")).first)
        assertEquals(404, gateway.enroll(GatewayEnroll("cc33|dd44", "nope", d.secret)).first)
        assertEquals(200 to d, gateway.enroll(GatewayEnroll("cc33|dd44", d.handle, d.secret)))
        gateway.push(alert(d))
        assertEquals("dd44", alerts.single().first)
    }

    @Test
    fun `only hex tokens are taken, and the secret is not kept`() {
        listOf("", "|", "zz|", "aa11", "aa|bb|cc").forEach {
            assertEquals(400, gateway.enroll(GatewayEnroll(it)).first, it)
        }
        val d = enrolled()
        assertNotEquals(d.secret, db.gatewayDevice(d.handle)!!.secretSha256)
        assertTrue(d.handle.length >= 20 && d.secret.length >= 40)
        assertNotEquals(d, enrolled())
    }

    @Test
    fun `an oversized alert or ring is refused before APNs`() {
        val d = enrolled()
        assertEquals(400, gateway.push(alert(d).copy(body = "x".repeat(1001))))
        val big = Json.parseToJsonElement("""{"x":"${"y".repeat(3000)}"}""").jsonObject
        assertEquals(400, gateway.push(GatewayPush(d.handle, d.secret, "voip", payload = big)))
        assertEquals(400, gateway.push(GatewayPush(d.handle, d.secret, "sms")))
        assertTrue(alerts.isEmpty() && rings.isEmpty())
    }

    @Test
    fun `a test alert carries its nonce and no empty collapse id`() {
        val p = Json.parseToJsonElement(alertPayload("Talon", "working", "~zod", "", "", nonce = "n-1")).jsonObject
        assertEquals("push-test", p["event"]!!.jsonPrimitive.content)
        assertEquals("n-1", p["nonce"]!!.jsonPrimitive.content)
        val r = Json.parseToJsonElement(alertPayload("~bus", "re", "~zod", "~bus", "~bus/2", parent = "~bus/1")).jsonObject
        assertEquals("~bus/1", r["parent"]!!.jsonPrimitive.content)
        assertEquals("new-message", r["event"]!!.jsonPrimitive.content)
        assertNull(r["nonce"])
    }

    // The trunk review (2026-10-06) found a control character in a peer's
    // text broke the body; Push.kt escaped only quote and backslash.
    @Test
    fun `control characters a peer chose stay valid JSON`() {
        val nasty = "a\nb\r\t\u001b[31mred\u0000\"q\"\\"
        assertEquals(nasty, Json.parseToJsonElement("\"${jsonEscape(nasty)}\"").jsonPrimitive.content)
        val p = Json.parseToJsonElement(alertPayload(nasty, nasty, "~zod", "~bus", "~bus/1")).jsonObject
        assertEquals(nasty, p["aps"]!!.jsonObject["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content)
    }

    // sneagan: "add the ability for iOS users to enable badges on the app
    // icon". The app sets the true count while open; each alert adds one.
    @Test
    fun `with badges on, each alert counts one more than the app last set`() {
        val d = enrolled()
        gateway.push(alert(d))
        assertEquals(204, gateway.badge(GatewayBadge(d.handle, d.secret, 3)))
        gateway.push(alert(d))
        gateway.push(alert(d))
        gateway.push(alert(d, nonce = "n"))
        assertEquals(listOf(null, 4, 5, null), badges, "off until set; a test alert is nothing to count")
        assertEquals(204, gateway.badge(GatewayBadge(d.handle, d.secret, null)))
        gateway.push(alert(d))
        assertEquals(null, badges.last(), "off again")
        assertEquals(401, gateway.badge(GatewayBadge(d.handle, "guess", 1)))
        assertEquals(404, gateway.badge(GatewayBadge("nope", d.secret, 1)))
        assertEquals(400, gateway.badge(GatewayBadge(d.handle, d.secret, -1)))
    }

    @Test
    fun `the badge rides in the alert's aps`() {
        val p = Json.parseToJsonElement(alertPayload("~bus", "hi", "~zod", "~bus", "~bus/1", badge = 7)).jsonObject
        assertEquals("7", p["aps"]!!.jsonObject["badge"]!!.jsonPrimitive.content)
        assertEquals("~bus", p["aps"]!!.jsonObject["thread-id"]!!.jsonPrimitive.content)
        assertNull(Json.parseToJsonElement(alertPayload("~bus", "hi", "~zod", "~bus", "~bus/1")).jsonObject["aps"]!!.jsonObject["badge"])
    }

    @Test
    fun `a relay iPhone's count works the same, by device id`() {
        db.upsertDevice("dev-1", "aa|bb", Push.IOS)
        assertEquals(null, db.nextBadge(Db.DEVICES, "dev-1"))
        assertTrue(db.setBadge(Db.DEVICES, "dev-1", 0))
        assertEquals(1, db.nextBadge(Db.DEVICES, "dev-1"))
        assertEquals(2, db.nextBadge(Db.DEVICES, "dev-1"))
        assertTrue(db.setBadge(Db.DEVICES, "dev-1", null))
        assertEquals(null, db.nextBadge(Db.DEVICES, "dev-1"))
        assertEquals(false, db.setBadge(Db.DEVICES, "never-was", 1))
    }

    // Trunk cancels on every device it has; an iPhone must report a call
    // for every VoIP push, so a cancel for a ring it never got filed a
    // missed call in its Recents (trunk review nit 11, 2026-10-07).
    @Test
    fun `a cancel goes only to a phone this gateway rang`() {
        val a = enrolled()
        val b = enrolled()
        fun voip(d: GatewayDevice, event: String, id: String) = gateway.push(
            GatewayPush(d.handle, d.secret, "voip", payload = Json.parseToJsonElement("""{"event":"$event","patp":"~zod","id":"$id"}""").jsonObject),
        )
        assertEquals(200, voip(a, "ring", "c1"))
        assertEquals(200, voip(b, "ring-cancel", "c1"), "answered, but not sent")
        assertEquals(200, voip(a, "ring-cancel", "c1"))
        assertEquals(200, voip(a, "ring-cancel", "c9"))
        assertEquals(listOf("ring c1", "ring-cancel c1"), rings.map {
            val p = Json.parseToJsonElement(it.second).jsonObject
            "${p["event"]!!.jsonPrimitive.content} ${p["id"]!!.jsonPrimitive.content}"
        })
    }

    @Test
    fun `a ring is remembered for the hours an answered call can last`() {
        var t = 0L
        val r = GatewayRings(keepMs = 1_000, now = { t })
        val ring = Json.parseToJsonElement("""{"event":"ring","id":"c1"}""").jsonObject
        val cancel = Json.parseToJsonElement("""{"event":"ring-cancel","id":"c1"}""").jsonObject
        assertTrue(r.shouldSend("h", ring))
        t = 900
        assertTrue(r.shouldSend("h", cancel))
        t = 2_000
        assertEquals(false, r.shouldSend("h", cancel), "forgotten after keepMs")
    }
}

