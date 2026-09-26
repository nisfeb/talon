package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.switches
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.settleOrreryGate
import io.nisfeb.talon.ui.screens.OrrerySettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orrery's page. Its one switch: off until the owner turns it on, with
 * what it is said in words for anyone; on, its settings; off again, the
 * ship's own reading and writing stop and this install's key goes back.
 * The switch and an install that disagree are settled the switch's way.
 * And the owner's style and standing preferences, which every orrery
 * prompt reads, kept on the ship.
 */
@OptIn(ExperimentalTestApi::class)
class OrrerySwitchTest {
    private val docs = Collections.synchronizedMap(mutableMapOf(
        "chat" to """{"enabled":true,"dms":[],"channels":[],"send_dms":true}""",
        "mail" to """{"enabled":true}""",
        "generator" to """{"enabled":true}""",
        "preferences" to """{"style":"Short and plain.","preferences":["No calls before 9am","Plain words"]}""",
    ))
    /** Documents read, in order. */
    private val reads: MutableList<String> = CopyOnWriteArrayList()
    /** An orrery older than preferences: the route is not there. */
    @Volatile private var noPreferences = false
    private val writes: MutableList<Pair<String, JsonObject>> = CopyOnWriteArrayList()
    private val revoked: MutableList<String> = CopyOnWriteArrayList()
    /** Settings the ship will not change. */
    @Volatile private var refused = setOf<String>()
    /** What the ship says to a key given back. */
    @Volatile private var revoke = HttpStatusCode.OK
    /** No Orrery on the ship: everything under it is 404. */
    @Volatile private var missing = false
    /** How long the ship takes over anything: a busy one. */
    @Volatile private var holdMs = 0L

    private val http = HttpClient(MockEngine { req ->
        val doc = req.url.encodedPath.substringAfter("/apps/orrery/api/", "")
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        if (holdMs > 0) kotlinx.coroutines.delay(holdMs)
        when {
            missing -> respond("", HttpStatusCode.NotFound)
            doc.startsWith("clients/") && req.method == HttpMethod.Delete -> {
                if (revoke != HttpStatusCode.OK) return@MockEngine respond("", revoke)
                revoked += doc.substringAfter("clients/")
                json("{}")
            }
            doc == "preferences" && noPreferences -> respond("", HttpStatusCode.NotFound)
            doc in refused && req.method == HttpMethod.Put -> respond("""{"error":"$doc: refused"}""", HttpStatusCode.BadRequest)
            doc in docs.keys && req.method == HttpMethod.Put -> {
                val sent = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                writes += doc to sent
                docs[doc] = JsonObject(Json.parseToJsonElement(docs.getValue(doc)).jsonObject + sent).toString()
                json(docs.getValue(doc))
            }
            doc in docs.keys -> json(docs.getValue(doc)).also { reads += doc }
            doc == "actions" -> json("""[{"id":"a1","kind":"reply","title":"Answer Susan","status":"proposed"}]""")
            else -> json("{}")
        }
    })

    private fun db(): Pair<AppDatabase, File> = createTempDirectory(prefix = "talon-orrery-switch-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build() to dir
    }

