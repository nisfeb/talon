package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.LoopScheduler
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.ui.screens.LoopsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loops, as their screen keeps them: made with a name, a prompt and a
 * schedule, switched on and off, run on demand and deleted, and every
 * change handed to the scheduler.
 */
@OptIn(ExperimentalTestApi::class)
class LoopsScreenTest {
    private var rescheduled = 0
    private val ran = mutableListOf<Long>()
    private val scheduler = object : LoopScheduler {
        override fun reschedule() { rescheduled++ }
    }

    private fun loops(seed: suspend AppDatabase.() -> Unit = {}, block: ComposeUiTest.(AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-loops-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        runBlocking { db.seed() }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        LoopsScreen(db = db, scheduler = scheduler, onRunNow = { ran += it }, onBack = {})
                    }
                }
                waitForIdle()
                block(db)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.tap(text: String) {
        shows(text)
        val node = onAllNodesWithText(text)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private val digest = LoopEntity(name = "Morning digest", prompt = "summarise overnight", intervalMinutes = 60,
        createdAt = 1, updatedAt = 1)

    @Test
    fun `a loop is made from its name, prompt and schedule, and handed to the scheduler`() = loops { db ->
        tap("Add a loop")
        shows("New loop")
        onNode(hasSetTextAction() and hasText("Name")).performTextInput("Evening check")
        onNode(hasSetTextAction() and hasText("Prompt", substring = true)).performTextInput("any mentions today?")
        tap("Save")
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.loops().enabled() }.isNotEmpty() }
        val loop = runBlocking { db.loops().enabled() }.single()
        assertEquals("Evening check" to "any mentions today?", loop.name to loop.prompt)
        assertTrue(!loop.writesAuthorized, "a new loop only reads until trusted")
        waitUntil(timeoutMillis = 5_000) { rescheduled > 0 }
    }

    @Test
    fun `a loop is switched off from the list`() = loops(seed = { loops().upsert(digest) }) { db ->
        shows("Morning digest")
        onAllNodes(isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.loops().enabled() }.isEmpty() }
        waitUntil(timeoutMillis = 5_000) { rescheduled > 0 } // the scheduler hears of it after the write
    }

    @Test
    fun `a loop runs on demand and is deleted only once confirmed`() = loops(seed = { loops().upsert(digest) }) { db ->
        tap("Morning digest")
        tap("Run now")
        assertEquals(1, ran.size)
        tap("Delete")
        shows("Delete 'Morning digest'?")
        tap("Cancel")
        assertEquals(1, runBlocking { db.loops().enabled() }.size, "cancelled")
        tap("Delete")
        onAllNodesWithText("Delete")[1].performClick() // the dialog's own Delete
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.loops().enabled() }.isEmpty() }
    }
}
