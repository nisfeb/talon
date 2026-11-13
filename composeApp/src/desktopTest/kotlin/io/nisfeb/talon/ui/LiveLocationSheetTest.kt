package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.GROUP_SHARE_MAX
import io.nisfeb.talon.orrery.LocalLiveLocation
import io.nisfeb.talon.orrery.OrreryApi
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live location from a person's card (orrery 88), the whole way to a
 * ship: Share my location, its choices and the exact request; the share
 * shown, and Stop; theirs shown with Open map; and what a refusal or no
 * answer says. The repo is the app's own, against a fake ship.
 */
@OptIn(ExperimentalTestApi::class)
class LiveLocationSheetTest {
    private val bus = "~bus"
    private val now get() = System.currentTimeMillis()
    private fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()
    private val fileKey = OrreryApi.SCRY_ROOT.removePrefix("/~/scry/") + "/location.json"

    /** The location document the fake orrery holds: shares change it, as the real one's routes do. */
    /** The shares out, by ship, as orrery's grants. */
    private val outs: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var out: String
        get() = outs.values.joinToString(",", "[", "]")
        set(v) {
            outs.clear()
            kotlinx.serialization.json.Json.parseToJsonElement(v).jsonArray.forEach { outs[it.jsonObject["ship"]!!.jsonPrimitive.content] = it.toString() }
        }
    @Volatile private var inbound = "{}"
    @Volatile private var asks = "{}"
    private val doc get() = """{"out": $out, "in": $inbound, "asks": $asks}"""
    private val live: MutableList<Long?> = java.util.concurrent.CopyOnWriteArrayList()
    private val maps: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** The ship's orrery, as GET /api/version answers. */
    @Volatile private var orreryVersion = 141

    /** Whether the ship holds a Mapbox token, as GET /api/travel's token_set says. */
    @Volatile private var mapbox = false

    private fun ship(onShare: (String) -> Unit = {}) = FakeShip("~zod").apply {
        answerApi = { method, path, body ->
            when {
                path == "/apps/orrery/api/state" -> "{}"
                path == "/apps/orrery/api/version" -> """{"version":$orreryVersion}"""
                method == "GET" && path == "/apps/orrery/api/travel" -> """{"enabled":false,"token_set":$mapbox}"""
                method == "GET" && path == "/apps/orrery/api/location" -> doc
                method == "POST" && path == "/apps/orrery/api/location/share" -> {
                    onShare(body)
                    val asked = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                    val to = asked["ship"]!!.jsonPrimitive.content
                    val hours = asked["hours"]?.jsonPrimitive?.content?.toLong()
                    val always = asked["always"]?.jsonPrimitive?.content == "true"
                    val until = hours?.let { "\"${iso(now + it * 3_600_000)}\"" } ?: "null"
                    val exact = asked["precision"]?.jsonPrimitive?.content == "exact"
                    // "auto": the ship works it out, here half an hour and five minutes off.
                    val by = asked["by"]?.jsonPrimitive?.content?.let { if (it == "auto") iso(now + 35 * 60_000) else it }?.let { "\"$it\"" } ?: "null"
                    outs[to] = """{"ship": "$to", "until": $until, "home": ${hours == null && !always}, "always": $always, "exact": $exact, "since": "${iso(now)}", "by": $by}"""
                    """{"ship": "$to"}"""
                }
                // Orrery 137: accept moves the ask's fix in, decline drops it.
                method == "POST" && path == "/apps/orrery/api/location/accept" -> {
                    asks = "{}"
                    inbound = """{"~bus": {"id": "person/bus", "name": "~bus", "lat": "1", "lon": "2", "at": "${iso(now)}", "until": null, "home": true}}"""
                    """{"ok": true, "id": "person/bus"}"""
                }
                method == "POST" && path == "/apps/orrery/api/location/decline" -> { asks = "{}"; """{"ok": true}""" }
                method == "DELETE" && path.startsWith("/apps/orrery/api/location/share/") -> {
                    outs.remove(path.substringAfterLast('/'))
                    """{"ok": true, "told": true}"""
                }
                else -> null
            }
        }
    }

