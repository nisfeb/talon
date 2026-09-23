package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.urbit.upsertAllWithMedia
import io.nisfeb.talon.util.createAppHttpClient
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pipe against a ship that has orrery. Runs only when told where
 * and with what cookie, so CI never sees it:
 *
 *   TALON_ORRERY_URL=http://host:port TALON_ORRERY_COOKIE='<the session cookie, name=value>' TALON_ORRERY_SHIP='~ship' \
 *     ./gradlew :composeApp:desktopTest --tests '*OrreryLiveTest*'
 *
 * The same code the app runs: mint on enable, a pass from the cursors,
 * the ship's own view read back, revoke on disable, and the dead key
 * refused. Leaves the ship as it found it, bar the audit log.
 */
class OrreryLiveTest {
    private val url = System.getenv("TALON_ORRERY_URL")
    private val cookie = System.getenv("TALON_ORRERY_COOKIE")
    private val ship = System.getenv("TALON_ORRERY_SHIP")

    /** Every body the ship shows this session, by id. */
    private suspend fun bodyIds(http: io.ktor.client.HttpClient, url: String): Set<String> {
        val text = http.get("$url/apps/orrery/api/state").bodyAsText()
        return Json.parseToJsonElement(text).jsonObject["bodies"]?.let { arr ->
            (arr as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        }.orEmpty().toSet()
    }

    /** One attribute as the ship folds it: the row that wins on `at`, then on the order it saw them. */
    private suspend fun folded(http: io.ktor.client.HttpClient, url: String, bodyId: String, attr: String): String? {
        val text = http.get("$url/apps/orrery/api/body/$bodyId").bodyAsText()
        return Json.parseToJsonElement(text).jsonObject["attrs"]?.jsonObject?.get(attr)
            ?.jsonObject?.get("value")?.jsonPrimitive?.content
    }

    /** A ship, a database and a scope for one test, cleaned up after. */
    private fun live(block: suspend (io.ktor.client.HttpClient, AppDatabase, CoroutineScope, String, String) -> Unit) {
        if (url.isNullOrBlank() || cookie.isNullOrBlank() || ship.isNullOrBlank()) {
            println("OrreryLiveTest: no ship given; skipped")
            return
        }
        val tmp = createTempDirectory(prefix = "talon-orrery-live-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        val owner = createAppHttpClient().config { defaultRequest { header(HttpHeaders.Cookie, cookie) } }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking { block(owner, db, scope, url, ship) }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `the structural pipe lands on the ship and the key dies with the switch`() {
        if (url.isNullOrBlank() || cookie.isNullOrBlank() || ship.isNullOrBlank()) {
            println("OrreryLiveTest: no ship given; skipped")
            return
        }
        val tmp = createTempDirectory(prefix = "talon-orrery-live-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        val owner = createAppHttpClient().config { defaultRequest { header(HttpHeaders.Cookie, cookie) } }
        val scope = CoroutineScope(Dispatchers.Default)
        val peer = "~sampel-palnet"
        try {
            runBlocking {
                db.contacts().upsert(ContactEntity(ship = peer, nickname = "Sampel", bio = null, avatarUrl = null, status = "on the road", statusUpdatedMs = nowMs() - 60_000))

                val repo = OrreryRepo(owner, scope, db, "live test", book = { setOf(peer) })
                repo.attach(url, ship)
                withTimeout(30_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(200) }

                repo.enable().getOrThrow()
                val row = assertNotNull(db.orreryAccounts().get(ship), "the key is kept")
                assertTrue(row.token.startsWith(row.clientId + "."), "the token names its key")
                withTimeout(60_000) { while (repo.lastPushMs.value == null) delay(200) }
                assertEquals(null, repo.error.value, "the pass reported nothing refused")

                // Chats are the ship's own reader's now; the contact is
                // still this install's to tell it about.
                val body = owner.get("$url/apps/orrery/api/body/${personId(peer)}").bodyAsText()
                val view = Json.parseToJsonElement(body).jsonObject
                assertTrue("Sampel" in body, "the ship holds the contact, with the nickname as an alias: $body")
                // Tlon's status field is a social one. What it says is
                // read like any other text and waits in the tray; it is
                // never sent as a fact about the person.
                assertFalse("on the road" in body, "a status line went up as a fact: $body")
                println("OrreryLiveTest: ship view keys ${view.keys}")

                // The whole point of the sent-record: a pass that runs
                // again says nothing new and makes no second body.
                val before = bodyIds(owner, url)
                repo.push()
                val after = bodyIds(owner, url)
                assertEquals(before, after, "a replay created bodies: ${after - before}")

                val token = row.token
                repo.disable().getOrThrow()
                assertEquals(null, db.orreryAccounts().get(ship), "the row is gone")
                delay(1_500)
                val dead = assertFailsWith<OrreryError.Refused> {
                    OrreryApi(owner, createAppHttpClient(), url).observe(
                        batches(Facts(observations = listOf(Obs(personId(peer), "last-contact", JsonPrimitive("2026-09-23"), nowMs(), sourceKind = "talon-dm", sourceId = "talon://chat/$peer?id=1")))).single(),
                        token,
                    )
                }
                assertEquals(403, dead.status, "a revoked key is refused: ${dead.reason}")

                // Leave the ship as it was found.
                owner.delete("$url/apps/orrery/api/body/${personId(peer)}")
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }
}
