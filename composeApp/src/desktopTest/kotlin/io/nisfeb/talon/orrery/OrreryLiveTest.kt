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
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.upsertAllWithMedia
import io.nisfeb.talon.util.createAppHttpClient
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
                ))

                val repo = OrreryRepo(owner, scope, db, "live test")
                repo.attach(url, ship)
                withTimeout(30_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(200) }

                repo.enable().getOrThrow()
                val row = assertNotNull(db.orreryAccounts().get(ship), "the key is kept")
                assertTrue(row.token.startsWith(row.clientId + "."), "the token names its key")
                withTimeout(60_000) { while (repo.lastPushMs.value == null) delay(200) }
                assertEquals(null, repo.error.value, "the pass reported nothing refused")

                val body = owner.get("$url/apps/orrery/api/body/${personId(peer)}").bodyAsText()
                val view = Json.parseToJsonElement(body).jsonObject
                assertTrue("last-contact" in body, "the ship holds the contact: $body")
                assertTrue("on the road" in body, "and the status line: $body")
                assertTrue("Sampel" in body, "with the nickname as an alias: $body")
                println("OrreryLiveTest: ship view keys ${view.keys}")

                val token = row.token
                repo.disable().getOrThrow()
                assertEquals(null, db.orreryAccounts().get(ship), "the row is gone")
                delay(1_500)
                val dead = assertFailsWith<OrreryError.Refused> {
                    OrreryApi(owner, createAppHttpClient(), url).observe(
                        batches(Facts(observations = listOf(messageFacts(MessageEntity(whom = peer, id = "1", author = peer, sentMs = nowMs(), contentJson = "[]", kind = "chat"), ship)!!))).single(),
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
