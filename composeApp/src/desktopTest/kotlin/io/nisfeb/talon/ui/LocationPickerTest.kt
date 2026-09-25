package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.LocationPicker
import io.nisfeb.talon.ui.theme.TalonTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the dial thinks you are: coordinates taken as typed without
 * asking anyone, a name looked up and one of its places picked, the
 * device asked where it can be, and each way it can fail said plainly.
 */
@OptIn(ExperimentalTestApi::class)
class LocationPickerTest {
    private val picked = CopyOnWriteArrayList<HomePlace>()
    private val looked = CopyOnWriteArrayList<String>()

    private val boston = HomePlace(42.36, -71.06, "Boston, MA")
    private val lincs = HomePlace(52.98, -0.03, "Boston, Lincolnshire")

    private fun picker(
        current: HomePlace? = null,
        device: (suspend () -> Result<HomePlace>)? = null,
        lookup: PlaceLookup? = { q -> looked += q; Result.success(listOf(boston, lincs)) },
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                LocationPicker(current = current, onUseDevice = device, lookup = lookup, onPick = { picked += it }, onDismiss = {})
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.type(text: String, button: String = "Search") {
        onNode(hasSetTextAction()).performTextInput(text)
        onNodeWithText(button).performClick()
    }

    @Test
    fun `coordinates are taken as typed, and nobody is asked`() = picker {
        type("42.36, -71.06")
        assertEquals(42.36 to -71.06, picked.single().lat to picked.single().lon)
        assertTrue(looked.isEmpty(), "a lookup would send the place somewhere")
    }

    @Test
    fun `a name is looked up, and one of the places it could be is picked`() = picker {
        type("Boston")
        waitUntil(timeoutMillis = 5_000) { shows("Boston, Lincolnshire") }
        onNodeWithText("Boston, Lincolnshire").performClick()
        assertEquals(listOf(lincs), picked.toList())
        assertEquals(listOf("Boston"), looked.toList())
    }

    @Test
    fun `a name found nowhere, or a lookup that fails, says so`() {
        picker(lookup = { Result.success(emptyList()) }) {
            type("Atlantis")
            waitUntil(timeoutMillis = 5_000) { shows("Nowhere by that name.") }
        }
        picker(lookup = { Result.failure(IllegalStateException("offline")) }) {
            type("Boston")
            waitUntil(timeoutMillis = 5_000) { shows("Could not look that up: offline") }
        }
    }

    @Test
    fun `without a lookup only coordinates will do`() = picker(lookup = null) {
        assertTrue(shows("Latitude, longitude"))
        type("Boston", button = "Set")
        waitUntil(timeoutMillis = 5_000) { shows("Enter coordinates as latitude, longitude.") }
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `the device is asked where it is, and a refusal says what to do instead`() {
        picker(device = { Result.success(boston.copy(fromGps = true)) }) {
            onNodeWithText("Use this device's location").performClick()
            waitUntil(timeoutMillis = 5_000) { picked.isNotEmpty() }
            assertTrue(picked.single().fromGps)
        }
        picker(device = { Result.failure(SecurityException("denied")) }) {
            onNodeWithText("Use this device's location").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Type a place instead.") }
        }
    }

    @Test
    fun `where it is now is said, and where the device has no say it offers nothing`() = picker(current = boston.copy(fromGps = true)) {
        assertTrue(shows("Now: Boston, MA (from this device)"))
        assertTrue(!shows("Use this device's location"))
    }
}
