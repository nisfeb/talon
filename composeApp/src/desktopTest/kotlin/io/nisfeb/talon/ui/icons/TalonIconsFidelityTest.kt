package io.nisfeb.talon.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every owned icon equals the library's original, structurally: name,
 * mirroring, viewport, and every path command. This is what lets the
 * app drop the extended library without a pixel changing.
 */
class TalonIconsFidelityTest {
    @Test
    fun `every owned icon equals its material original`() {
        val owned = TalonIcons.entries().toMap()
        assertEquals(TalonIconSources.all.map { it.first }.sorted(), owned.keys.sorted(), "the two lists name the same icons")
        for ((name, original) in TalonIconSources.all) {
            assertEquals(original, owned.getValue(name), "icon $name drifted from the library")
        }
    }
}