    private fun card(
        ship: FakeShip = ship(),
        canShare: Boolean = true,
        /** A group's members: its Info's rows instead of a person's card. */
        group: List<String>? = null,
        block: ComposeUiTest.(FakeShip, OrreryRepo, (Boolean) -> Unit) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-live-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val control = object : LocationControl {
            override fun stop() = Unit
            override fun pause(paused: Boolean) = Unit
            override fun live(untilMs: Long?) { live += untilMs }
        }
        val repo = OrreryRepo(ship.http, scope, db, "test", bareClient = ship.http, location = control, canShareLocation = canShare)
            .apply { attach("https://ship.test", "~zod") }
        try {
            runBlocking { withTimeout(5_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(20) } }
            runComposeUiTest {
                var shown by mutableStateOf(true)
                setContent {
                    CompositionLocalProvider(
                        LocalLiveLocation provides repo.live,
                        LocalMapsLauncher provides MapsLauncher { lat, lng -> maps += "$lat,$lng" },
                    ) {
                        TalonTheme(darkTheme = false) {
                            // As the shells have it, over whatever page shows.
                            ShareBackOffer()
                            if (group != null) androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.Text("Group Info")
                                if (shown) GroupLiveLocationRows(group, us = "~zod")
                            } else if (shown) ContactProfileSheet(
                                ship = bus, self = false, contact = null,
                                onMessage = {}, onEditSelf = {}, onDismiss = { shown = false },
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows(if (group != null) "Group Info" else "Message") }
                block(ship, repo) { shown = it }
            }
        } finally {
            // Every query the repo started is over before the database
            // closes: closed under one, SQLite's native side crashed the
            // test JVM later, in a finalizer (CI, 2026-11-01).
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            io.nisfeb.talon.urbit.closeAfter(db)
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.tap(text: String) {
        waitUntil(timeoutMillis = 5_000) { shows(text) }
        val node = onNodeWithText(text)
        // The card scrolls; a dialog and the group's rows have nothing to scroll.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun `Share my location asks the ship for what was chosen, and the card says it runs`() = card { ship, _, _ ->
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Share where you are with ~bus") }
        onNodeWithText("Until I'm home").performClick()
        onNodeWithText("Exact").performClick()
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("You're sharing where you are") }
        assertEquals(
            listOf("""POST /apps/orrery/api/location/share {"ship":"~bus","home":true,"precision":"area"}"""),
            ship.api.filter { "/location/share" in it },
        )
        assertTrue(shows("until you're home · area only"))
        assertTrue(!shows("Share my location"), "one share per ship: Stop, not a second share")
        // Until home: the phone sends a fix a minute, and reads the shares again in a quarter hour.
        val until = live.lastOrNull()
        assertTrue(until != null && until in (now + 14 * 60_000)..(now + 16 * 60_000), "fast until $until")
    }

    @Test
    fun `Stop asks the ship to stop, and Share my location is back`() {
        out = """[{"ship": "~bus", "until": "${iso(now + 3_600_000)}", "home": false, "exact": true, "since": "${iso(now)}"}]"""
        card { ship, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("You're sharing where you are") }
            assertTrue(shows("· exact"))
            tap("Stop")
            waitUntil(timeoutMillis = 5_000) { shows("Share my location") }
            assertEquals(listOf("DELETE /apps/orrery/api/location/share/~bus "), ship.api.filter { it.startsWith("DELETE") })
            assertTrue(!shows("You're sharing where you are"))
            assertEquals(null, live.last(), "the phone stops sending fast")
        }
    }

    @Test
    fun `theirs shows who, how fresh and until when, and Open map opens where they are`() {
        inbound = """{"~bus": {"name": "Bus", "lat": "40.6782", "lon": "-73.9442", "acc": "12", "at": "${iso(now - 3 * 60_000)}", "until": null, "home": true}}"""
        card { _, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("~bus is sharing where they are") }
            assertTrue(shows("Updated 3m ago · until they're home"))
            tap("Open map")
            assertEquals(listOf("40.6782,-73.9442"), maps)
        }
    }

    // The scry costs the ship no event: no request reaches orrery for it.
    @Test
    fun `the shares are read by scry where the ship allows it`() {
        inbound = """{"~bus": {"name": "Bus", "lat": "1", "lon": "2", "at": "${iso(now)}", "until": "${iso(now + 3_600_000)}", "home": false}}"""
        val ship = ship().apply { scries[fileKey] = doc }
        inbound = "{}"
        card(ship) { s, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("~bus is sharing where they are") }
            assertTrue(s.api.none { it.startsWith("GET /apps/orrery/api/location") }, s.api.toString())
            assertTrue(fileKey in s.scried)
        }
    }

