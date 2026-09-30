package io.nisfeb.talon.ui

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.CometDomeEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A comet's name asks the signed-in ship's Jael, once and in the
 * background, whether it is on Groundwire, and wears the single dot
 * when it is: without its profile having to be opened first.
 */
class CometDomesCheckTest {
    private val dir = createTempDirectory(prefix = "talon-domes-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val asked = CopyOnWriteArrayList<String>()

    @AfterTest
    fun close() {
        Mnemonym.onComet = null
        db.close()
        dir.deleteRecursively()
    }

    /** A comet no other test names: its @p spells sixteen random bytes. */
    private fun comet() = Mnemonym.patpOf(Random.nextBytes(16))

    /** A ship whose Jael answers every dome with [status] and [jam]. */
    private fun domes(status: HttpStatusCode = HttpStatusCode.OK, jam: String = "09f8ceee5ac4e8c6") = CometDomes(
        HttpClient(MockEngine { req ->
            asked += req.url.encodedPath
            respond(ByteArray(jam.length / 2) { jam.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, status)
        }),
        "https://me.example",
        db,
    )

    private fun eventually(what: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!what()) delay(20) } }

    @Test
    fun `a comet drawn for the first time is asked about once, and a Groundwire one gets its dot`() {
        domes()
        val c = comet()
        assertTrue(Mnemonym.display(c)!!.startsWith(".."))
        eventually { Mnemonym.display(c)!!.let { it.startsWith(".") && !it.startsWith("..") } }
        assertEquals(listOf("/_~_/=/dome/=/j/$c"), asked.toList())
        repeat(3) { Mnemonym.display(c) }
        runBlocking { delay(200) }
        assertEquals(1, asked.size, "asked once: the answer is kept")
    }

    @Test
    fun `a comet the ship has no record of keeps its two dots`() {
        domes(jam = "02")
        val c = comet()
        Mnemonym.display(c)
        eventually { asked.isNotEmpty() }
        runBlocking { delay(200) }
        assertTrue(Mnemonym.display(c)!!.startsWith(".."))
    }

    @Test
    fun `a ship without Groundwire's Jael is asked once, and never again that session`() {
        domes(status = HttpStatusCode.InternalServerError, jam = "")
        val a = comet()
        Mnemonym.display(a)
        eventually { asked.size == 1 }
        runBlocking { delay(200) }
        Mnemonym.display(comet())
        runBlocking { delay(300) }
        assertEquals(1, asked.size)
    }

    @Test
    fun `a comet already known to be on Groundwire is named so from the start, asking nothing`() {
        val c = comet()
        runBlocking { db.cometDomes().put(CometDomeEntity(c, "gw-btc")) }
        domes()
        eventually { Mnemonym.forShip(c)!!.let { it.startsWith(".") && !it.startsWith("..") } }
        assertTrue(asked.isEmpty())
    }
}
