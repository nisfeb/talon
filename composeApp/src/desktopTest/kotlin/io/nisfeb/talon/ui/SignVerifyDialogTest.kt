package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.LatticeSign
import io.nisfeb.talon.urbit.SignedRecord
import io.nisfeb.talon.urbit.armor
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Signing with the ship's key and checking a signature, against a
 * Lattice that signs, and verifies only the text it signed.
 */
@OptIn(ExperimentalTestApi::class)
class SignVerifyDialogTest {
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val signed = SignedRecord("~zod", "3", "ed25519", "lattice", "12345", "67890")

    private fun lattice(old: Boolean = false) = LatticeSign(HttpClient(MockEngine { req ->
        val body = req.body.toByteArray().decodeToString()
        asked += "${req.url.encodedPath} $body"
        when {
            old -> respond("", HttpStatusCode.NotFound)
            req.url.encodedPath.endsWith("/sign") ->
                respond("""{"ship":"~zod","life":3,"digest":"12345","sig":"67890"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else -> {
                val ok = "\"content\"" !in body || "\"content\":\"the words\"" in body
                respond(if (ok) """{"ok":true}""" else """{"ok":false,"reason":"the text is not what was signed"}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
    }), "https://ship.example")

    private fun dialog(signer: LatticeSign = lattice(), block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { SignVerifyDialog(signer, ourShip = "~zod", onDismiss = {}) } }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    private fun ComposeUiTest.check(block: String, text: String?) {
        onNodeWithText("Check").performClick()
        onNode(hasSetTextAction() and hasText("Signature block")).performTextInput(block)
        text?.let { onNode(hasSetTextAction() and hasText("The text it covers, if it was text")).performScrollTo().performTextInput(it) }
        // The dialog scrolls: its button is below what first shows.
        onAllNodesWithText("Check").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
    }

    @Test
    fun `text is signed by the ship and the block offered to copy`() = dialog {
        onNode(hasSetTextAction() and hasText("Something to sign")).performTextInput("the words")
        onNodeWithText("Sign the text").performClick()
        showing("Copy the signature")
        assertTrue(asked.single().let { it.startsWith("/apps/lattice/sign") && it.endsWith("the words") }, asked.toString())
    }

    @Test
    fun `a signature checked against its own text is good`() = dialog {
        check(signed.armor(), "the words")
        showing("Signed by ~zod, and it covers that text.")
    }

    @Test
    fun `a signature checked against other text is not`() = dialog {
        check(signed.armor(), "other words")
        showing("No: the text is not what was signed")
    }

    @Test
    fun `a signature checked alone says nothing was checked against it`() = dialog {
        check(signed.armor(), null)
        showing("Nothing was checked against it.")
    }

    @Test
    fun `something that is not a signature is said to be so, and nothing is asked`() = dialog {
        check("hello", null)
        showing("That is not a signature block.")
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a Lattice too old to sign says so`() = dialog(lattice(old = true)) {
        onNode(hasSetTextAction() and hasText("Something to sign")).performTextInput("the words")
        onNodeWithText("Sign the text").performClick()
        showing("too old to sign")
    }
}
