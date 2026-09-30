package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.comet.LocalShip
import io.nisfeb.talon.comet.LocalShipInfo
import io.nisfeb.talon.comet.LocalShipState
import io.nisfeb.talon.comet.RuntimeUpdate
import io.nisfeb.talon.ui.screens.LocalShipSection
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comet on this computer: what and where it is, started and stopped,
 * its login code, a runtime upgrade, and a dojo line sent.
 */
@OptIn(ExperimentalTestApi::class)
class LocalShipSectionTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val comet = "~radbes-dasdul-wicsed-mislyd--dolnul-sipnyd-labmyr-marzod"

    private inner class FakeComet(
        initial: LocalShipState,
        private val code: String? = "ridlur-figbud-sampel-palnet",
        private val update: RuntimeUpdate? = null,
        private val startFails: String? = null,
    ) : LocalShip {
        override val state = MutableStateFlow(initial)
        override val terminal = MutableStateFlow("")
        override fun pierExists() = true
        override suspend fun setup() = error("not in these tests")
        override suspend fun start(): LocalShipState.Ready {
            did += "start"
            startFails?.let { error(it) }
            return LocalShipState.Ready(comet, "http://localhost:8080", "code").also { state.value = it }
        }
        override suspend fun stop() { did += "stop"; state.value = LocalShipState.Stopped }
        override fun send(line: String) { did += "send $line"; terminal.value += "~$comet:dojo> $line\n4\n" }
        override fun describe() = LocalShipInfo(ship = comet, pierPath = "/home/me/.talon/comet", runtimeVersion = "3.4")
        override suspend fun pierBytes() = 5L * 1_048_576
        override fun keptCode() = code
        override suspend fun checkRuntimeUpdate(): RuntimeUpdate? { did += "check"; return update }
        override suspend fun upgradeRuntime(version: String) { did += "upgrade $version" }
    }

    private fun section(ship: FakeComet, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { did += "copied ${annotatedString.text}" }
                },
            ) {
                TalonTheme(darkTheme = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { LocalShipSection(ship) }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows("Local ship (beta)") }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.tap(text: String) = onNodeWithText(text).performScrollTo().performClick()

    private val running = LocalShipState.Ready(comet, "http://localhost:8080", "code")

    @Test
    fun `a running comet says what and where it is, is copied, and stops`() = section(FakeComet(running)) {
        assertTrue(shows("Running") && shows("/home/me/.talon/comet") && shows("vere 3.4"))
        waitUntil(timeoutMillis = 5_000) { shows("(5 MB)") }
        tap("Copy ship name")
        tap("Stop")
        waitUntil(timeoutMillis = 5_000) { shows("Start") }
        assertEquals(listOf("copied $comet", "stop"), did.toList())
        assertTrue(shows("The ship is not running."))
    }

    @Test
    fun `a start that fails says why`() = section(FakeComet(LocalShipState.Stopped, startFails = "port 8080 is taken")) {
        assertTrue(shows("Stopped"))
        tap("Start")
        waitUntil(timeoutMillis = 5_000) { shows("port 8080 is taken") }
    }

    @Test
    fun `a comet still booting cannot be started again`() = section(FakeComet(LocalShipState.Booting(firstBoot = true, detail = "fetching the pill"))) {
        assertTrue(shows("Booting: fetching the pill"))
        onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun `the login code is shown on asking, and copied`() = section(FakeComet(running)) {
        assertTrue(!shows("ridlur-figbud-sampel-palnet"))
        tap("Show login code")
        waitUntil(timeoutMillis = 5_000) { shows("Login code: ridlur-figbud-sampel-palnet") }
        tap("Copy")
        assertTrue("copied ridlur-figbud-sampel-palnet" in did)
        tap("Hide login code")
        waitUntil(timeoutMillis = 5_000) { !shows("ridlur-figbud-sampel-palnet") }
    }

    @Test
    fun `without a kept code it says when one is read`() = section(FakeComet(running, code = null)) {
        tap("Show login code")
        waitUntil(timeoutMillis = 5_000) { shows("No code kept yet; it is read at the first boot.") }
    }

    @Test
    fun `a newer runtime is offered and upgraded to`() = section(FakeComet(running, update = RuntimeUpdate("3.4", "3.5"))) {
        tap("Check for runtime update")
        waitUntil(timeoutMillis = 5_000) { shows("vere 3.5 is available (you have 3.4).") }
        tap("Upgrade")
        waitUntil(timeoutMillis = 5_000) { shows("Check for runtime update") }
        assertEquals(listOf("check", "upgrade 3.5"), did.toList())
    }

    @Test
    fun `an up-to-date runtime says so`() = section(FakeComet(running)) {
        tap("Check for runtime update")
        waitUntil(timeoutMillis = 5_000) { shows("The runtime is up to date.") }
    }

    @Test
    fun `a dojo line goes to the ship, and its answer shows`() = section(FakeComet(running)) {
        assertTrue(shows("Waiting for output…"))
        onNode(hasSetTextAction()).performScrollTo().performTextInput("(add 2 2)")
        tap("Send")
        waitUntil(timeoutMillis = 5_000) { shows("dojo> (add 2 2)") }
        assertEquals(listOf("send (add 2 2)"), did.toList())
    }
}
