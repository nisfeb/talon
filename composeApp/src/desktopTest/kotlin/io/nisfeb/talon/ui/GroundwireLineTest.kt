package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.CometDomeEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** "add a manual recheck on the profile interface for comets only" */
@OptIn(ExperimentalTestApi::class)
class GroundwireLineTest {
    private val dir = createTempDirectory(prefix = "talon-gwline-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    /** What the ship's Jael answers now, as hex. */
    @Volatile private var answer = "02"
    @Volatile private var status = HttpStatusCode.OK

    private val domes = CometDomes(
        HttpClient(MockEngine {
            respond(ByteArray(answer.length / 2) { answer.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, status)
        }),
        "https://me.example",
        db,
    )

    @AfterTest
    fun close() {
        Mnemonym.onComet = null
        db.close()
        dir.deleteRecursively()
    }

    private fun comet() = Mnemonym.patpOf(Random.nextBytes(16))

    private fun line(ship: String, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { CompositionLocalProvider(LocalCometDomes provides domes) { GroundwireLine(ship) } } }
        block()
    }

    private fun ComposeUiTest.shows(t: String) = onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a comet kept as not on Groundwire says so, and Check again asks afresh`() {
        val c = comet()
        runBlocking { db.cometDomes().put(CometDomeEntity(c, "")) }
        line(c) {
            waitUntil(timeoutMillis = 5_000) { shows("Not on Groundwire") }
            answer = "09f8ceee5ac4e8c6"
            onNodeWithText("Check again").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Groundwire comet") }
        }
    }

    @Test
    fun `a ship that is not a comet has no Groundwire line`() = line("~ricsul-bilwyt") {
        waitForIdle()
        assertTrue(!shows("Groundwire") && !shows("Check again"))
    }

    @Test
    fun `where the ship's Jael cannot say, nothing is shown`() {
        status = HttpStatusCode.InternalServerError
        answer = ""
        line(comet()) {
            waitUntil(timeoutMillis = 5_000) { !shows("Asking your ship") }
            assertTrue(!shows("Groundwire") && !shows("Check again"))
        }
    }
}
