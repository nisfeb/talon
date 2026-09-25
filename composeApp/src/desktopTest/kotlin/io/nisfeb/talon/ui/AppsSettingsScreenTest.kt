package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
import io.nisfeb.talon.mail.LocalGrubberyInstall
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.screens.AppsSettingsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ship's apps: whether each answers, the install offered where one
 * is missing and what it says after, and the ship's permits page.
 */
@OptIn(ExperimentalTestApi::class)
class AppsSettingsScreenTest {
    private val did: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var groupsHere = false
    @Volatile private var latticeHere = true
    @Volatile private var orreryHere = false

    private fun apps(
        installGroups: (suspend () -> Result<Unit>)? = { did += "install groups"; groupsHere = true; Result.success(Unit) },
        grubbery: (suspend () -> Result<Unit>)? = null,
        withOrrery: Boolean = false,
        shipUrl: String? = "https://zod.test",
        block: ComposeUiTest.() -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-apps-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val http = HttpClient(MockEngine { respond("{}", if (orreryHere) HttpStatusCode.OK else HttpStatusCode.NotFound) })
        val scope = CoroutineScope(SupervisorJob())
        val orrery = if (!withOrrery) null else OrreryRepo(http, scope, db, "test", bareClient = http).apply {
            attach("https://zod.test", "~zod")
            runBlocking { probe() }
        }
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(
                        LocalGrubberyInstall provides grubbery,
                        LocalUriHandler provides object : UriHandler {
                            override fun openUri(uri: String) { did += "open $uri" }
                        },
                    ) {
                        TalonTheme(darkTheme = false) {
                            AppsSettingsScreen(
                                mail = null, calendar = null, orrery = orrery,
                                latticeInstalled = { did += "asked lattice"; latticeHere },
                                groupsInstalled = { groupsHere },
                                onInstallGroups = installGroups,
                                onAddOrrery = { did += "add orrery"; orreryHere = true; Result.success(Unit) },
                                shipUrl = shipUrl, onBack = {},
                            )
                        }
                    }
                }
                block()
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.row(name: String, state: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(name) and hasText(state)).fetchSemanticsNodes().isNotEmpty() || (shows(name) && shows(state)) }

    @Test
    fun `each app says whether it answers, and Groups is installed where it is missing`() = apps {
        row("Lattice", "working")
        row("Groups", "not installed")
        assertTrue(shows("Chat runs on Groups, which this ship does not have."))
        onNodeWithText("Install").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Installed Groups.") }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("not installed").fetchSemanticsNodes().isEmpty() }
        assertTrue("install groups" in did && onAllNodesWithText("Install").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a failed install says why, and the app stays missing`() = apps(installGroups = { Result.failure(IllegalStateException("The ship would not fetch it.")) }) {
        row("Groups", "not installed")
        onNodeWithText("Install").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("The ship would not fetch it.") }
        assertTrue(shows("not installed"))
    }

    @Test
    fun `missing Lattice is fetched with Grubbery, and missing is only offered where it can be`() {
        latticeHere = false
        groupsHere = true
        apps(grubbery = { did += "grubbery"; latticeHere = true; Result.success(Unit) }) {
            row("Lattice", "not installed")
            onNodeWithText("Install").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Grubbery and its apps are here.") }
            assertEquals(listOf("grubbery"), did.filter { it == "grubbery" })
        }
    }

    @Test
    fun `without the Grubbery install, missing Lattice offers no dead button`() {
        latticeHere = false
        groupsHere = true
        apps {
            row("Lattice", "not installed")
            assertTrue(onAllNodesWithText("Install").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun `missing Orrery is added to the shell, then says where its settings live`() {
        groupsHere = true
        apps(withOrrery = true) {
            row("Orrery", "not installed")
            onNodeWithText("Install").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Added Orrery to your Grubbery shell.") }
            waitUntil(timeoutMillis = 5_000) { shows("Feed Orrery from Settings, under AI.") }
            assertTrue("add orrery" in did)
        }
    }

    @Test
    fun `permits open on the ship, and Check again asks each app afresh`() = apps {
        row("Lattice", "working")
        onNodeWithText("Open permits on your ship").performClick()
        assertTrue("open https://zod.test/apps/grubbery/permits" in did, did.toString())
        val asked = did.count { it == "asked lattice" }
        onNodeWithText("Check again").performClick()
        waitUntil(timeoutMillis = 5_000) { did.count { it == "asked lattice" } > asked }
    }

    @Test
    fun `signed out, the permits page is only mentioned`() = apps(shipUrl = null) {
        waitUntil(timeoutMillis = 5_000) { shows("Sign in to a ship to reach its permits page.") }
        assertTrue(!shows("Open permits on your ship"))
    }
}
