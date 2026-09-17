package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryNoticedEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The tray's table: a claim is noticed once, and the person's word is kept. */
class OrreryNoticedTest {
    private lateinit var tmp: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmp = createTempDirectory(prefix = "talon-noticed-").toFile()
        db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @AfterTest
    fun tearDown() { db.close(); tmp.deleteRecursively() }

    private fun row(id: String, attr: String = "location", state: String = "pending") = OrreryNoticedEntity(
        id = id, ship = "~zod", subject = "person/bus", attr = attr, valueJson = "\"the shop\"", atMs = 1, untilMs = null, conf = 70,
        sourceKind = "talon-dm", sourceId = "talon://chat/~bus?id=$id", bodyJson = null, whom = "~bus", postId = id, snippet = "I'm at the shop", state = state, createdMs = 1,
    )

    @Test
    fun `the same claim is noticed once and the word on it sticks`() = runBlocking {
        assertEquals(1L, db.orreryNoticed().insertIfNew(row("a")))
        assertEquals(-1L, db.orreryNoticed().insertIfNew(row("a")), "a re-read message is the same row")
        assertEquals(listOf("a"), db.orreryNoticed().pending("~zod").first().map { it.id })
        OrreryRepo.discard(db, "a")
        assertEquals(emptyList(), db.orreryNoticed().pending("~zod").first())
        assertEquals(1, db.orreryNoticed().countByState("~zod", "location", "discarded"))
        assertFalse(OrreryRepo.confirm(db, "a"), "with no pipe attached, a confirm has nowhere to go and says so")
        assertEquals("discarded", db.orreryNoticed().get("a")?.state, "and changes nothing")
    }

    @Test
    fun `a noticed row is the facts it stands for`() {
        val f = OrreryRepo.factsOf(row("b").copy(bodyJson = """{"id":"person/bus","aliases":["~bus"]}"""))
        assertEquals("person/bus", f.bodies.single().id)
        val o = f.observations.single()
        assertEquals("location", o.attr)
        assertEquals("talon://chat/~bus?id=b", o.sourceId)
    }
}
