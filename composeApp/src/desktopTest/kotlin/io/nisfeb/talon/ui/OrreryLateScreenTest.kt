package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.collectAsState
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.screens.OrreryActionsScreen
import io.nisfeb.talon.ui.screens.OrreryRepoScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Late section and the high mark, through the Actions page: a late
 * high one leads in red with Done, Snooze 1 h and Not high, each checked
 * by what reaches the ship; a high one elsewhere carries a badge; and an
 * orrery older than 110 shows none of it.
 */
@OptIn(ExperimentalTestApi::class)
class OrreryLateScreenTest {
    private val now = System.currentTimeMillis()
    private fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()

    private fun action(
        id: String, title: String, status: String = "approved", kind: String = "task",
        due: Long? = now - 2 * 3_600_000, payload: String = """{"priority":"high"}""",
    ) = OrreryAction(id, kind, title, Json.parseToJsonElement(payload).jsonObject, emptyList(), due?.let(::iso), status, "owner")

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private val did = CopyOnWriteArrayList<String>()

    private fun list(actions: List<OrreryAction>, marks: Boolean = true, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                OrreryActionsScreen(
                    actions = actions, onBack = {}, header = false, marks = marks,
                    onDecide = { a, status, _ -> did += "${a.id} $status" },
                    onSnooze = { did += "${it.id} snooze" },
                    onPriority = { a, high -> did += "${a.id} high=$high" },
                    onOpen = { did += "open ${it.id}" },
                )
            }
        }
        block()
    }

    @Test
    fun `late ones lead newest due first with their three buttons`() = list(listOf(
        action("old", "Renew passport", due = now - 86_400_000),
        action("a1", "Check in for DL 2627", status = "proposed"),
        action("q1", "Buy bread", payload = "{}"),
    )) {
        waitUntil(timeoutMillis = 5_000) { shows("Late · 2") }
        val top = { t: String -> onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Check in for DL 2627") < top("Renew passport"), "newest due first")
        assertTrue(top("Renew passport") < top("Buy bread"), "above the rest")
        assertTrue(shows("2 h late") || shows("2 h 1 min late"), "says how late")
        assertEquals(1, onAllNodesWithText("Check in for DL 2627").fetchSemanticsNodes().size, "in one place, not twice")
        onAllNodesWithText("Done")[0].performClick()
        onAllNodesWithText("Snooze 1 h")[0].performClick()
        onAllNodesWithText("Not high")[0].performClick()
        onNodeWithText("Check in for DL 2627").performClick()
        assertEquals(listOf("a1 done", "a1 snooze", "a1 high=false", "open a1"), did.toList())
    }

    @Test
    fun `one the ship carries out has no Done on Late`() = list(listOf(
        action("m1", "Tell Bus you are late", status = "proposed", kind = "message", payload = """{"priority":"high","via":"chat","to":"~bus","text":"late"}"""),
    )) {
        waitUntil(timeoutMillis = 5_000) { shows("Late · 1") }
        assertTrue(shows("Snooze 1 h") && shows("Not high"))
        assertTrue(onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty(), "Done would approve it, which sends it")
    }

    @Test
    fun `a high one not yet late carries a badge in its place`() = list(listOf(
        action("t1", "File the taxes", status = "proposed", due = now + 86_400_000),
        action("s1", "Call the bank", payload = """{"priority":"high","snooze_until":"${iso(now + 3_600_000)}"}"""),
    )) {
        waitUntil(timeoutMillis = 5_000) { shows("File the taxes") }
        assertTrue(!shows("Late ·"), "neither is late: one is not due, one is snoozed")
        assertEquals(2, onAllNodesWithText("high").fetchSemanticsNodes().size)
    }

    @Test
    fun `an orrery older than 110 shows none of it`() = list(listOf(action("a1", "Check in for DL 2627")), marks = false) {
        waitUntil(timeoutMillis = 5_000) { shows("Check in for DL 2627") }
        assertTrue(!shows("Late") && !shows("Snooze 1 h") && !shows("Not high") && onAllNodesWithText("high").fetchSemanticsNodes().isEmpty())
    }

    // ─── through the repo, as both shells wire it ──────────────────

    private val posts = CopyOnWriteArrayList<String>()
    @Volatile private var version = """{"version":110}"""
    @Volatile private var open = "[]"
    /** Holds the ship's answer to a mark until completed: a slow ship. */
    @Volatile private var hold: CompletableDeferred<Unit>? = null

    /** The page, the window on the first action, or nothing: what [wired]'s block switches between. */
    private enum class On { PAGE, WINDOW, NOTHING }

    private fun wired(block: ComposeUiTest.(OrreryRepo, androidx.compose.runtime.MutableState<On>) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-late-ui-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = headersOf("Content-Type", "application/json")
            when {
                path == "/apps/orrery/api/version" -> respond(version, HttpStatusCode.OK, json)
                req.method == HttpMethod.Post && path.startsWith("/apps/orrery/api/actions/") -> {
                    posts += "$path ${req.body.toByteArray().decodeToString()}"
                    hold?.await()
                    val said = when {
                        path.endsWith("/snooze") -> """{"ok":true,"snooze_until":"${iso(now + 3_600_000)}"}"""
                        path.endsWith("/priority") -> """{"ok":true,"priority":"${if ("high" in posts.last()) "high" else ""}"}"""
                        else -> """{"ok":true}"""
                    }
                    respond(said, HttpStatusCode.OK, json)
                }
                path == "/apps/orrery/api/actions" -> respond(if (req.url.parameters["status"] == "open" || req.url.parameters["status"] == null) open else "[]", HttpStatusCode.OK, json)
                path == "/apps/orrery/api/state" -> respond("{}", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val scope = CoroutineScope(SupervisorJob())
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        val on = androidx.compose.runtime.mutableStateOf(On.PAGE)
        try {
            runBlocking { withTimeout(5_000) { while (repo.availability.value != OrreryAvailability.PRESENT || repo.version.value == null) delay(20) } }
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        val actions = repo.actions.collectAsState().value
                        when (on.value) {
                            On.PAGE -> OrreryRepoScreen(repo, actions, twentyFourHour = true, onBack = {}, onOpenAction = { did += "open ${it.id}" })
                            On.WINDOW -> actions.firstOrNull()?.let { OrreryActionDialog(it, repo, onClose = { on.value = On.PAGE }) }
                            On.NOTHING -> Unit
                        }
                    }
                }
                block(repo, on)
            }
        } finally {
            runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun row(id: String, title: String, status: String, payload: String = """{"priority":"high"}""", due: Long = now - 2 * 3_600_000) =
        """{"id":"$id","kind":"task","title":"$title","payload":$payload,"due":"${iso(due)}","status":"$status"}"""

    @Test
    fun `snooze and not high and done each reach the ship and empty Late`() {
        open = "[${row("a1", "Check in for DL 2627", "proposed")},${row("a2", "Renew passport", "approved", due = now - 86_400_000)},${row("a3", "Book parking", "approved")}]"
        wired { _, _ ->
            waitUntil(timeoutMillis = 10_000) { shows("Late · 3") }
            onAllNodesWithText("Snooze 1 h")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Late · 2") }
            onAllNodesWithText("Not high")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Late · 1") }
            onNodeWithText("Done").performClick()
            waitUntil(timeoutMillis = 5_000) { !shows("Late ·") }
            waitUntil(timeoutMillis = 5_000) { posts.size >= 3 }
            assertEquals(
                listOf(
                    "/apps/orrery/api/actions/a1/snooze {\"minutes\":60}",
                    "/apps/orrery/api/actions/a3/priority {\"priority\":\"\"}",
                    "/apps/orrery/api/actions/a2 {\"status\":\"done\"}",
                ),
                posts.toList(),
            )
            assertTrue(shows("Book parking") && shows("Check in for DL 2627"), "both still listed below")
        }
    }

    @Test
    fun `an orrery older than 110 offers none of it on the page or in the window`() {
        version = """{"version":109}"""
        open = "[${row("a1", "Check in for DL 2627", "proposed")}]"
        wired { _, on ->
            waitUntil(timeoutMillis = 10_000) { shows("Check in for DL 2627") }
            assertTrue(!shows("Late") && !shows("Snooze 1 h"))
            on.value = On.WINDOW
            waitUntil(timeoutMillis = 5_000) { shows("Note for Orrery") }
            assertTrue(!shows("Mark high") && !shows("Not high"))
        }
    }

    @Test
    fun `mark high in the window reaches the ship and the page shows it late`() {
        open = "[${row("a1", "Check in for DL 2627", "proposed", payload = "{}")}]"
        wired { _, on ->
            waitUntil(timeoutMillis = 10_000) { shows("Check in for DL 2627") }
            assertTrue(!shows("Late ·"))
            on.value = On.WINDOW
            waitUntil(timeoutMillis = 5_000) { shows("Mark high") }
            onNodeWithText("Mark high").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Not high") }
            waitUntil(timeoutMillis = 5_000) { posts.isNotEmpty() }
            assertEquals("/apps/orrery/api/actions/a1/priority {\"priority\":\"high\"}", posts.single())
            on.value = On.PAGE
            waitUntil(timeoutMillis = 5_000) { shows("Late · 1") }
        }
    }

    // Ship writes run on the repo's scope: leave the page while the ship
    // is slow and the snooze still lands. Waited on real time.
    @Test
    fun `a snooze given and the page left still lands`() {
        open = "[${row("a1", "Check in for DL 2627", "approved")}]"
        hold = CompletableDeferred()
        wired { repo, on ->
            waitUntil(timeoutMillis = 10_000) { shows("Late · 1") }
            onNodeWithText("Snooze 1 h").performClick()
            waitUntil(timeoutMillis = 5_000) { posts.isNotEmpty() }
            on.value = On.NOTHING
            waitForIdle()
            hold!!.complete(Unit)
            val held = iso(now + 3_600_000)
            runBlocking {
                withTimeout(5_000) {
                    while (repo.actions.value.single().payload["snooze_until"]?.toString()?.trim('"') != held) delay(20)
                }
            }
            assertEquals(null, repo.answerProblem.value)
        }
    }
}
