package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.screens.ProfileEditScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Editing your own profile: the form starts from what is kept, Save
 * sends the edits and leaves, a refusal says so and stays; and the
 * ship's public keys are shown and copied, or it says why not.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileEditScreenTest {
    private val did = CopyOnWriteArrayList<String>()

    private val zodKeys = object : AzimuthRpc {
        override suspend fun fingerprint(ship: String) = Result.failure<AzimuthRpc.Answer>(IllegalStateException("unused"))
        override suspend fun keys(ship: String) = Result.success<AzimuthRpc.Keys?>(AzimuthRpc.Keys(auth = "0xauthkey", crypt = "0xcryptkey", suite = 1))
    }

    private fun profile(keys: AzimuthRpc = AzimuthRpc.None, prepare: FakeShip.() -> Unit = {}, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-profile-edit-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        runBlocking { db.contacts().upsertAll(listOf(ContactEntity("~zod", "Zod", "runs the place", null, status = "around"))) }
        val ship = FakeShip("~zod").apply(prepare)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(
                        LocalClipboardManager provides object : ClipboardManager {
                            override fun getText(): AnnotatedString? = null
                            override fun setText(annotatedString: AnnotatedString) { did += "copied ${annotatedString.text}" }
                        },
                    ) {
                        TalonTheme(darkTheme = false) {
                            ProfileEditScreen(db = db, repo = repo, ourPatp = "~zod", onBack = { did += "back" }, keys = keys)
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { fieldText("Nickname") == "Zod" }
                block(ship)
            }
        } finally {
            runBlocking { repo.stopAndJoinForTest() }
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.fieldText(label: String): String? =
        onAllNodes(hasSetTextAction() and hasText(label)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the form starts from what is kept, and Save sends the edits and leaves`() = profile { ship ->
        assertEquals("around" to "runs the place", fieldText("Status") to fieldText("Bio"))
        onNode(hasSetTextAction() and hasText("Nickname")).performTextReplacement("Zed")
        onNodeWithText("Save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { "back" in did }
        assertTrue(ship.pokesTo("contacts").any { "Zed" in it.json.toString() })
    }

    @Test
    fun `a save the ship refuses says so and stays`() = profile(prepare = { refuse = { if (it.app == "contacts") "not now" else null } }) {
        onNode(hasSetTextAction() and hasText("Nickname")).performTextReplacement("Zed")
        onNodeWithText("Save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("save failed") }
        assertTrue("back" !in did)
    }

    @Test
    fun `the ship's public keys are shown and copied`() = profile(keys = zodKeys) {
        waitUntil(timeoutMillis = 5_000) { shows("0xauthkey") }
        assertTrue(shows("0xcryptkey"))
        onNodeWithText("Copy keys").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Copied") }
        assertTrue(did.any { it.startsWith("copied") && "0xauthkey" in it })
    }

    @Test
    fun `a ship Azimuth has no keys for, or a lookup that fails, says which`() {
        profile(keys = object : AzimuthRpc by zodKeys {
            override suspend fun keys(ship: String) = Result.success<AzimuthRpc.Keys?>(null)
        }) { waitUntil(timeoutMillis = 5_000) { shows("Azimuth holds no keys for this ship.") } }
        profile(keys = object : AzimuthRpc by zodKeys {
            override suspend fun keys(ship: String) = Result.failure<AzimuthRpc.Keys?>(IllegalStateException("down"))
        }) { waitUntil(timeoutMillis = 5_000) { shows("Could not read your keys") } }
    }
}
