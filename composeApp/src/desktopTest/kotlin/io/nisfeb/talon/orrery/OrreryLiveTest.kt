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
import io.nisfeb.talon.calendar.CalendarApi
import io.nisfeb.talon.calendar.EventDraft
import io.nisfeb.talon.calendar.eventBody
import io.nisfeb.talon.data.MessageEntity
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * An event moved to a different time. What the ship was told about
     * the time it used to be at has to come back off, or the fold keeps
     * it: the ship takes the latest `at` of the rows it holds, and a
     * meeting moved earlier would go on reading as the old time for
     * ever.
     */
    @Test
    fun `an event that moves takes its old time back off the ship`() = live { owner, db, scope, url, ship ->
        val cal = CalendarApi(owner, url)
        val ball = runCatching { cal.config().ball }.getOrNull()?.takeIf { it.isNotBlank() }
        if (ball == null) {
            println("OrreryLiveTest: no calendar on this ship; skipped")
            return@live
        }
        val title = "Talon live move check"
        val today = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val draft = EventDraft(name = title, date = yesterday, minuteOfDay = 13 * 60, durMin = 30)
        assertTrue(cal.poke(ball, eventBody(draft)), "the calendar took the event")
        var uid: String? = null
        try {
            val now = nowMs()
            val made = cal.window(now - 3 * 86_400_000L, now + 86_400_000L).rows.first { it.name == title }
            uid = made.id
            val was = made.l

            val repo = OrreryRepo(owner, scope, db, "live test", book = { emptySet() })
            repo.attach(url, ship)
            withTimeout(30_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(200) }
            repo.enable().getOrThrow()
            withTimeout(60_000) { while (repo.lastPushMs.value == null) delay(200) }
            val row = assertNotNull(db.orreryAccounts().get(ship))
            val api = OrreryApi(owner, createAppHttpClient(), url)
            val bodyId = assertNotNull(db.orrerySent().get(ship, "cal:${made.cal}/$uid")?.value?.substringBefore('|'))
            assertTrue(
                api.observationsOf(bodyId, row.token).any { it.attr == "started" && it.atMs == was && it.stands },
                "the ship holds the event at the time it was made",
            )

            // Three hours earlier, which is the case that matters: the
            // old row's `at` is the later one.
            assertTrue(cal.poke(ball, eventBody(draft.copy(minuteOfDay = 10 * 60), id = uid)), "the calendar moved it")
            val moved = cal.window(now - 3 * 86_400_000L, now + 86_400_000L).rows.first { it.id == uid }
            assertTrue(moved.l < was, "the calendar really moved it: ${moved.l} vs $was")
            repo.push()

            val after = api.observationsOf(bodyId, row.token)
            val old = after.filter { it.atMs == was }
            assertTrue(old.isNotEmpty(), "the old rows are still on the ship, as retracted rows: $after")
            assertTrue(old.none { it.stands }, "a row at the old time still stands: $old")
            assertTrue(
                after.any { it.attr == "started" && it.atMs == moved.l && it.stands },
                "the new time was not written: $after",
            )
            assertEquals(null, db.orrerySent().get(ship, "occ:${made.cal}/$uid/$was"), "the moved occurrence is forgotten")

            owner.delete("$url/apps/orrery/api/body/$bodyId")
            repo.disable().getOrThrow()
        } finally {
            uid?.let { cal.poke(ball, buildJsonObject { put("action", "del-event"); put("id", it) }) }
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
                db.messages().upsertAllWithMedia(db.messageMedia(), listOf(
                    MessageEntity(whom = peer, id = "170.141.184.506", author = peer, sentMs = nowMs() - 3_600_000, contentJson = "[]", kind = "chat"),
                    MessageEntity(whom = peer, id = "170.141.184.507", author = peer, sentMs = nowMs() - 1_800_000, contentJson = """[{"inline":["I'm at the shop now"]}]""", kind = "chat"),
                ))

                val repo = OrreryRepo(owner, scope, db, "live test", book = { setOf(peer) })
                repo.attach(url, ship)
                withTimeout(30_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(200) }

                repo.enable().getOrThrow()
                val row = assertNotNull(db.orreryAccounts().get(ship), "the key is kept")
                assertTrue(row.token.startsWith(row.clientId + "."), "the token names its key")
                withTimeout(60_000) { while (repo.lastPushMs.value == null) delay(200) }
                assertEquals(null, repo.error.value, "the pass reported nothing refused")
                val noticed = db.orreryNoticed().pending(ship).first()
                assertEquals(listOf("location"), noticed.map { it.attr }, "the triage read the DM against the ship's bodies: $noticed")
                assertEquals(personId(peer), noticed.single().subject)

                val body = owner.get("$url/apps/orrery/api/body/${personId(peer)}").bodyAsText()
                val view = Json.parseToJsonElement(body).jsonObject
                assertTrue("last-contact" in body, "the ship holds the contact: $body")
                assertTrue("on the road" in body, "and the status line: $body")
                assertTrue("Sampel" in body, "with the nickname as an alias: $body")
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
                        batches(Facts(observations = listOf(messageFacts(MessageEntity(whom = peer, id = "1", author = peer, sentMs = nowMs(), contentJson = "[]", kind = "chat"), ship, personId(peer))!!))).single(),
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
