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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // ─── "add a manual recheck on the profile interface for comets only" ───
    // A Groundwire Jael says ~ of a comet it has not learned of yet, and
    // that was kept for good: two attested comets wore ".." on ~foppel.

    /** What the ship's Jael answers now, as hex; changed mid-test. */
    @Volatile private var answer = "02"
    @Volatile private var holdMs = 0L

    private fun changing() = CometDomes(
        HttpClient(MockEngine { req ->
            asked += req.url.encodedPath
            if (holdMs > 0) delay(holdMs)
            respond(ByteArray(answer.length / 2) { answer.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, HttpStatusCode.OK)
        }),
        "https://me.example",
        db,
    )

    private fun dotted(c: String) = Mnemonym.display(c)!!.let { it.startsWith(".") && !it.startsWith("..") }

    @Test
    fun `a comet kept as not on Groundwire is asked again on a recheck, and gets its dot when the ship now says so`() = runBlocking<Unit> {
        val c = comet()
        db.cometDomes().put(CometDomeEntity(c, ""))
        val domes = changing()
        assertEquals("", domes.registry(c))
        assertTrue(asked.isEmpty(), "the kept answer, without asking")
        answer = "09f8ceee5ac4e8c6"
        assertEquals("gw-btc", domes.recheck(c))
        assertEquals(listOf("/_~_/=/dome/=/j/$c"), asked.toList())
        assertTrue(dotted(c))
        assertEquals("gw-btc", db.cometDomes().get(c)?.registry, "kept, for the next start")
    }

    @Test
    fun `a recheck that now says no takes the dot away`() = runBlocking<Unit> {
        val c = comet()
        db.cometDomes().put(CometDomeEntity(c, "gw-btc"))
        val domes = changing()
        eventually { dotted(c) }
        answer = "02"
        assertEquals("", domes.recheck(c))
        assertTrue(Mnemonym.display(c)!!.startsWith(".."))
    }

    @Test
    fun `a recheck finishes when the profile closes partway`() = runBlocking<Unit> {
        val c = comet()
        db.cometDomes().put(CometDomeEntity(c, ""))
        val domes = changing()
        answer = "09f8ceee5ac4e8c6"
        holdMs = 400
        val profile = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        profile.launch { domes.recheck(c) }
        delay(100)
        profile.cancel()
        eventually { dotted(c) }
    }

    @Test
    fun `a ship without Groundwire's Jael answers a recheck with can't tell, keeping what was known`() = runBlocking<Unit> {
        val c = comet()
        db.cometDomes().put(CometDomeEntity(c, ""))
        val domes = domes(status = HttpStatusCode.InternalServerError, jam = "")
        assertEquals(null, domes.recheck(c))
        assertEquals("", db.cometDomes().get(c)?.registry)
        assertTrue(!domes.canTell)
    }
}
