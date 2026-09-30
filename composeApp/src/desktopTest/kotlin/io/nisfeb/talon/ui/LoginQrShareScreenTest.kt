package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.login.TalonLoginUri
import io.nisfeb.talon.ui.screens.LoginQrShareScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A login handed to another device: a QR and a link, only once both halves are there. */
@OptIn(ExperimentalTestApi::class)
class LoginQrShareScreenTest {
    private val copied = mutableListOf<String>()

    private fun share(initialUrl: String = "", block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { copied += annotatedString.text }
                },
            ) {
                TalonTheme(darkTheme = false) { LoginQrShareScreen(onBack = {}, initialUrl = initialUrl) }
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `nothing is offered until both the ship and the code are in`() = share(initialUrl = "https://zod.test") {
        assertTrue(shows("Fill in both fields to see a QR.") && !shows("Copy talon:// URI"))
        onNode(hasSetTextAction() and hasText("+code")).performTextInput("lidlut-tabwed")
        waitUntil(timeoutMillis = 5_000) { shows("Copy talon:// URI") }
        assertTrue(!shows("Fill in both fields"))
    }

    @Test
    fun `the link copied carries the ship and code, and a new edit clears the note`() = share {
        onNode(hasSetTextAction() and hasText("Ship URL")).performTextInput(" https://zod.test ")
        onNode(hasSetTextAction() and hasText("+code")).performTextInput("lidlut-tabwed")
        onNodeWithText("Copy talon:// URI").performClick()
        assertEquals(TalonLoginUri.Payload("https://zod.test", "lidlut-tabwed"), TalonLoginUri.decode(copied.single()))
        waitUntil(timeoutMillis = 5_000) { shows("Copied talon:// URI to clipboard") }
        onNode(hasSetTextAction() and hasText("+code")).performTextReplacement("sampel-sampel")
        waitUntil(timeoutMillis = 5_000) { !shows("Copied talon:// URI to clipboard") }
    }

    @Test
    fun `more than a QR can hold says so`() = share(initialUrl = "https://zod.test") {
        onNode(hasSetTextAction() and hasText("+code")).performTextInput("x".repeat(4_000))
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't generate QR") }
    }
}
