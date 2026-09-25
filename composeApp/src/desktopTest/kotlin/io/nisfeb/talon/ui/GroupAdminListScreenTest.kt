package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.GroupAdminListScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The groups this ship runs: listed from the ship with their size,
 * opened, a new one made, and a load that fails said to have failed.
 */
@OptIn(ExperimentalTestApi::class)
class GroupAdminListScreenTest {
    private val opened: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun group(title: String, members: String) =
        """{"meta":{"title":"$title","description":"","image":"#223344","cover":""},"admins":["admin"],"seats":{$members},"admissions":{"privacy":"private"}}"""

    private fun admin(prepare: FakeShip.() -> Unit = {}, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-adminlist-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply {
            scries["groups/v2/groups"] = """{"~zod/garden":{},"~nec/book-club":{},"~bus/elsewhere":{}}"""
            scries["groups/v2/groups/~zod/garden"] = group("The Garden", """"~zod":{"roles":[]},"~nec":{"roles":[]},"~bus":{"roles":[]}""")
            scries["groups/v2/groups/~nec/book-club"] = group("Book Club", """"~zod":{"roles":["admin"]}""")
            scries["groups/v2/groups/~bus/elsewhere"] = group("Elsewhere", """"~zod":{"roles":[]},"~bus":{"roles":["admin"]}""")
        }.apply(prepare)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { GroupAdminListScreen(repo = repo, onBack = {}, onOpenGroup = { opened += it }) } }
                block(ship)
            }
        } finally {
            runBlocking { repo.stopAndJoinForTest() }
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    @Test
    fun `the groups we host or administer are listed with their size, and open`() = admin {
        showing("The Garden")
        assertTrue(shows("3 members") && shows("Book Club") && shows("1 member"))
        assertTrue(!shows("Elsewhere"), "a group we only belong to is not ours to run")
        onNodeWithText("Book Club").performClick()
        assertEquals(listOf("~nec/book-club"), opened.toList())
    }

    @Test
    fun `with nothing to run, it says so`() = admin(prepare = { scries["groups/v2/groups"] = "{}" }) {
        showing("You're not an admin of any groups.")
    }

    @Test
    fun `a list the ship will not give says it failed, rather than spinning`() = admin(prepare = { scries.remove("groups/v2/groups") }) {
        showing("Couldn't load groups")
    }

    @Test
    fun `a new group is made with its title, then opened`() = admin(prepare = {
        answerApi = { method, path, _ -> if (method == "PUT" && path.startsWith("/spider/groups/group-create-thread/")) "{}" else null }
    }) { ship ->
        showing("The Garden")
        onNodeWithContentDescription("New group").performClick()
        onNodeWithText("Create").assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Title")).performTextInput("Seed Swap")
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }
        assertTrue(opened.single().startsWith("~zod/v"), opened.toString())
        val made = ship.api.single { it.startsWith("PUT /spider/") }
        assertTrue("\"title\":\"Seed Swap\"" in made && "\"title\":\"General\"" in made, made)
    }

    @Test
    fun `a group the ship will not make says why, and the dialog stays`() = admin { _ ->
        showing("The Garden")
        onNodeWithContentDescription("New group").performClick()
        onNode(hasSetTextAction() and hasText("Title")).performTextInput("Seed Swap")
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("HTTP 404") }
        assertTrue(shows("New group") && opened.isEmpty())
    }
}
