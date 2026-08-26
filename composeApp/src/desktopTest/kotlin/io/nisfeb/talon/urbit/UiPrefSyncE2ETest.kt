package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.NoopDailyDigestSettings
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.FolderItemOrder
import io.nisfeb.talon.ui.GroupChannelOrder
import io.nisfeb.talon.ui.InMemoryUiSettings
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two clients of the same ship — i.e. one user's two devices. A
 * preference changed on one must land on the other, and must not
 * echo back and forth. Opt-in like the other live tests:
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*UiPrefSyncE2E*'
 */
class UiPrefSyncE2ETest {

    private class MemStore : SessionStore {
        private var s: SavedSession? = null
        private var active: String? = null
        override fun all() = listOfNotNull(s)
        override fun active() = s
        override fun activeShip() = active
        override fun save(entry: SavedSession, makeActive: Boolean) {
            s = entry
            if (makeActive) active = entry.ship
        }
        override fun setActive(ship: String) { active = ship }
        override fun remove(ship: String) { s = null; active = null }
        override fun clearAll() { s = null; active = null }
    }

    private object StubAi : AiSettingsRepository {
        override val state: StateFlow<AiSettings.Config> = MutableStateFlow(
            AiSettings.Config(AiSettings.Provider.Anthropic, "", null),
        )
        override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null
        override fun update(
            provider: AiSettings.Provider,
            apiKey: String,
            model: String?,
            baseUrl: String?,
        ) {}
        override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {}
        override fun setSyncEnabled(enabled: Boolean) {}
        override fun setBraveApiKey(key: String) {}
        override fun setPrompt(kind: AiSettings.PromptKind, value: String) {}
        override fun applyRemote(config: AiSettings.Config) {}
        override fun clear() {}
    }

    private fun tempDb(): AppDatabase {
        val dir = createTempDirectory(prefix = "talon-uipref-").toFile()
        return Room.databaseBuilder<AppDatabase>(File(dir, "p.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Test
    fun preferenceChangedOnOneDeviceReachesTheOther() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping live ui-pref sync test")
            return
        }
        val url = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val code = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"

        runBlocking<Unit> {
            val scope = CoroutineScope(Job())
            // Two independent clients of the same ship.
            val (syncA, uiA) = client(url, code, scope)
            val (syncB, uiB) = client(url, code, scope)
            delay(3_000)

            // Device A flips two preferences of different shapes.
            val targetGroupOrder =
                if (uiA.groupChannelOrder.value == GroupChannelOrder.Recent) {
                    GroupChannelOrder.HostOrder
                } else {
                    GroupChannelOrder.Recent
                }
            uiA.setGroupChannelOrder(targetGroupOrder)
            uiA.setPowerFeaturesEnabled(!uiA.powerFeaturesEnabled.value)
            val wantPower = uiA.powerFeaturesEnabled.value

            // Device B sees them.
            withTimeout(30_000) {
                uiB.groupChannelOrder.first { it == targetGroupOrder }
            }
            withTimeout(30_000) {
                uiB.powerFeaturesEnabled.first { it == wantPower }
            }
            println("group order + power features reached the second device")

            // And the values settle rather than ping-ponging: after the
            // dust clears both devices still agree.
            delay(4_000)
            assertEquals(targetGroupOrder, uiA.groupChannelOrder.value)
            assertEquals(targetGroupOrder, uiB.groupChannelOrder.value)
            assertEquals(wantPower, uiA.powerFeaturesEnabled.value)
            assertEquals(wantPower, uiB.powerFeaturesEnabled.value)

            // A folder-order change from the *other* direction too.
            val targetFolderOrder =
                if (uiB.folderItemOrder.value == FolderItemOrder.Manual) {
                    FolderItemOrder.Recent
                } else {
                    FolderItemOrder.Manual
                }
            uiB.setFolderItemOrder(targetFolderOrder)
            withTimeout(30_000) {
                uiA.folderItemOrder.first { it == targetFolderOrder }
            }
            println("folder order reached the first device")

            scope.cancel()
        }
    }

    private suspend fun client(
        url: String,
        code: String,
        scope: CoroutineScope,
    ): Pair<SettingsSyncImpl, InMemoryUiSettings> {
        val http = createAppHttpClient()
        val session = UrbitSession(http, MemStore())
        session.login(url, code).getOrThrow()
        val db = tempDb()
        val ui = InMemoryUiSettings()
        val sync = SettingsSyncImpl(
            db = db,
            aiSettings = StubAi,
            dailyDigestSettings = NoopDailyDigestSettings(),
        )
        val repo = TlonChatRepo(db, settingsSync = sync)
        repo.start(session)
        sync.attachUiSettings(ui, scope)
        return sync to ui
    }
}
