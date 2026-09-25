package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.AccentMode
import io.nisfeb.talon.ui.GroupChannelOrder
import io.nisfeb.talon.ui.InMemoryUiSettings
import io.nisfeb.talon.ui.RailItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Display preferences kept on the ship: one arriving is applied here and
 * not sent back; one changed here goes up once; one that arrives before
 * there is anywhere to put it waits; nonsense is ignored.
 */
class SettingsSyncUiPrefsTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-uiprefs-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
    private val ui = InMemoryUiSettings()
    // Not runBlocking's own: the watchers never end, so they get a scope of their own.
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun close() {
        runBlocking { watchers.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    /** A ui-prefs entry as the ship announces it. */
    private suspend fun arrives(entry: String, value: String) = sync.applySettingsEvent(
        Json.parseToJsonElement(
            """{"settings-event":{"put-entry":{"desk":"talon","bucket-key":"ui-prefs","entry-key":"$entry","value":${JsonPrimitive(value)}}}}""",
        ).jsonObject,
    )

    private fun pushed(entry: String) = ship.pokesTo("settings").map { it.json.toString() }.filter { "\"entry-key\":\"$entry\"" in it }

    private suspend fun settled(what: suspend () -> Boolean) = withTimeout(5_000) { while (!what()) delay(20) }

    /** Attached, and the watchers listening: they subscribe on their own
     *  thread, so a preference unrelated to the test is changed until one
     *  is heard going up. Without this a "not sent back" could pass only
     *  because nothing was listening yet. */
    private suspend fun watching() {
        sync.attachUiSettings(ui, watchers)
        settled {
            ui.setHideComposerButtons(!ui.hideComposerButtons.value)
            delay(100)
            pushed("hide-composer-buttons").isNotEmpty()
        }
    }

    @Test
    fun `a preference from the ship is applied here and not sent back, even one this build completes`() = live {
        watching()
        arrives("group-channel-order", """{"value":"HostOrder"}""")
        settled { ui.groupChannelOrder.value == GroupChannelOrder.HostOrder }
        arrives("rail-item-order", """{"order":["Mail","Chats","NotAThing"]}""")
        settled { ui.railItemOrder.value.take(2) == listOf(RailItem.Mail, RailItem.Chats) }
        delay(300)
        assertTrue(pushed("group-channel-order").isEmpty() && pushed("rail-item-order").isEmpty(), "applying is not an edit: ${ship.pokesTo("settings")}")
    }

    @Test
    fun `a preference changed here goes up once`() = live {
        sync.attachUiSettings(ui, watchers)
        // The watchers subscribe on their own thread, and a change made
        // before they do is the value they start from: change it until
        // one is heard going up.
        settled {
            ui.setPowerFeaturesEnabled(!ui.powerFeaturesEnabled.value)
            delay(100)
            pushed("power-features").isNotEmpty()
        }
        assertTrue("enabled" in pushed("power-features").last())
        val sent = pushed("power-features").size
        ui.setPowerFeaturesEnabled(ui.powerFeaturesEnabled.value)
        delay(300)
        assertEquals(sent, pushed("power-features").size, "the same value again is not a change")
    }

    @Test
    fun `a preference that arrives before the store waits for it`() = live {
        arrives("power-features", """{"enabled":true}""")
        assertEquals(false, ui.powerFeaturesEnabled.value)
        sync.attachUiSettings(ui, watchers)
        settled { ui.powerFeaturesEnabled.value }
    }

    @Test
    fun `an accent arrives whole, and an unknown mode falls back to the profile's`() = live {
        watching()
        arrives("accent", """{"enabled":true,"mode":"Custom","customHex":"#336699"}""")
        settled { ui.accentSettings.value.customHex == "#336699" }
        assertEquals(AccentMode.Custom, ui.accentSettings.value.mode)
        arrives("accent", """{"enabled":true,"mode":"Sparkly"}""")
        settled { ui.accentSettings.value.mode == AccentMode.Profile }
        delay(300)
        assertTrue(pushed("accent").isEmpty(), "what arrived, as it was kept, is not sent back")
    }

    @Test
    fun `nonsense from the ship changes nothing`() = live {
        sync.attachUiSettings(ui, watchers)
        val before = ui.groupChannelOrder.value
        arrives("group-channel-order", """{"value":"Sideways"}""")
        arrives("rail-item-order", """{"order":["Nope"]}""")
        delay(200)
        assertEquals(before, ui.groupChannelOrder.value)
        assertTrue(ui.railItemOrder.value.isNotEmpty())
    }
}
