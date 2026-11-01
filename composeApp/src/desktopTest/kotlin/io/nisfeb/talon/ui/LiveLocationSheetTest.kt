package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.LocalLiveLocation
import io.nisfeb.talon.orrery.OrreryApi
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    @Volatile private var out = "[]"
    @Volatile private var inbound = "{}"
    private val doc get() = """{"out": $out, "in": $inbound}"""
    private val live: MutableList<Long?> = java.util.concurrent.CopyOnWriteArrayList()
    private val maps: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun ship(onShare: (String) -> Unit = {}) = FakeShip("~zod").apply {
        answerApi = { method, path, body ->
            when {
                path == "/apps/orrery/api/state" -> "{}"
                method == "GET" && path == "/apps/orrery/api/location" -> doc
                method == "POST" && path == "/apps/orrery/api/location/share" -> {
                    onShare(body)
                    out = """[{"ship": "~bus", "until": null, "home": true, "exact": false, "since": "${iso(now)}"}]"""
                    """{"ship": "~bus"}"""
                }
                method == "DELETE" && path == "/apps/orrery/api/location/share/~bus" -> { out = "[]"; """{"ok": true, "told": true}""" }
                else -> null
            }
        }
    }

    private fun card(
        ship: FakeShip = ship(),
        canShare: Boolean = true,
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
                            if (shown) ContactProfileSheet(
                                ship = bus, self = false, contact = null,
                                onMessage = {}, onEditSelf = {}, onDismiss = { shown = false },
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("Message") }
                block(ship, repo) { shown = it }
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.tap(text: String) {
        waitUntil(timeoutMillis = 5_000) { shows(text) }
        onNodeWithText(text).performScrollTo().performClick()
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
        answerApiStatus = { method, path, _ ->
            if (method == "POST" && path.endsWith("/location/share")) {
                HttpStatusCode.BadRequest to """{"error": "home: this ship does not know where home is; give hours too"}"""
            } else null
        }
    }) { _, _, _ ->
        tap("Share my location")
        waitUntil(timeoutMillis = 5_000) { shows("Until I'm home") }
        onNodeWithText("Until I'm home").performClick()
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't share: home: this ship does not know where home is; give hours too") }
        assertTrue(shows("Share where you are with ~bus"), "the choices stay, to pick hours instead")
        assertTrue(!shows("You're sharing where you are"))
        assertTrue(live.isEmpty() || live.last() == null, "no fast sending for a share the ship refused")
    }

    @Test
    fun `no answer says the ship did not answer`() = card(ship().apply {
        answerApiStatus = { method, path, _ ->
            if (method == "DELETE") throw java.io.IOException("connection reset") else null
        }
    }.also { out = """[{"ship": "~bus", "until": "${iso(now + 3_600_000)}", "home": false, "exact": true, "since": "${iso(now)}"}]""" }) { _, _, _ ->
        tap("Stop")
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't stop: your ship didn't answer. Try again.") }
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
            runBlocking { withTimeout(5_000) { while (repo.live.shares.value?.outTo("~bus") == null) delay(20) } }
            assertTrue(live.lastOrNull() != null, "the phone sends fast for it")
        }
    }
}
