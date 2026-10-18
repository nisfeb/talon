package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.DesktopEmbedder
import io.nisfeb.talon.ai.DesktopEmbeddingIndexer
import io.nisfeb.talon.ai.DesktopSearchEmbedderClient
import io.nisfeb.talon.compose.App
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The smart-search index follows the Smart features switch through the
 * whole desktop app, mid-session too: off stops a pass and calls the
 * model no more, on runs one.
 */
@OptIn(ExperimentalTestApi::class)
class SmartIndexSwitchTest {
    /** The settings with the switch the test flips. */
    private class Switch(val flow: MutableStateFlow<AiSettings.Config>) :
        AiSettingsRepository by FakeAiSettings(flow.value) {
        override val state: StateFlow<AiSettings.Config> = flow
        fun set(on: Boolean) { flow.value = flow.value.copy(smartFeaturesEnabled = on) }
    }

    /** A model that answers only once [gate] opens, so a pass is caught midway. */
    private class HeldModel : DesktopEmbedder() {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        override suspend fun embed(text: String): FloatArray {
            calls.incrementAndGet()
            gate.await()
            return floatArrayOf(1f, 0f)
        }
    }

    private fun app(smartOn: Boolean, block: ComposeUiTest.(Switch, HeldModel, () -> DesktopSearchEmbedderClient) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-smart-").toFile()
        io.nisfeb.talon.ui.screens.forgetHomeListSnapshot("~zod")
        val ship = FakeShip("~zod").apply {
            scries["activity/v6/feed/init/30"] = """{"all":[]}"""
            scries["groups-ui/v7/init"] = """{"foreigns":{}}"""
        }
        val switch = Switch(MutableStateFlow(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, smartFeaturesEnabled = smartOn)))
        val model = HeldModel()
        var client: DesktopSearchEmbedderClient? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                        App(
                            http = ship.http,
                            sessionStore = ship.session,
                            aiSettings = switch,
                            createDb = { key ->
                                Room.databaseBuilder<AppDatabase>(File(tmp, "$key.db").absolutePath)
                                    .setDriver(BundledSQLiteDriver())
                                    .fallbackToDestructiveMigration(dropAllTables = true)
                                    .build().also { db ->
                                        runBlocking {
                                            db.messages().upsertAll(listOf(MessageEntity("~bus", "1", "~bus", 1, """[{"inline":["lunch at noon"]}]""", "/chat")))
                                        }
                                    }
                            },
                            drafts = InMemoryDraftStore(),
                            updateState = UpdateState(scope, StaticUpdateRuntime(), NoopUpdateInstallerHook()),
                            createUiSettings = { InMemoryUiSettings() },
                            createSearchEmbedderClient = { db ->
                                DesktopSearchEmbedderClient(db, model, DesktopEmbeddingIndexer(db, model)).also { client = it }
                            },
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithContentDescription("New message").fetchSemanticsNodes().isNotEmpty()
                }
                block(switch, model) { client!! }
                model.gate.complete(Unit)
            }
        } finally {
            scope.cancel()
            // The app closes its database itself, two seconds after it goes.
            Thread { Thread.sleep(5_000); tmp.deleteRecursively() }.apply { isDaemon = true }.start()
        }
    }

    @Test
    fun `off stops the pass midway and on runs one again`() = app(smartOn = true) { switch, model, client ->
        waitUntil(timeoutMillis = 10_000) { client().progress.value.running && model.calls.get() == 1 }
        switch.set(false)
        waitUntil(timeoutMillis = 10_000) { !client().progress.value.running }
        switch.set(true)
        waitUntil(timeoutMillis = 10_000) { client().progress.value.running && model.calls.get() == 2 }
        model.gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { !client().progress.value.running }
        assertEquals(1, client().progress.value.indexed)
    }

    @Test
    fun `off from the start never calls the model`() = app(smartOn = false) { _, model, client ->
        // Real time: the effect would have started a pass well within it.
        Thread.sleep(1_000)
        waitForIdle()
        assertEquals(0, model.calls.get())
        assertTrue(!client().progress.value.running)
    }
}
