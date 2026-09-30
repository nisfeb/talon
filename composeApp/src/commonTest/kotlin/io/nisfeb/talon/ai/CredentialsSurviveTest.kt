package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule that had been missing while this bug was fixed four times.
 *
 * Each earlier fix guarded the path that had just lost a key, and a
 * new path appeared: a peer's push, an entry that replaced a whole
 * blob, a stale removal stamp. This pins the rule itself, so the next
 * path has to break it on purpose.
 */
class CredentialsSurviveTest {
    private val mine = AiSettings.Config(
        provider = AiSettings.Provider.OpenRouter,
        apiKey = "sk-mine",
        model = "anthropic/claude-sonnet-4",
        baseUrl = "https://openrouter.ai/api/v1",
        braveApiKey = "brave-mine",
        sttApiKey = "whisper-mine",
        sttApiKeySetAtMs = 1_000L,
        privateApiKey = "local-mine",
        privateBaseUrl = "http://localhost:1234",
        privateModel = "qwen2.5-7b",
    )

    /** What a device with nothing configured would send. */
    private val empty = AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null)

    @Test
    fun `nothing arriving from elsewhere can take a credential away`() {
        val after = empty.keepingCredentials(mine)
        assertEquals("sk-mine", after.apiKey)
        assertEquals("brave-mine", after.braveApiKey)
        assertEquals("whisper-mine", after.sttApiKey)
        assertEquals("local-mine", after.privateApiKey)
        assertEquals("anthropic/claude-sonnet-4", after.model)
        assertEquals("https://openrouter.ai/api/v1", after.baseUrl)
        assertEquals("http://localhost:1234", after.privateBaseUrl)
        assertEquals("qwen2.5-7b", after.privateModel)
    }

    @Test
    fun `a real credential from elsewhere is taken`() {
        val theirs = empty.copy(apiKey = "sk-theirs", braveApiKey = "brave-theirs")
        val after = theirs.keepingCredentials(mine)
        assertEquals("sk-theirs", after.apiKey)
        assertEquals("brave-theirs", after.braveApiKey)
        assertEquals("whisper-mine", after.sttApiKey, "and what it said nothing about stays")
    }

    @Test
    fun `a removal older than the key it would remove does not win`() {
        // The loop people hit: type a key here, and a peer's old removal
        // stamp, still sitting on the ship, blanks it again on the pull.
        val stale = empty.copy(sttApiKey = "", sttApiKeyRemovedAtMs = 500L)
        assertEquals("whisper-mine", stale.keepingCredentials(mine).sttApiKey)
        // A removal made after this key was set is a real one.
        val fresh = empty.copy(sttApiKey = "", sttApiKeyRemovedAtMs = 2_000L)
        assertEquals("", fresh.keepingCredentials(mine).sttApiKey)
    }

    @Test
    fun `when this device has nothing, what arrives is simply taken`() {
        val after = mine.keepingCredentials(empty)
        assertEquals("sk-mine", after.apiKey)
        assertEquals("local-mine", after.privateApiKey)
    }
}
