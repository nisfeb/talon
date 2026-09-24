package io.nisfeb.talon.ui

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pin down the pure-logic and persistence guarantees the More-menu
 * freshness-pip flow depends on. The screen-side data-vs-seen
 * comparisons aren't tested here — they live in DmListScreen and
 * the Compose UI test scaffolding for that doesn't exist yet — but
 * the building blocks should be airtight.
 */
class MenuSeenStoreTest {

    private lateinit var tmpDir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-menuseen-test-").toFile()
        file = File(tmpDir, "menuseen-test.json")
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ── invitesSnapshot ────────────────────────────────────────

    @Test
    fun `invitesSnapshot is order-insensitive`() {
        // Two clients sorting their invite lists differently must hash
        // identically — otherwise the dot would re-fire on every reorder
        // even though the user has already seen the same set.
        val a = invitesSnapshot(listOf("~bus", "~zod", "~nec"))
        val b = invitesSnapshot(listOf("~zod", "~nec", "~bus"))
        assertEquals(a, b)
    }

    @Test
    fun `invitesSnapshot is empty for empty input`() {
        assertEquals("", invitesSnapshot(emptyList()))
    }

    @Test
    fun `invitesSnapshot dedupes duplicates`() {
        val a = invitesSnapshot(listOf("~zod", "~zod", "~nec"))
        val b = invitesSnapshot(listOf("~nec", "~zod"))
        assertEquals(a, b)
    }

    @Test
    fun `invitesSnapshot detects a real set change`() {
        val before = invitesSnapshot(listOf("~zod"))
        val after = invitesSnapshot(listOf("~zod", "~nec"))
        assertFalse(before == after)
    }

    // ── DesktopMenuSeenStore: round-trip + persistence ───────────────



    @Test
    fun `the three markers are independent and accumulate in one file`() {
        // All three writes hit the same per-ship JSON; one mark must
        // not stomp the others. Reload reads them all back.
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        store.markStatusesSeenAt(1_700_000_000_000L)
        store.markInvitesSeen("~zod")

        val reloaded = DesktopMenuSeenStore(ship = "~test", file = file)
        val s = reloaded.state.value
        assertEquals(1_700_000_000_000L, s.lastSeenStatusesMs)
        assertEquals("~zod", s.lastSeenInvitesSnapshot)
    }

    @Test
    fun `corrupt JSON falls back to default state`() {
        // A truncated JSON file (e.g. from a JVM kill mid-write) must
        // surface as the empty default rather than crashing the
        // composition that constructs the store.
        file.writeText("{ malformed")
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        val s = store.state.value
        assertEquals(0L, s.lastSeenStatusesMs)
        assertEquals("", s.lastSeenInvitesSnapshot)
    }

    @Test
    fun `extra fields in JSON are ignored`() {
        // Lets a future schema version add a field without breaking
        // older clients that read the same file.
        file.writeText(
            """{"lastSeenStatusesMs":7,"somethingNew":"x"}""",
        )
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        assertEquals(7L, store.state.value.lastSeenStatusesMs)
    }

}
