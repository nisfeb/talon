package io.nisfeb.talon.ui

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `defaults are empty when file does not exist`() {
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        val s = store.state.value
        assertNull(s.lastSeenDigestDate)
        assertEquals(0L, s.lastSeenStatusesMs)
        assertEquals("", s.lastSeenInvitesSnapshot)
        assertFalse(file.exists())
    }

    @Test
    fun `markDigestSeen persists and survives reload`() {
        DesktopMenuSeenStore(ship = "~test", file = file)
            .markDigestSeen("2026-05-02")
        assertTrue(file.exists())

        val reloaded = DesktopMenuSeenStore(ship = "~test", file = file)
        assertEquals("2026-05-02", reloaded.state.value.lastSeenDigestDate)
    }

    @Test
    fun `markStatusesSeenAt persists and survives reload`() {
        DesktopMenuSeenStore(ship = "~test", file = file)
            .markStatusesSeenAt(1_700_000_000_000L)

        val reloaded = DesktopMenuSeenStore(ship = "~test", file = file)
        assertEquals(1_700_000_000_000L, reloaded.state.value.lastSeenStatusesMs)
    }

    @Test
    fun `markInvitesSeen persists and survives reload`() {
        DesktopMenuSeenStore(ship = "~test", file = file)
            .markInvitesSeen("~zod,~nec")

        val reloaded = DesktopMenuSeenStore(ship = "~test", file = file)
        assertEquals("~zod,~nec", reloaded.state.value.lastSeenInvitesSnapshot)
    }

    @Test
    fun `markDigestSeen with null clears the saved date`() {
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        store.markDigestSeen("2026-05-02")
        store.markDigestSeen(null)

        val reloaded = DesktopMenuSeenStore(ship = "~test", file = file)
        assertNull(reloaded.state.value.lastSeenDigestDate)
    }

    @Test
    fun `the three markers are independent and accumulate in one file`() {
        // All three writes hit the same per-ship JSON; one mark must
        // not stomp the others. Reload reads them all back.
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        store.markDigestSeen("2026-05-02")
        store.markStatusesSeenAt(1_700_000_000_000L)
        store.markInvitesSeen("~zod")

        val reloaded = DesktopMenuSeenStore(ship = "~test", file = file)
        val s = reloaded.state.value
        assertEquals("2026-05-02", s.lastSeenDigestDate)
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
        assertNull(s.lastSeenDigestDate)
        assertEquals(0L, s.lastSeenStatusesMs)
        assertEquals("", s.lastSeenInvitesSnapshot)
    }

    @Test
    fun `extra fields in JSON are ignored`() {
        // Lets a future schema version add a field without breaking
        // older clients that read the same file.
        file.writeText(
            """{"lastSeenDigestDate":"2026-05-02","futureField":42}""",
        )
        val store = DesktopMenuSeenStore(ship = "~test", file = file)
        assertEquals("2026-05-02", store.state.value.lastSeenDigestDate)
    }

    @Test
    fun `atomic move leaves no tmp file after persist`() {
        DesktopMenuSeenStore(ship = "~test", file = file)
            .markDigestSeen("2026-05-02")
        val tmp = File(tmpDir, file.name + ".tmp")
        assertFalse(tmp.exists())
    }
}
