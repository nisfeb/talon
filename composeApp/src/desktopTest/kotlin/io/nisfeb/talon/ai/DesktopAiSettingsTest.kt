package io.nisfeb.talon.ai

import io.nisfeb.talon.util.AppDirs
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The desktop AI settings file, which holds the user's API keys in the
 * clear: what survives a relaunch, and what a bad file costs. The test
 * JVM's config dir is the build's own test-home, never the real one.
 */
class DesktopAiSettingsTest {
    private val file = File(AppDirs.userData, "ai_settings.json")
    private val bak = File(AppDirs.userData, "ai_settings.json.bak")

    @BeforeTest
    @AfterTest
    fun clean() {
        file.delete()
        bak.delete()
    }

    @Test
    fun `a fresh install writes its device id at once, and a relaunch keeps it`() {
        val first = DesktopAiSettings().state.value.deviceId
        assertTrue(first.isNotBlank() && file.exists(), "written before any setting is saved")
        assertEquals(first, DesktopAiSettings().state.value.deviceId)
    }

    @Test
    fun `a provider and key survive a relaunch, and each change is announced`() {
        val seen = mutableListOf<Boolean>()
        DesktopAiSettings().apply {
            onStateChange = { _, off -> seen += off }
            update(AiSettings.Provider.OpenAi, "sk-test", "gpt-x", null)
            setFeature(AiSettings.Feature.Agent, true)
        }
        val back = DesktopAiSettings().state.value
        assertEquals(AiSettings.Provider.OpenAi to "sk-test", back.provider to back.apiKey)
        assertTrue(back.agentEnabled && back.askUrbitEnabled, "the legacy flag follows the assistant")
        assertEquals(listOf(false, false), seen)
    }

    @Test
    fun `turning sync off says it was turned off`() {
        val seen = mutableListOf<Boolean>()
        DesktopAiSettings().apply {
            onStateChange = { _, off -> seen += off }
            setSyncEnabled(false)
            setSyncEnabled(false)
        }
        assertEquals(listOf(true, false), seen, "only the transition counts")
    }

    @Test
    fun `an unreadable file is kept as a backup, key and all, and defaults written`() {
        file.parentFile.mkdirs()
        file.writeText("""{"provider":"OpenAi","apiKey":"sk-precious" GARBAGE""")
        val cfg = DesktopAiSettings().state.value
        assertEquals("", cfg.apiKey)
        assertTrue("sk-precious" in bak.readText(), "the key is not destroyed")
        assertTrue(file.exists() && "sk-precious" !in file.readText())
    }

    @Test
    fun `the old per-feature switches, all off, keep smart features off`() {
        file.parentFile.mkdirs()
        file.writeText(
            """{"provider":"Anthropic","apiKey":"","model":null,"deviceId":"d1","semanticSearchEnabled":false,""" +
                """"topicClustersEnabled":false,"importantMessagesEnabled":false,"entityActionsEnabled":false}""",
        )
        assertFalse(DesktopAiSettings().state.value.smartFeaturesEnabled)
        assertFalse(DesktopAiSettings().state.value.smartFeaturesEnabled, "and it was written back")
    }

    @Test
    fun `a file from before device ids gets one, and keeps it`() {
        file.parentFile.mkdirs()
        file.writeText("""{"provider":"Anthropic","apiKey":"sk-a","model":null}""")
        val id = DesktopAiSettings().state.value.deviceId
        assertTrue(id.isNotBlank())
        assertEquals(id, DesktopAiSettings().state.value.deviceId)
        assertEquals("sk-a", DesktopAiSettings().state.value.apiKey)
    }

    @Test
    fun `settings from the ship never take away a key this device holds, and are not echoed back`() {
        val s = DesktopAiSettings()
        s.update(AiSettings.Provider.OpenAi, "sk-mine", null, null)
        var echoed = false
        s.onStateChange = { _, _ -> echoed = true }
        s.applyRemote(s.state.value.copy(apiKey = "", catchMeUpEnabled = false))
        val now = DesktopAiSettings().state.value
        assertEquals("sk-mine", now.apiKey)
        assertFalse(now.catchMeUpEnabled, "the rest of what arrived is taken")
        assertFalse(echoed)
    }

    @Test
    fun `clearing starts over with a new device`() {
        val s = DesktopAiSettings()
        val before = s.state.value.deviceId
        s.update(AiSettings.Provider.OpenAi, "sk-gone", null, null)
        s.clear()
        assertEquals("", DesktopAiSettings().state.value.apiKey)
        assertNotEquals(before, s.state.value.deviceId)
    }
}
