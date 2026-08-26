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

    private val nicked = ContactEntity(
        ship = "~litzod", nickname = "Maya", bio = null, avatarUrl = null,
    )
    private val bare = ContactEntity(
        ship = "~sampel-palnet", nickname = null, bio = null, avatarUrl = null,
    )

    private fun map(mnemonym: Boolean, alwaysPatp: Boolean) = ContactMap(
        contacts = listOf(nicked, bare),
        mnemonymNames = mnemonym,
        alwaysPatp = alwaysPatp,
    )

    @Test
    fun nicknameWinsWhenPresent() {
        assertEquals("Maya", map(mnemonym = true, alwaysPatp = false).displayName("~litzod"))
    }

    @Test
    fun starsHaveNoMnemonymAndKeepTheirPatp() {
        // Mnemonyms only exist for planets and below; a star has no
        // syllable payload to name, so it stays a ~ship either way.
        val m = ContactMap(
            contacts = listOf(ContactEntity("~timzod", null, null, null)),
            mnemonymNames = true,
        )
        assertEquals("~timzod", m.displayName("~timzod"))
    }

    @Test
    fun mnemonymFillsInForShipsWithoutNicknames() {
        val shown = map(mnemonym = true, alwaysPatp = false).displayName("~sampel-palnet")
        assertNotEquals("~sampel-palnet", shown)
        assertEquals(Mnemonym.display("~sampel-palnet"), shown)
    }

    @Test
    fun mnemonymOffFallsBackToPatp() {
        val m = map(mnemonym = false, alwaysPatp = false)
        assertEquals("~sampel-palnet", m.displayName("~sampel-palnet"))
        // A nickname still wins — that switch is only about the fallback.
        assertEquals("Maya", m.displayName("~litzod"))
    }

    @Test
    fun alwaysPatpOverridesEverything() {
        val m = map(mnemonym = true, alwaysPatp = true)
        assertEquals("~litzod", m.displayName("~litzod"))
        assertEquals("~sampel-palnet", m.displayName("~sampel-palnet"))
    }

    @Test
    fun namesVersionTracksWhatDisplayNameDependsOn() {
        val base = map(mnemonym = true, alwaysPatp = false)
        // Both switches change what you see...
        assertNotEquals(base.namesVersion, map(false, false).namesVersion)
        assertNotEquals(base.namesVersion, map(true, true).namesVersion)
        // ...as does editing a nickname...
        val renamed = ContactMap(
            contacts = listOf(nicked.copy(nickname = "Maya R"), bare),
            mnemonymNames = true,
        )
        assertNotEquals(base.namesVersion, renamed.namesVersion)
        // ...but an avatar or colour change must not, or every message
        // in the cache would re-render for nothing.
        val recolored = ContactMap(
            contacts = listOf(nicked.copy(color = "#ff0000", avatarUrl = "x"), bare),
            mnemonymNames = true,
        )
        assertEquals(base.namesVersion, recolored.namesVersion)
    }
}