    private fun page(orrery: Boolean?, block: ComposeUiTest.(FakeAiSettings, OrreryRepo, AppDatabase) -> Unit) {
        val (db, dir) = db()
        runBlocking { db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "c1.secret")) }
        val scope = CoroutineScope(SupervisorJob())
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        val ai = FakeAiSettings().apply {
            if (orrery != null) applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = AiProfile(orrery = orrery)))
        }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { OrrerySettingsSection(ai, repo) }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { repo.availability.value == OrreryAvailability.PRESENT }
                waitForIdle()
                block(ai, repo, db)
            }
        } finally {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `off until turned on, what it is is said, and none of it shows`() = page(orrery = null) { _, _, _ ->
        assertTrue(shows("a private notebook about your life"))
        assertTrue(shows("Turning it off stops all of it"))
        onAllNodes(isToggleable())[0].assertIsOff()
        assertTrue(!shows("Orrery triage"), "no settings while it is off")
        assertTrue("preferences" !in reads, "nor are the owner's preferences asked for")
    }

    @Test
    fun `turned on, its settings show, and the switch goes to every device`() = page(orrery = null) { ai, _, _ ->
        onAllNodes(isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Orrery triage") }
        assertEquals(true, ai.state.value.savedProfile?.orrery)
        assertEquals("true", ai.state.value.savedProfile!!.switches()["orrery"].toString(), "it travels with the switches")
    }

    @Test
    fun `turned off, it asks, stops the ship's own work, and gives this device's key back`() = page(orrery = true) { ai, _, db ->
        onAllNodes(isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Turn Orrery off?") }
        onNodeWithText("Keep it on").performClick()
        waitForIdle()
        assertEquals(true, ai.state.value.savedProfile?.orrery, "kept")

        onAllNodes(isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Turn Orrery off?") }
        onNodeWithText("Turn off").performClick()
        waitUntil(timeoutMillis = 10_000) { ai.state.value.savedProfile?.orrery == false }
        // Exactly these: orrery merges a partial body into what it has, so
        // nothing else it holds (the chats it reads, the model) is touched.
        assertEquals(
            listOf("chat" to """{"enabled":false,"send_dms":false}""", "mail" to """{"enabled":false}""", "generator" to """{"enabled":false}"""),
            writes.map { (doc, body) -> doc to body.toString() },
        )
        assertEquals(listOf("c1"), revoked.toList())
        assertNull(runBlocking { db.orreryAccounts().get("~zod") }, "this install holds no key")
        waitUntil(timeoutMillis = 5_000) { !shows("Orrery triage") }
    }

    @Test
    fun `a reader the ship does not stop is said, and Orrery is off here anyway`() = page(orrery = true) { ai, _, db ->
        refused = setOf("chat")
        onAllNodes(isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Turn Orrery off?") }
        onNodeWithText("Turn off").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("did not confirm it stopped reading your chats") }
        assertEquals(false, ai.state.value.savedProfile?.orrery)
        assertEquals(listOf("c1"), revoked.toList(), "the key goes back all the same")
        assertNull(runBlocking { db.orreryAccounts().get("~zod") })
    }

    private fun sent(doc: String) = writes.filter { it.first == doc }.map { it.second.toString() }

    private fun ComposeUiTest.field(label: String) = onNode(hasSetTextAction() and hasText(label))
    private fun ComposeUiTest.hasField(label: String) = onAllNodes(hasSetTextAction() and hasText(label)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.add(text: String) {
        field("Add a preference").performTextInput(text)
        onNodeWithText("Add").performClick()
    }

    @Test
    fun `on, the owner's style and preferences show, and each change sends only itself`() = page(orrery = true) { _, _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("No calls before 9am") && shows("Plain words") }
        add("Never book Mondays")
        waitUntil(timeoutMillis = 5_000) { shows("Never book Mondays") }
        onNodeWithContentDescription("Remove \"Plain words\"").performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("Plain words") }
        field("How you like things written").performTextClearance()
        field("How you like things written").performTextInput("Warm, and brief.")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { sent("preferences").size == 3 }
        // What orrery's parser takes: the list whole, the style alone.
        assertEquals(
            listOf(
                """{"preferences":["No calls before 9am","Plain words","Never book Mondays"]}""",
                """{"preferences":["No calls before 9am","Never book Mondays"]}""",
                """{"style":"Warm, and brief."}""",
            ),
            sent("preferences"),
        )
    }

    @Test
    fun `a change is made to the list the ship holds now, not the one the page first read`() = page(orrery = true) { _, _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Plain words") }
        // Another device takes one out meanwhile.
        docs["preferences"] = """{"style":"Short and plain.","preferences":["No calls before 9am"]}"""
        add("Never book Mondays")
        waitUntil(timeoutMillis = 5_000) { sent("preferences").isNotEmpty() }
        assertEquals(listOf("""{"preferences":["No calls before 9am","Never book Mondays"]}"""), sent("preferences"), "not put back")
    }

    @Test
    fun `an Orrery that does not keep preferences says so and offers nothing to write over them`() {
        noPreferences = true
        page(orrery = true) { _, _, _ ->
            waitUntil(timeoutMillis = 5_000) { shows("An older Orrery does not keep them") }
            assertTrue(!hasField("Add a preference") && !hasField("How you like things written"), "no box to write an empty list back from")
        }
    }

    @Test
    fun `a preference the ship refuses is said, and not shown as kept`() = page(orrery = true) { _, _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Plain words") }
        refused = setOf("preferences")
        add("Never book Mondays")
        waitUntil(timeoutMillis = 5_000) { shows("Your ship refused it: preferences: refused") }
        assertEquals(1, onAllNodesWithText("Never book Mondays").fetchSemanticsNodes().size, "only in the box, still to send")
    }

    @Test
    fun `leaving the page while a preference is sent still sends it, and the answer is what shows`() = attached { repo, _ ->
        holdMs = 300
        val page = CoroutineScope(SupervisorJob())
        page.launch { repo.changePreferences { it + "Never book Mondays" } }
        kotlinx.coroutines.delay(100)
        page.coroutineContext.job.cancelAndJoin()
        kotlinx.coroutines.withTimeout(10_000) { while (repo.preferences.value?.list?.contains("Never book Mondays") != true) kotlinx.coroutines.delay(20) }
        assertEquals(listOf("""{"preferences":["No calls before 9am","Plain words","Never book Mondays"]}"""), sent("preferences"))
    }

    /** A repo attached to the ship with this install's key, as a device that has fed Orrery is. */
    private fun attached(block: suspend (OrreryRepo, AppDatabase) -> Unit) = runBlocking {
        val (db, dir) = db()
        val scope = CoroutineScope(SupervisorJob())
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "c1.secret"))
            repo.attach("https://ship.test", "~zod")
            kotlinx.coroutines.withTimeout(5_000) { while (repo.availability.value == OrreryAvailability.UNKNOWN) kotlinx.coroutines.delay(20) }
            block(repo, db)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a key the ship does not take back is kept, and given back at the next start`() = attached { repo, db ->
        revoke = HttpStatusCode.InternalServerError
        assertEquals(true, repo.enabled.value, "fed from here until now")
        var off = false
        assertEquals(listOf("taking back this device's key"), repo.switchOff { off = true })
        assertTrue(off, "off here all the same")
        assertEquals(false, repo.enabled.value, "and not fed from here")
        assertTrue(db.orreryAccounts().get("~zod") != null, "kept, to give back later")
        // The next start: the switch is off and a key is held.
        revoke = HttpStatusCode.OK
        settleOrreryGate(false, db, FakeAiSettings(), repo, "https://ship.test", "~zod")
        assertEquals(listOf("c1"), revoked.toList())
        assertNull(db.orreryAccounts().get("~zod"))
    }

    @Test
    fun `off, what Orrery proposed leaves the tray and its notifications go`() = attached { repo, _ ->
        val cleared = CopyOnWriteArrayList<Set<String>>()
        repo.onActions = { _, clear -> cleared += clear }
        repo.refreshWaiting()
        assertTrue(repo.loadPreferences())
        assertEquals(listOf("a1"), repo.actions.value.map { it.id })
        repo.detach()
        assertTrue(repo.actions.value.isEmpty())
        assertNull(repo.preferences.value, "nor this ship's preferences, for the next ship's page")
        assertEquals(setOf("a1"), cleared.last(), "the notification for it is taken back")
        // Nothing shown, nothing to take back.
        cleared.clear()
        repo.detach()
        assertTrue(cleared.isEmpty(), cleared.toString())
    }

    @Test
    fun `a key the ship no longer has counts as given back`() = attached { repo, db ->
        revoke = HttpStatusCode.NotFound
        assertEquals(emptyList(), repo.switchOff())
        assertNull(db.orreryAccounts().get("~zod"))
    }

    @Test
    fun `with no Orrery on the ship, turning off asks nothing of it and still lets go`() {
        missing = true
        attached { repo, db ->
            assertEquals(OrreryAvailability.MISSING, repo.availability.value)
            var off = false
            assertEquals(emptyList(), repo.switchOff { off = true })
            assertTrue(off)
            assertTrue(writes.isEmpty(), writes.toString())
            assertNull(db.orreryAccounts().get("~zod"))
        }
    }

    @Test
    fun `leaving the page while it turns off still turns it off`() = attached { repo, db ->
        holdMs = 300
        val off = java.util.concurrent.atomic.AtomicBoolean(false)
        val page = CoroutineScope(SupervisorJob())
        page.launch { repo.switchOff { off.set(true) } }
        kotlinx.coroutines.delay(100)
        page.coroutineContext.job.cancelAndJoin()
        kotlinx.coroutines.withTimeout(10_000) { while (!off.get()) kotlinx.coroutines.delay(20) }
        assertEquals(listOf("chat", "mail", "generator"), writes.map { it.first })
        assertEquals(listOf("c1"), revoked.toList())
        assertNull(db.orreryAccounts().get("~zod"))
    }

    @Test
    fun `an install and the switch that disagree are settled the switch's way`() = runBlocking {
        val (db, dir) = db()
        val scope = CoroutineScope(SupervisorJob())
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http)
        try {
            // Never set, and this install fed Orrery before the switch: kept on.
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "c1.secret"))
            val ai = FakeAiSettings()
            settleOrreryGate(null, db, ai, repo, "https://ship.test", "~zod")
            assertEquals(true, ai.state.value.savedProfile?.orrery)

            // Turned off on another device: this one's key goes back.
            settleOrreryGate(false, db, ai, repo, "https://ship.test", "~zod")
            assertEquals(listOf("c1"), revoked.toList())
            assertNull(db.orreryAccounts().get("~zod"))

            // Never used: off stays off, and nothing on the ship is touched.
            val fresh = FakeAiSettings()
            settleOrreryGate(null, db, fresh, repo, "https://ship.test", "~zod")
            assertNull(fresh.state.value.savedProfile?.orrery)
            assertTrue(writes.isEmpty(), "a switch off by default stops nothing on the ship")
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }
}
