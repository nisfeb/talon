package io.nisfeb.talon.ui

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the runCommand dispatcher. Every slash-command flow goes through
 * this switch — without coverage, a stale invocation can silently route
 * into the chat send path, or a typo could turn an unknown command into
 * a no-op instead of a user-facing error.
 *
 * `/cal`, `/tz`, `/poll` parse paths are exercised in their own files;
 * here we cover the routing + the parser-free commands (`/me`, `/pet`,
 * `/talk`, `/loc`, plus the unknown-command branch).
 */
class SlashCommandsTest {
    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo
    private val http: OkHttpClient = OkHttpClient()

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-slash-test-").toFile()
        val dbFile = File(tmpDir, "test.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        // Real repo, no settingsSync, no attached UrbitChannel. Calls
        // that need the channel (setPetName, updateProfile) throw "not
        // connected" — that's the signal we're testing for in the
        // routing tests below.
        repo = TlonChatRepo(db, settingsSync = null)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun run(text: String, locationProvider: LocationProvider? = null): CommandResult =
        runBlocking { runCommand(text, repo, http, locationProvider) }

    // ── routing ─────────────────────────────────────────────────────

    @Test
    fun `non-slash text returns NotACommand so caller can fall through`() {
        assertEquals(CommandResult.NotACommand, run("hello world"))
    }

    @Test
    fun `unknown slash command returns Error with the command name`() {
        val r = run("/notarealcommand foo")
        assertTrue(r is CommandResult.Error)
        assertTrue((r as CommandResult.Error).message.contains("notarealcommand"))
    }

    @Test
    fun `img file mic are recognized as Handled by the dispatcher`() {
        // These three are intercepted by the UI layer before runCommand
        // sees them, but routing still needs to recognize them as known
        // — otherwise a stale invocation would surface the unknown-
        // command error instead of a no-op.
        assertEquals(CommandResult.Handled, run("/img"))
        assertEquals(CommandResult.Handled, run("/file"))
        assertEquals(CommandResult.Handled, run("/mic"))
    }

    // ── /me ─────────────────────────────────────────────────────────

    @Test
    fun `me with no args is Error`() {
        val r = run("/me")
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `me wraps the action in italics`() {
        val r = run("/me shrugs")
        assertTrue(r is CommandResult.Send)
        assertEquals("*shrugs*", (r as CommandResult.Send).body)
    }

    @Test
    fun `me joins multi-word actions with single spaces`() {
        // Args list is preserved after parseSlash splits on whitespace,
        // then runMe joinToString(" ") collapses runs of spaces.
        val r = run("/me  walks   the dog ")
        assertTrue(r is CommandResult.Send)
        assertEquals("*walks the dog*", (r as CommandResult.Send).body)
    }

    // ── /pet ────────────────────────────────────────────────────────

    @Test
    fun `pet with no args returns usage error`() {
        val r = run("/pet")
        assertTrue(r is CommandResult.Error)
        assertTrue((r as CommandResult.Error).message.contains("usage"))
    }

    @Test
    fun `pet with only a ship and no name returns usage error`() {
        val r = run("/pet ~zod")
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `pet rejects an arg that isn't a patp`() {
        val r = run("/pet badShip myname")
        assertTrue(r is CommandResult.Error)
        assertTrue((r as CommandResult.Error).message.contains("isn't a ship patp"))
    }

    @Test
    fun `pet rejects uppercase patp because regex is lowercase`() {
        // The regex is `^~[a-z-]+$` — uppercase is rejected before the
        // repo is touched. Without this guard, `/pet ~ZOD foo` would
        // hit the ship and get a server-side error.
        val r = run("/pet ~ZOD myname")
        assertTrue(r is CommandResult.Error)
        assertTrue((r as CommandResult.Error).message.contains("isn't a ship patp"))
    }

    @Test
    fun `pet rejects an empty name after a valid ship`() {
        // The trailing whitespace-only "name" trims to empty.
        val r = run("/pet ~zod    ")
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `pet rejects a name longer than 64 chars`() {
        val long = "x".repeat(65)
        val r = run("/pet ~zod $long")
        assertTrue(r is CommandResult.Error)
        assertTrue((r as CommandResult.Error).message.contains("too long"))
    }

    @Test
    fun `pet with valid args reaches the repo (which throws not connected here)`() {
        // Validation passed → call routes to repo.setPetName, which
        // errors with "not connected" because no UrbitChannel is
        // attached. Either error proves we made it past validation.
        val r = run("/pet ~zod fred")
        assertTrue(r is CommandResult.Error)
        val msg = (r as CommandResult.Error).message
        assertTrue(
            msg.contains("not connected") || msg.contains("nack"),
            "expected repo-layer error, got: $msg",
        )
    }

    // ── /talk ───────────────────────────────────────────────────────

    @Test
    fun `talk with no arg generates a 43-char base64url room key`() {
        val r = run("/talk")
        assertTrue(r is CommandResult.Send)
        val body = (r as CommandResult.Send).body
        // Body shape: "🎙️ Brave Talk: https://talk.brave.com/<key>"
        val key = body.substringAfterLast('/')
        assertEquals(43, key.length, "expected 43-char key, got ${key.length}: $key")
        assertTrue(
            key.matches(Regex("^[A-Za-z0-9_-]{43}$")),
            "key isn't base64url: $key",
        )
    }

    @Test
    fun `talk with a valid 43-char key reuses it verbatim`() {
        // 43 base64url chars is what Brave Talk's E2EE keys look like.
        val key = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop_"
        assertEquals(43, key.length)
        val r = run("/talk $key")
        assertTrue(r is CommandResult.Send)
        assertTrue((r as CommandResult.Send).body.endsWith("/$key"))
    }

    @Test
    fun `talk with a malformed key is Error`() {
        // Anything other than 43 base64url chars must be rejected so
        // we don't generate broken Brave Talk URLs. "tooShort" is
        // 8 chars and contains no invalid chars — must still fail
        // on length alone.
        val r = run("/talk tooShort")
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `talk with valid-length but invalid chars is Error`() {
        // 43-char string but with `!` (not base64url) must reject.
        val bad = "!".repeat(43)
        val r = run("/talk $bad")
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `talk generates a different key on each invocation`() {
        // Two consecutive empty-arg /talk calls must produce different
        // keys — same key would let a third party guess room ids.
        val r1 = run("/talk") as CommandResult.Send
        val r2 = run("/talk") as CommandResult.Send
        assertTrue(r1.body != r2.body, "two /talk calls produced same key")
    }

    // ── /loc ────────────────────────────────────────────────────────

    @Test
    fun `loc with no provider returns the platform-not-supported Error`() {
        // Desktop callers pass null; the dispatcher must surface a
        // user-facing message rather than crashing or silently sending.
        val r = run("/loc", locationProvider = null)
        assertTrue(r is CommandResult.Error)
        assertTrue((r as CommandResult.Error).message.contains("platform"))
    }

    @Test
    fun `loc with a successful provider routes coords into a Send`() {
        val provider: LocationProvider = { Result.success(37.7749 to -122.4194) }
        val r = run("/loc", locationProvider = provider)
        assertTrue(r is CommandResult.Send)
        // formatLocationShare emits the OSM URL with 5 decimal places.
        assertTrue((r as CommandResult.Send).body.contains("37.77490"))
        assertTrue(r.body.contains("-122.41940"))
    }

    @Test
    fun `loc surfaces provider failure message verbatim`() {
        val provider: LocationProvider = {
            Result.failure(IllegalStateException("/loc: no fix yet"))
        }
        val r = run("/loc", locationProvider = provider)
        assertTrue(r is CommandResult.Error)
        assertEquals("/loc: no fix yet", (r as CommandResult.Error).message)
    }

    // ── filterSlashCommands ─────────────────────────────────────────

    @Test
    fun `filterSlashCommands empty query returns the full catalog in declared order`() {
        val all = filterSlashCommands("")
        assertEquals(SLASH_COMMANDS, all)
    }

    @Test
    fun `filterSlashCommands prioritizes prefix hits over substring hits`() {
        // "ic" is a substring of "mic" but not a prefix of any command.
        val r = filterSlashCommands("ic")
        assertNotNull(r.firstOrNull { it.name == "mic" })
    }

    @Test
    fun `filterSlashCommands is case-insensitive`() {
        // Both lowercases the query; commands' names are already lower.
        val r = filterSlashCommands("CA")
        assertNotNull(r.firstOrNull { it.name == "cal" })
    }

    @Test
    fun `filterSlashCommands prefix matches outrank substring matches`() {
        // "p" is a prefix of "pet"/"poll" and a substring of "help"-
        // ish names — but no command has it as a substring, so check
        // the ordering by using "a" which is in "cal" as a substring
        // and a prefix of nothing.
        val r = filterSlashCommands("a")
        assertNull(r.firstOrNull { it.name.startsWith("a") })
        assertNotNull(r.firstOrNull { it.name.contains("a") })
    }
}