    @Test
    fun `where this device sends no fixes, theirs still shows but nothing offers to share`() {
        inbound = """{"~bus": {"name": "Bus", "lat": "1", "lon": "2", "at": "${iso(now)}", "until": "${iso(now + 3_600_000)}", "home": false}}"""
        card(canShare = false) { _, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("~bus is sharing where they are") }
            assertTrue(!shows("Share my location"))
        }
    }

    @Test
    fun `a refusal says the ship's reason, and nothing is shown as shared`() = card(ship().apply {
        answerApiStatus = { method, path, body ->
            if (method == "POST" && path.endsWith("/location/share") && "\"home\":true" in body) {
                HttpStatusCode.BadRequest to """{"error": "home: this ship does not know where home is; give hours too"}"""
            } else null
        }
    }) { _, _, _ ->
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        onNodeWithText("Until I'm home").performClick()
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't share: home: this ship does not know where home is; give hours too") }
        // The dialog closed on the tap: the reason is on the card, by Share, to pick hours instead.
        assertTrue(!shows("Share where you are with ~bus"))
        assertTrue(shows("Share my location"), "offered again")
        assertTrue(!shows("You're sharing where you are"))
        assertTrue(live.isEmpty() || live.last() == null, "no fast sending for a share the ship refused")
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("For 8 hours") }
        onNodeWithText("For 8 hours").performClick()
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("You're sharing where you are") && !shows("Sending to your Groundwire node") }
        assertTrue(!shows("Couldn't share"), "asked again, the old reason goes")
    }

    // A check-in (orrery 142): until home, by a time, or the ship tells them.
    @Test
    fun `an orrery before 142 is not offered a check-in, which it would ignore`() = card { ship, _, _ ->
        waitUntil(timeoutMillis = 5_000) { ship.api.any { "/api/version" in it } }
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        onNodeWithText("Until I'm home").performClick()
        waitForIdle()
        assertTrue(!shows("Check in"))
    }

    @Test
    fun `Check in sends a time to be home by, and the card says it`() = card(ship().also { orreryVersion = 142 }) { ship, _, _ ->
        waitUntil(timeoutMillis = 5_000) { ship.api.any { "/api/version" in it } }
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        assertTrue(!shows("Check in"), "only for until home")
        onNodeWithText("Until I'm home").performClick()
        tap("Check in")
        waitUntil(timeoutMillis = 5_000) { shows("even if your phone is off") && shows("Home by") }
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.api.any { "/location/share" in it } }
        val body = ship.api.single { "/location/share" in it }
        val by = Regex(""""by":"([^"]+)"""").find(body)?.groupValues?.get(1)
        assertTrue(body.startsWith("""POST /apps/orrery/api/location/share {"ship":"~bus","home":true,"precision":"exact","by":"""), body)
        val byMs = kotlin.time.Instant.parse(by!!).toEpochMilliseconds()
        assertTrue(byMs > now && byMs < now + 26 * 3_600_000, "the next one: $by")
        waitUntil(timeoutMillis = 5_000) { shows("check in by") }
    }

    // orrery 143 with a Mapbox token: the ship works the time out from the drive home.
    @Test
    fun `with Mapbox on the ship, Check in works its time out from the drive home`() = card(ship().also { orreryVersion = 143; mapbox = true }) { ship, _, _ ->
        waitUntil(timeoutMillis = 5_000) { ship.api.any { "/api/travel" in it } }
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        onNodeWithText("Until I'm home").performClick()
        tap("Check in")
        waitUntil(timeoutMillis = 5_000) { shows("works it out from the drive home") }
        assertTrue(!shows("Home by"), "no clock while Automatic")
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.api.any { "/location/share" in it } }
        assertEquals("""POST /apps/orrery/api/location/share {"ship":"~bus","home":true,"precision":"exact","by":"auto"}""", ship.api.single { "/location/share" in it })
        waitUntil(timeoutMillis = 5_000) { shows("check in by") }
    }

    @Test
    fun `Set a time is there beside Automatic, and without Mapbox only a time`() {
        card(ship().also { orreryVersion = 143; mapbox = true }) { ship, _, _ ->
            waitUntil(timeoutMillis = 5_000) { ship.api.any { "/api/travel" in it } }
            tap("Share my location")
            waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
            onNodeWithText("Until I'm home").performClick()
            tap("Check in")
            tap("Set a time")
            waitUntil(timeoutMillis = 5_000) { shows("Home by") }
        }
        card(ship().also { orreryVersion = 143; mapbox = false }) { ship, _, _ ->
            waitUntil(timeoutMillis = 5_000) { ship.api.any { "/api/travel" in it } }
            tap("Share my location")
            waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
            onNodeWithText("Until I'm home").performClick()
            tap("Check in")
            waitUntil(timeoutMillis = 5_000) { shows("Home by") }
            assertTrue(!shows("Automatic"), "no token, nothing to work it out with")
        }
    }

    // The token was read once, with the version at sign-in: one added on
    // the ship after that was never seen until the next (review of #99).
    @Test
    fun `a Mapbox token added since sign-in is found when the choices open`() = card(ship().also { orreryVersion = 143; mapbox = false }) { ship, _, _ ->
        waitUntil(timeoutMillis = 5_000) { ship.api.any { "/api/travel" in it } }
        mapbox = true
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        onNodeWithText("Until I'm home").performClick()
        tap("Check in")
        waitUntil(timeoutMillis = 5_000) { shows("works it out from the drive home") }
        assertEquals(2, ship.api.count { "/api/travel" in it }, "asked again as the choices opened, and only then")
    }

    @Test
    fun `theirs, not home by their time, says so`() {
        inbound = """{"~bus": {"name": "Bus", "lat": "1", "lon": "2", "at": "${iso(now)}", "until": null, "home": true, "late": "${iso(now - 60_000)}"}}"""
        card { _, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Not home yet, meant to be by") }
        }
    }

    // sneagan, 2026-11-08: "tapping share doesn't dismiss the modal". It
    // waited for the ship's answer, and then the shares read back.
    @Test
    fun `Share closes the choices on the tap, and the card says it is being sent until the ship takes it`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        card(ship(onShare = { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) })) { ship, _, _ ->
            tap("Share my location")
            onNodeWithText("Share").performClick()
            waitUntil(timeoutMillis = 2_000) { !shows("Share where you are with ~bus") }
            waitUntil(timeoutMillis = 2_000) { shows("Sending to your Groundwire node") }
            assertTrue(shows("You're sharing where you are"), "shown at once")
            onNodeWithText("Stop").assertIsNotEnabled()
            waitUntil(timeoutMillis = 5_000) { ship.api.any { it.startsWith("POST /apps/orrery/api/location/share") } }
            gate.countDown()
            waitUntil(timeoutMillis = 5_000) { !shows("Sending to your Groundwire node") }
            assertTrue(shows("You're sharing where you are"))
            onNodeWithText("Stop").assertIsEnabled()
        }
    }

    @Test
    fun `no answer says the node did not answer`() = card(ship().apply {
        answerApiStatus = { method, path, _ ->
            if (method == "DELETE") throw java.io.IOException("connection reset") else null
        }
    }.also { out = """[{"ship": "~bus", "until": "${iso(now + 3_600_000)}", "home": false, "exact": true, "since": "${iso(now)}"}]""" }) { _, _, _ ->
        tap("Stop")
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't stop: your Groundwire node didn't answer. Try again.") }
        assertTrue(shows("You're sharing where you are"), "still shared: it was not stopped")
    }

    // Ship writes run on the repo's scope: the card closed mid-share, and
    // the share still lands and is shown wherever the shares are read.
    @Test
    fun `leaving the card while it shares still shares`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        card(ship(onShare = { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) })) { ship, repo, show ->
            tap("Share my location")
            onNodeWithText("Share").performClick()
            waitUntil(timeoutMillis = 5_000) { ship.api.any { it.startsWith("POST /apps/orrery/api/location/share") } }
            show(false)
            waitForIdle()
            gate.countDown()
            // Until the ship has taken it, not only until it shows: a share
            // shows at once, as being sent, and the phone is told to send
            // fast when the ship answers. Read a moment sooner, on a slow
            // machine, that had not happened yet (CI, 2026-11-10).
            runBlocking { withTimeout(10_000) { while (repo.live.shares.value?.outTo("~bus")?.sending != false || live.lastOrNull() == null) delay(20) } }
            assertTrue(live.lastOrNull() != null, "the phone sends fast for it")
        }
    }

    // A group's members, each their own grant (orrery keeps one per ship).
    private val crew = listOf("~zod", "~bus", "~nec")

    @Test
    fun `a group share goes to every member but us, the area unless asked, and Stop ends them all`() = card(group = crew) { ship, _, _ ->
        tap("Share my location with the group")
        waitUntil(timeoutMillis = 5_000) { shows("Share where you are with the group's 2 members") }
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("You're sharing where you are with 2 of its members") }
        assertEquals(
            listOf(
                """POST /apps/orrery/api/location/share {"ship":"~bus","hours":1,"precision":"area"}""",
                """POST /apps/orrery/api/location/share {"ship":"~nec","hours":1,"precision":"area"}""",
            ),
            ship.api.filter { "/location/share" in it },
        )
        tap("Stop")
        waitUntil(timeoutMillis = 5_000) { shows("Share my location with the group") }
        // In the order the ship lists its shares, which is its own.
        assertEquals(
            setOf("DELETE /apps/orrery/api/location/share/~bus ", "DELETE /apps/orrery/api/location/share/~nec "),
            ship.api.filter { it.startsWith("DELETE") }.toSet(),
        )
        assertEquals(2, ship.api.count { it.startsWith("DELETE") })
        assertEquals(null, live.last())
    }

    // 2026-11-10: one share with a member hid the group's offer, and a
    // group share would have replaced that member's own exact share.
    @Test
    fun `a member's own share is kept, the group is still offered, and its Stop ends only the group's`() {
        out = """[{"ship": "~bus", "until": "${iso(now + 8 * 3_600_000)}", "home": false, "exact": true}]"""
        card(group = crew) { ship, repo, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("has a share of their own, kept as it is.") }
            tap("Share my location with the group")
            waitUntil(timeoutMillis = 5_000) { shows("Share where you are with the group's 1 other member") }
            onNodeWithText("Share").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("You're sharing where you are with 1 of its members") }
            assertEquals(
                listOf("""POST /apps/orrery/api/location/share {"ship":"~nec","hours":1,"precision":"area"}"""),
                ship.api.filter { "/location/share" in it },
            )
            assertTrue(!shows("Share my location with the group"), "everyone has a share now")
            tap("Stop")
            waitUntil(timeoutMillis = 5_000) { shows("Share my location with the group") }
            assertEquals(listOf("DELETE /apps/orrery/api/location/share/~nec "), ship.api.filter { it.startsWith("DELETE") })
            assertEquals(listOf("~bus"), repo.live.shares.value?.out?.map { it.ship })
            assertTrue(shows("has a share of their own, kept as it is."))
        }
    }

    // Stopping first was the only way to a new length or precision.
    @Test
    fun `a running share is changed from the profile without stopping it`() {
        out = """[{"ship": "~bus", "until": "${iso(now + 3_600_000)}", "home": false, "exact": false}]"""
        card { ship, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("Change") }
            tap("Change")
            waitUntil(timeoutMillis = 5_000) { shows("Share where you are with") }
            onNodeWithText("Share").performClick()
            waitUntil(timeoutMillis = 5_000) { ship.api.any { "/location/share" in it } }
            assertEquals(
                listOf("""POST /apps/orrery/api/location/share {"ship":"~bus","hours":1,"precision":"exact"}"""),
                ship.api.filter { "/location/share" in it },
            )
            assertTrue(ship.api.none { it.startsWith("DELETE") }, "replaced, not stopped")
        }
    }

    @Test
    fun `a member's refusal stops the share there, says why, and the shares before it stand`() = card(group = crew, ship = ship().apply {
        answerApiStatus = { method, path, body ->
            if (method == "POST" && "~nec" in body) HttpStatusCode.Forbidden to """{"error": "no such route"}""" else null
        }
    }) { _, repo, _ ->
        tap("Share my location with the group")
        waitUntil(timeoutMillis = 5_000) { shows("Share") }
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't share: no such route") }
        assertEquals(listOf("~bus"), repo.live.shares.value?.out?.map { it.ship })
    }

    @Test
    fun `a group bigger than a family is not offered a share`() =
        card(group = listOf("~zod") + (1..GROUP_SHARE_MAX + 1).map { "~sampel-$it" }) { _, _, _ ->
            waitForIdle()
            assertTrue(!shows("Share my location with the group"))
        }

    private val anAsk get() = """{"~bus": {"lat": "1", "lon": "2", "at": "${iso(now)}", "until": null, "home": true, "asked": "${iso(now)}"}}"""

    @Test
    fun `an ask shows nothing of theirs until Accept, which makes them a person and shows where they are`() {
        asks = anAsk
        card { ship, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("~bus wants to share where they are") }
            assertTrue(!shows("is sharing where they are"), "nothing of theirs before the owner says yes")
            tap("Accept")
            waitUntil(timeoutMillis = 5_000) { shows("~bus is sharing where they are") }
            assertEquals(listOf("""POST /apps/orrery/api/location/accept {"ship":"~bus"}"""), ship.api.filter { "/accept" in it || "/decline" in it })
            assertTrue(!shows("wants to share"))
        }
    }

    @Test
    fun `Decline drops the ask`() {
        asks = anAsk
        card { ship, _, _ ->
            tap("Decline")
            waitUntil(timeoutMillis = 5_000) { !shows("wants to share") }
            assertEquals(listOf("""POST /apps/orrery/api/location/decline {"ship":"~bus"}"""), ship.api.filter { "/accept" in it || "/decline" in it })
            assertTrue(!shows("is sharing where they are"))
        }
    }

    @Test
    fun `an ask that went meanwhile says so`() {
        asks = anAsk
        card(ship().apply {
            answerApiStatus = { method, path, _ ->
                if (method == "POST" && path.endsWith("/accept")) HttpStatusCode.NotFound to """{"error": "no ask from that ship"}""" else null
            }
        }) { _, _, _ ->
            tap("Accept")
            waitUntil(timeoutMillis = 5_000) { shows("Couldn't accept: no ask from that ship") }
        }
    }

    // sneagan, 2026-11-10: "a way to share location with someone
    // indefinitely", offered only where the ship keeps one (orrery 146).
    @Test
    fun `Always shares with no end, as the ship reads it, and the card says so`() = card(ship().also { orreryVersion = 146 }) { ship, _, _ ->
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Always, until I stop") }
        tap("Always, until I stop")
        waitUntil(timeoutMillis = 5_000) { shows("when you move a few hundred metres, not each minute") }
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("always, until you stop") }
        assertEquals(listOf("""POST /apps/orrery/api/location/share {"ship":"~bus","always":true,"precision":"exact"}"""), ship.api.filter { "/location/share" in it })
    }

    @Test
    fun `an older ship is not offered Always`() = card(ship().also { orreryVersion = 145 }) { _, _, _ ->
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        assertTrue(!shows("Always, until I stop"))
    }

    private val anAlwaysAsk get() = """{"~bus": {"lat": "1", "lon": "2", "at": "${iso(now)}", "until": null, "home": false, "always": true, "asked": "${iso(now)}"}}"""

    // Offered, not required (sneagan, 2026-11-10): theirs is accepted either way.
    @Test
    fun `accepting a share with no end offers to share back, and saying no still accepts`() {
        asks = anAlwaysAsk
        card(ship().also { orreryVersion = 146 }) { ship, _, _ ->
            tap("Accept")
            waitUntil(timeoutMillis = 5_000) { shows("always shares with you. Share where you are with them too?") }
            tap("Cancel")
            waitUntil(timeoutMillis = 5_000) { !shows("Share where you are with them too?") }
            assertTrue(shows("~bus is sharing where they are"), "theirs is accepted")
            assertTrue(ship.api.none { "/location/share" in it }, "nothing of ours was shared")
        }
    }

    @Test
    fun `sharing back from the offer shares with no end, the area unless asked`() {
        asks = anAlwaysAsk
        card(ship().also { orreryVersion = 146 }) { ship, _, _ ->
            tap("Accept")
            waitUntil(timeoutMillis = 5_000) { shows("Share where you are with them too?") }
            onNodeWithText("Share").performClick()
            waitUntil(timeoutMillis = 5_000) { ship.api.any { "/location/share" in it } }
            assertEquals(listOf("""POST /apps/orrery/api/location/share {"ship":"~bus","always":true,"precision":"area"}"""), ship.api.filter { "/location/share" in it })
        }
    }

    @Test
    fun `an ask that ends is accepted with no offer`() {
        asks = anAsk
        card(ship().also { orreryVersion = 146 }) { _, _, _ ->
            tap("Accept")
            waitUntil(timeoutMillis = 5_000) { shows("~bus is sharing where they are") }
            assertTrue(!shows("Share where you are with them too?"))
        }
    }
}
