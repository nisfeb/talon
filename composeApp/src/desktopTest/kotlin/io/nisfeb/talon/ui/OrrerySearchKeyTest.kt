package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.orrery.OrreryApi
import io.nisfeb.talon.orrery.OrreryError
import io.nisfeb.talon.orrery.SearchSettings
import io.nisfeb.talon.ui.screens.OrrerySearchKeyRow
import io.nisfeb.talon.ui.screens.searchProblem
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * sneagan, 2026-10-07 (through orrery-0c): "release 87 and ask talon for
 * the button". The assistant's Brave Search key handed to orrery for its
 * place lookups, owner only, deliberately.
 */
@OptIn(ExperimentalTestApi::class)
class OrrerySearchKeyTest {
    private val sent = CopyOnWriteArrayList<String>()

    /** The ship as orrery 87 answers it; the bare client is never to be asked. */
    private fun api(status: HttpStatusCode = HttpStatusCode.OK, held: () -> String) = OrreryApi(
        HttpClient(MockEngine { req ->
            val body = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            sent += "${req.method.value} ${req.url.encodedPath}" + (body?.let { " $it" } ?: "")
            respond(if (status == HttpStatusCode.OK) held() else "owner only", status, headersOf("Content-Type", "application/json"))
        }),
        HttpClient(MockEngine { sent += "BARE ${it.url.encodedPath}"; respond("", HttpStatusCode.Forbidden) }),
        "https://ship.test",
    )

    @Test
    fun `the key goes to orrery with the owner's login, and off keeps it`() = runBlocking {
        var held = """{"api_key_set":false,"api_url":"https://api.search.brave.com","monthly_cap":500,"enabled":false}"""
        val a = api { held }
        assertEquals(SearchSettings(enabled = false, keySet = false, monthlyCap = 500), a.searchSettings())
        held = """{"api_key_set":true,"api_url":"https://api.search.brave.com","monthly_cap":500,"enabled":true}"""
        assertEquals(SearchSettings(enabled = true, keySet = true, monthlyCap = 500), a.setSearch(true, " BSA-test-key "))
        a.setSearch(false)
        assertEquals(
            listOf(
                "GET /apps/orrery/api/search",
                """PUT /apps/orrery/api/search {"enabled":true,"api_key":"BSA-test-key"}""",
                """PUT /apps/orrery/api/search {"enabled":false}""",
            ),
            sent.toList(),
            "owner only, and turning off sends no key so the stored one stays",
        )
    }

    @Test
    fun `refusals say what they mean`() {
        assertTrue("version 87" in searchProblem(OrreryError.Refused(404, "not found")))
        assertTrue("owner" in searchProblem(OrreryError.Refused(403, "owner only")))
        assertEquals("Your ship did not answer.", searchProblem(OrreryError.Unreachable(RuntimeException())))
    }

    @Test
    fun `the button turns lookups on, then off, and says when there is no key`() = runComposeUiTest {
        var held = SearchSettings(enabled = false, keySet = false, monthlyCap = 300)
        val calls = mutableListOf<Pair<Boolean, String?>>()
        var key by androidx.compose.runtime.mutableStateOf("BSA-test-key")
        setContent {
            TalonTheme(darkTheme = false) {
                OrrerySearchKeyRow(
                    key = key,
                    read = { Result.success(held) },
                    set = { on, k -> calls += on to k; held = SearchSettings(on, keySet = true, monthlyCap = 300); Result.success(held) },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("at most 300 places", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Use this key for orrery's place lookups").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Orrery's place lookups are on.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Turn off orrery's place lookups").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Use this key for orrery's place lookups").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf<Pair<Boolean, String?>>(true to "BSA-test-key", false to null), calls)
        key = ""
        waitForIdle()
        onNodeWithText("Use this key for orrery's place lookups").assertIsNotEnabled()
        assertTrue(onAllNodesWithText("Save a Brave Search key above first.").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `an older orrery says so`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                OrrerySearchKeyRow(key = "BSA-test-key", read = { Result.failure(OrreryError.Refused(404, "nf")) }, set = { _, _ -> error("not called") })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("older than version 87", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Use this key for orrery's place lookups").assertIsNotEnabled()
    }
}
