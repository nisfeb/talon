package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Naming is reader-side: what you see for a ship is your preference
 * applied to your contact data, never the sender's. These pin the
 * precedence and the cache-invalidation signal that makes a preference
 * change actually re-render already-parsed messages.
 */
class ShipNamingTest {

    private val COMET = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
    private val NYM = "..admire...attune"

    private val nicked = ContactEntity(
        ship = "~litzod", nickname = "Maya", bio = null, avatarUrl = null,
    )
    /** A comet, which is the only thing that gets a nym. */
    private val bare = ContactEntity(
        ship = COMET, nickname = null, bio = null, avatarUrl = null,
    )

    private fun map(alwaysPatp: Boolean) = ContactMap(
        contacts = listOf(nicked, bare),
        alwaysPatp = alwaysPatp,
    )

    @Test
    fun nicknameWinsWhenPresent() {
        assertEquals("Maya", map(alwaysPatp = false).displayName("~litzod"))
    }

    @Test
    fun starsHaveNoMnemonymAndKeepTheirPatp() {
        // Only a comet's @p spells a key, so a star stays a ~ship.
        val m = ContactMap(contacts = listOf(ContactEntity("~timzod", null, null, null)))
        assertEquals("~timzod", m.displayName("~timzod"))
    }

    @Test
    fun planetsHaveNoMnemonymAndKeepTheirPatp() {
        val m = ContactMap(
            contacts = listOf(ContactEntity("~sampel-palnet", null, null, null)),
        )
        assertEquals("~sampel-palnet", m.displayName("~sampel-palnet"))
    }

    @Test
    fun mnemonymFillsInForShipsWithoutNicknames() {
        val shown = map(alwaysPatp = false).displayName(COMET)
        assertNotEquals(COMET, shown)
        assertEquals(NYM, shown)
    }

    @Test
    fun alwaysPatpOverridesEverything() {
        val m = map(alwaysPatp = true)
        assertEquals("~litzod", m.displayName("~litzod"))
        assertEquals(COMET, m.displayName(COMET))
    }

    @Test
    fun namesVersionTracksWhatDisplayNameDependsOn() {
        val base = map(alwaysPatp = false)
        // The switch changes what you see...
        assertNotEquals(base.namesVersion, map(alwaysPatp = true).namesVersion)
        // ...as does editing a nickname...
        val renamed = ContactMap(contacts = listOf(nicked.copy(nickname = "Maya R"), bare))
        assertNotEquals(base.namesVersion, renamed.namesVersion)
        // ...but an avatar or colour change must not, or every message
        // in the cache would re-render for nothing.
        val recolored = ContactMap(
            contacts = listOf(nicked.copy(color = "#ff0000", avatarUrl = "x"), bare),
        )
        assertEquals(base.namesVersion, recolored.namesVersion)
    }
}
