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
import kotlin.test.assertFalse
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

    // Bubbles or rows is a taste, not a screen size, so it travels. The
    // body is %settings' own: the value a cord of JSON, as Tlon writes it.
    @Test
    fun `rows chosen here go up as the ship's own body`() = live {
        watching()
        ui.setChatBubbles(false)
        settled { pushed("chat-bubbles").isNotEmpty() }
        assertEquals(
            """{"put-entry":{"desk":"talon","bucket-key":"ui-prefs","entry-key":"chat-bubbles","value":"{\"enabled\":false}"}}""",
            pushed("chat-bubbles").single(),
        )
    }

    @Test
    fun `rows chosen on another device are taken here and not sent back`() = live {
        watching()
        arrives("chat-bubbles", """{"enabled":false}""")
        settled { !ui.chatBubbles.value }
        arrives("chat-bubbles", """{"enabled":"yes"}""")
        delay(300)
        assertFalse(ui.chatBubbles.value, "nonsense changes nothing")
        assertTrue(pushed("chat-bubbles").isEmpty(), "applying is not an edit: ${pushed("chat-bubbles")}")
    }

    // The background behind bubbles travels with them.
    @Test
    fun `a chat background goes up as the ship's own body, and one from another device is taken, not nonsense`() = live {
        watching()
        ui.setChatBackground("#0B1120")
        settled { pushed("chat-background").isNotEmpty() }
        assertEquals(
            """{"put-entry":{"desk":"talon","bucket-key":"ui-prefs","entry-key":"chat-background","value":"{\"value\":\"#0B1120\"}"}}""",
            pushed("chat-background").single(),
        )
        arrives("chat-background", """{"value":"#123456"}""")
        settled { ui.chatBackground.value == "#123456" }
        arrives("chat-background", """{"value":"navy"}""")
        delay(300)
        assertEquals("#123456", ui.chatBackground.value, "not a colour, so nothing changes")
        arrives("chat-background", """{"value":""}""")
        settled { ui.chatBackground.value == "" }
    }

    // ─── fonts ────────────────────────────────────────────────────

    private fun fontJson(vararg ids: String, family: String? = null, removed: List<String> = emptyList()) =
        io.nisfeb.talon.ui.FontSettings(ids.map { io.nisfeb.talon.ui.InstalledFont(it, "F-$it") }, family, removed).toJson()

    // Another device's list is merged in, never taken whole: what this one
    // has and the other does not goes back up, so the ship holds both.
    @Test
    fun `fonts from another device join this one's, and this one's go back up`() = live {
        watching()
        ui.setFontSettings(io.nisfeb.talon.ui.FontSettings.fromJson(fontJson("aaaa1111", family = "F-aaaa1111"))!!)
        settled { pushed("fonts").isNotEmpty() }
        arrives("fonts", fontJson("bbbb2222", family = "F-bbbb2222"))
        settled { ui.fontSettings.value.fonts.map { it.id }.sorted() == listOf("aaaa1111", "bbbb2222") }
        assertEquals("F-bbbb2222", ui.fontSettings.value.family, "the choice is the latest word")
        settled { pushed("fonts").any { "aaaa1111" in it && "bbbb2222" in it } }
    }

    // "an empty list should not win" (settings-sync rule): only a removal said is one.
    @Test
    fun `an empty font list from the ship takes nothing off, and a removal does`() = live {
        watching()
        ui.setFontSettings(io.nisfeb.talon.ui.FontSettings.fromJson(fontJson("a", family = "F-a"))!!)
        arrives("fonts", fontJson())
        delay(200)
        assertEquals(listOf("a"), ui.fontSettings.value.fonts.map { it.id })
        arrives("fonts", fontJson(removed = listOf("a")))
        settled { ui.fontSettings.value.fonts.isEmpty() }
    }

    // Fonts installed through Talon were on the ship, offered nowhere
    // else: the list's one push had gone, and a ship whose ui-prefs held
    // other entries was never sent it again.
    @Test
    fun `a font list the ship never got goes up when the device connects`() = live {
        // Installed before anything listened: its push never went.
        ui.setFontSettings(io.nisfeb.talon.ui.FontSettings.fromJson(fontJson("aaaa1111", family = "F-aaaa1111"))!!)
        sync.attachUiSettings(ui, watchers)
        delay(300)
        assertTrue(pushed("fonts").isEmpty())
        ship.scries["settings/desk/talon"] = """{"desk":{"ui-prefs":{"power-features":${JsonPrimitive("""{"enabled":true}""")}}}}"""
        sync.bootstrap()
        settled { pushed("fonts").any { "aaaa1111" in it } }
    }

    @Test
    fun `a device with no fonts sends no list`() = live {
        sync.attachUiSettings(ui, watchers)
        ship.scries["settings/desk/talon"] = """{"desk":{"ui-prefs":{"power-features":${JsonPrimitive("""{"enabled":true}""")}}}}"""
        sync.bootstrap()
        delay(300)
        assertTrue(pushed("fonts").isEmpty(), "${ship.pokesTo("settings")}")
    }

    @Test
    fun `a font list that changes nothing here is not sent back`() = live {
        watching()
        ui.setFontSettings(io.nisfeb.talon.ui.FontSettings.fromJson(fontJson("a", family = "F-a"))!!)
        settled { pushed("fonts").isNotEmpty() }
        val sent = pushed("fonts").size
        arrives("fonts", fontJson("a", family = "F-a"))
        delay(300)
        assertEquals(sent, pushed("fonts").size)
    }

    @Test
    fun `a preference that arrives before the store waits for it`() = live {
        arrives("power-features", """{"enabled":true}""")
        assertEquals(false, ui.powerFeaturesEnabled.value)
        sync.attachUiSettings(ui, watchers)
        settled { ui.powerFeaturesEnabled.value }
    }

    // An older Talon, or the Omarchy plugin, writes a theme with the five
    // alone. Absent is not a choice: the colours set here stay.
    @Test
    fun `a theme from a writer that knows only the five keeps this device's extras`() = live {
        val mine = io.nisfeb.talon.ui.theme.CustomTheme("a1", "Ocean", true, "#38BDF8", "#A78BFA", "#34D399", "#0B1120", "#111827")
            .explicit().copy(link = "#FF0000", text = "#EEEEEE")
        ui.setThemeSettings(io.nisfeb.talon.ui.theme.ThemeSettings(listOf(mine), activeId = "a1"))
        watching()
        arrives("themes", """{"themes":[{"id":"a1","name":"Ocean","dark":true,"primary":"#123456","secondary":"#A78BFA",
            "tertiary":"#34D399","background":"#0B1120","surface":"#111827"}],"activeId":"a1"}""")
        settled { ui.themeSettings.value.active?.primary == "#123456" }
        assertEquals("#FF0000" to "#EEEEEE", ui.themeSettings.value.active!!.let { it.link to it.text })
        arrives("themes", """{"themes":[{"id":"a1","name":"Ocean","dark":true,"primary":"#123456","secondary":"#A78BFA",
            "tertiary":"#34D399","background":"#0B1120","surface":"#111827","text":"","muted":"","raised":"","error":"",
            "selection":"","link":""}],"activeId":"a1"}""")
        settled { ui.themeSettings.value.active?.link == "" }
        delay(300)
        assertTrue(pushed("themes").isEmpty(), "what arrived, as it was kept, is not sent back: ${pushed("themes")}")

        // Changed here, it goes up with every extra written.
        ui.setThemeSettings(ui.themeSettings.value.copy(themes = listOf(ui.themeSettings.value.active!!.copy(link = "#00FF00"))))
        settled { pushed("themes").isNotEmpty() }
        val up = pushed("themes").last()
        assertTrue("#00FF00" in up && "selection" in up, up)
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
