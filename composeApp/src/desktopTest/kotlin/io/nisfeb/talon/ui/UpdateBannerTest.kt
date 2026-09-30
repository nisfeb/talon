package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.update.UpdateManifest
import io.nisfeb.talon.update.UpdateStatus
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The update banner at each step: offered with its changelog (and not
 * dismissable when mandatory), downloading with its progress and no
 * way to tap, ready to install, and a failure that is its own words.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateBannerTest {
    private val did = CopyOnWriteArrayList<String>()
    private val manifest = UpdateManifest(
        versionCode = 500, versionName = "1.9.0", url = "https://x.test/t.apk", sha256 = "ab", minSdk = 26,
        changelog = "Faster sync.", mandatory = false,
    )

    private fun banner(status: UpdateStatus, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { UpdateBanner(status, onTap = { did += "tap" }, onDismiss = { did += "dismiss" }) } }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an update on offer says what is new, taps to update, and can be put off`() = banner(UpdateStatus.Available(manifest)) {
        assertTrue(shows("Talon 1.9.0 available") && shows("Faster sync."))
        onNodeWithText("Talon 1.9.0 available").performClick()
        onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("tap", "dismiss"), did.toList())
    }

    @Test
    fun `a mandatory update cannot be put off, and one with no notes just says tap`() =
        banner(UpdateStatus.Available(manifest.copy(mandatory = true, changelog = " "))) {
            assertTrue(shows("Tap to update."))
            assertTrue(onAllNodesWithContentDescription("Dismiss").fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `a download shows how far it has got, and taps do nothing`() = banner(UpdateStatus.Downloading(manifest, 42)) {
        assertTrue(shows("Downloading 1.9.0…") && shows("42%"))
        onNodeWithText("Downloading 1.9.0…").performClick()
        assertTrue(did.isEmpty())
    }

    @Test
    fun `a downloaded update taps to install`() = banner(UpdateStatus.Ready(manifest, "/tmp/t.apk", "Android will ask to confirm.")) {
        assertTrue(shows("Android will ask to confirm."))
        onNodeWithText("Tap to install 1.9.0").performClick()
        assertEquals(listOf("tap"), did.toList())
    }

    @Test
    fun `a failure is its own words, and taps to retry`() = banner(UpdateStatus.Failed(manifest, "Couldn't verify the download.")) {
        assertTrue(shows("Couldn't verify the download.") && shows("Tap to retry."))
        onNodeWithText("Couldn't verify the download.").performClick()
        assertEquals(listOf("tap"), did.toList())
    }

    @Test
    fun `with nothing to say there is no banner`() = banner(UpdateStatus.Idle) {
        assertTrue(!shows("Talon") && !shows("Tap"))
    }
}
