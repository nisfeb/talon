package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.login.TalonLoginUri
import io.nisfeb.talon.ui.screens.LoginScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.DesktopSessionStore
import io.nisfeb.talon.urbit.UrbitSession
import java.io.File
import java.net.ConnectException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in with a ship URL and +code, and what each failure says. */
@OptIn(ExperimentalTestApi::class)
class LoginScreenTest {
    private val store = DesktopSessionStore(File(createTempDirectory("talon-login-").toFile(), "sessions.json"))

    /** Each login asked for, as URL and form body. */
    private val asked: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    private val ship = "~zod"
    private val signsIn: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond("", HttpStatusCode.NoContent, headersOf("Set-Cookie", "urbauth-$ship=0v7.abc; Path=/; Max-Age=604800"))
    }

    private fun login(
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = signsIn,
        notice: String? = null,
        qr: TalonLoginUri.Payload? = null,
        onRunLocalShip: (() -> Unit)? = null,
        opened: MutableList<String> = mutableListOf(),
        block: ComposeUiTest.(loggedIn: List<String>) -> Unit,
    ) = runComposeUiTest {
        val http = HttpClient(MockEngine { req ->
            asked += req.url.toString() to req.body.toByteArray().decodeToString()
            answer(req)
        })
        val loggedIn = java.util.concurrent.CopyOnWriteArrayList<String>()
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                TalonTheme(darkTheme = false) {
                    LoginScreen(
                        session = UrbitSession(http, store),
                        onLoggedIn = { loggedIn += it },
                        notice = notice,
                        qrScanIntegration = qr?.let { payload -> { onResult -> { onResult(payload) } } },
                        onRunLocalShip = onRunLocalShip,
                    )
                }
            }
        }
        block(loggedIn)
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.connect(url: String, code: String) {
        onNodeWithText("Ship URL").performTextInput(url)
        onNodeWithText("+code").performTextInput(code)
        onNodeWithText("Connect").performClick()
    }

    @Test
    fun `a right code signs in as the ship the cookie names, and keeps the session`() = login { loggedIn ->
        connect("zod.example.com/", " +lidlut-tabwed ")
        waitUntil(timeoutMillis = 5_000) { loggedIn.isNotEmpty() }
        assertEquals(listOf("~zod"), loggedIn)
        val (url, form) = asked.single()
        assertEquals("https://zod.example.com/~/login", url, "a bare host gets https")
        assertEquals("password=lidlut-tabwed", form, "trimmed, without the +")
        assertTrue(shows("Connected as ~zod"))
        assertEquals("~zod" to "https://zod.example.com", store.active()?.let { it.ship to it.shipUrl })
    }

    @Test
    // What eyre answers to a wrong +code: 400, with its login page.
    fun `a wrong code says so and leaves the form to try again`() = login(answer = { respond("<html/>", HttpStatusCode.BadRequest) }) { loggedIn ->
        connect("https://zod.example.com", "wrong")
        waitUntil(timeoutMillis = 5_000) { shows("Wrong +code") }
        assertTrue(loggedIn.isEmpty())
        assertNull(store.active())
        onNodeWithText("Connect").assertIsEnabled()
    }

    @Test
    fun `a ship that answers without a session cookie is not a ship`() = login(answer = { respond("<html/>", HttpStatusCode.OK) }) { loggedIn ->
        connect("https://example.com", "x")
        waitUntil(timeoutMillis = 5_000) { shows("no ship signed you in there") }
        assertTrue(loggedIn.isEmpty())
    }

    @Test
    fun `a ship that is not running says so`() = login(answer = { throw ConnectException("refused") }) { loggedIn ->
        connect("http://localhost:1", "x")
        waitUntil(timeoutMillis = 5_000) { shows("Connection refused") }
        assertTrue(loggedIn.isEmpty())
    }

    @Test
    fun `a scanned QR fills the form, and Connect uses it`() =
        login(qr = TalonLoginUri.Payload("https://bus.example.com", "sampel-code")) { loggedIn ->
            onNodeWithText("Scan QR").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("QR scanned") }
            onNodeWithText("Connect").performClick()
            waitUntil(timeoutMillis = 5_000) { loggedIn.isNotEmpty() }
            assertEquals("https://bus.example.com/~/login" to "password=sampel-code", asked.single())
        }

    @Test
    fun `no scanner, no Scan button, and no local ship, no offer to run one`() = login { _ ->
        assertTrue(!shows("Scan QR") && !shows("Run one on this computer"))
    }

    @Test
    fun `why we are back at login is shown, and the other ways in work`() {
        var ranLocal = 0
        val opened = mutableListOf<String>()
        login(notice = "Your session on ~zod expired", onRunLocalShip = { ranLocal++ }, opened = opened) { _ ->
            assertTrue(shows("Your session on ~zod expired"))
            onNodeWithText("No ship? Run one on this computer (beta)").performClick()
            // The link is one span of a centred sentence: click its own glyphs.
            val link = onNodeWithText("Get one", substring = true)
            val layout = mutableListOf<TextLayoutResult>().also { link.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(it) }.single()
            val at = layout.getBoundingBox(layout.layoutInput.text.indexOf("Get one")).center
            link.performTouchInput { click(at) }
            waitForIdle()
        }
        assertEquals(1, ranLocal)
        assertTrue(opened.single().startsWith("https://urbit.org/"), opened.toString())
    }
}
