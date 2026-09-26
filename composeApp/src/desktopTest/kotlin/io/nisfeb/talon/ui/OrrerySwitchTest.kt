package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
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
 * Orrery's one switch. Off until the owner turns it on, with what it is
 * said in words for anyone; on, its settings; off again, the ship's own
 * reading and writing stop and this install's key goes back. And the
 * switch and an install that disagree are settled the switch's way.
 */
@OptIn(ExperimentalTestApi::class)
class OrrerySwitchTest {
    private val docs = Collections.synchronizedMap(mutableMapOf(
        "chat" to """{"enabled":true,"dms":[],"channels":[],"send_dms":true}""",
        "mail" to """{"enabled":true}""",
        "generator" to """{"enabled":true}""",
    ))
    private val writes: MutableList<Pair<String, JsonObject>> = CopyOnWriteArrayList()
    private val revoked: MutableList<String> = CopyOnWriteArrayList()

    private val http = HttpClient(MockEngine { req ->
        val doc = req.url.encodedPath.substringAfter("/apps/orrery/api/", "")
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        when {
            doc.startsWith("clients/") && req.method == HttpMethod.Delete -> {
                revoked += doc.substringAfter("clients/")
                json("{}")
            }
            doc in docs.keys && req.method == HttpMethod.Put -> {
                val sent = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                writes += doc to sent
                docs[doc] = JsonObject(Json.parseToJsonElement(docs.getValue(doc)).jsonObject + sent).toString()
                json(docs.getValue(doc))
            }
            doc in docs.keys -> json(docs.getValue(doc))
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
        val sent = writes.associate { (doc, body) -> doc to body.toString() }
        assertTrue("\"enabled\":false" in sent["chat"].orEmpty() && "\"send_dms\":false" in sent["chat"].orEmpty(), sent.toString())
        assertTrue("\"enabled\":false" in sent["mail"].orEmpty(), sent.toString())
        assertTrue("\"enabled\":false" in sent["generator"].orEmpty(), sent.toString())
        assertEquals(listOf("c1"), revoked.toList())
        assertNull(runBlocking { db.orreryAccounts().get("~zod") }, "this install holds no key")
        waitUntil(timeoutMillis = 5_000) { !shows("Orrery triage") }
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
