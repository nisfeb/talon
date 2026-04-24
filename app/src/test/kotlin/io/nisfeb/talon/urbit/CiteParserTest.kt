package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CiteParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(raw: String): CiteParse =
        parseCite(json.parseToJsonElement(raw).jsonObject)

    // ─── channel cites ─────────────────────────────────────────────

    @Test
    fun `chan cite with nest and where resolves to ChannelPost target`() {
        val r = parse("""
            {"chan":{"nest":"chat/~sampel/general","where":"/msg/1.234"}}
        """.trimIndent())
        val t = r.target
        assertTrue(t is CiteTarget.ChannelPost)
        t as CiteTarget.ChannelPost
        assertEquals("chat/~sampel/general", t.nest)
        assertEquals("1.234", t.postDa)
        assertNull(t.replyDa)
        assertEquals("Post in #general", r.label)
    }

    @Test
    fun `chan cite without where still gives a channel-post label`() {
        val r = parse("""{"chan":{"nest":"chat/~sampel/general","where":""}}""")
        // With no post-da we can't resolve a target, but we still show
        // a meaningful label.
        assertEquals("Post in #general", r.label)
        assertNull(r.target)
    }

    @Test
    fun `chan cite with reply-da populates replyDa`() {
        // where format has both post and reply @ud atoms.
        val r = parse("""
            {"chan":{"nest":"chat/~sampel/gen","where":"/msg/1.234/reply/5.678"}}
        """.trimIndent())
        val t = r.target as CiteTarget.ChannelPost
        assertEquals("1.234", t.postDa)
        assertEquals("5.678", t.replyDa)
    }

    // ─── group cites ───────────────────────────────────────────────

    @Test
    fun `bare group cite resolves to Group target`() {
        // Spec shape: {group: "<flag>"}.
        val r = parse("""{"group":"~sampel-palnet/my-group"}""")
        val t = r.target as CiteTarget.Group
        assertEquals("~sampel-palnet/my-group", t.flag)
        assertTrue(r.label.startsWith("Group "))
    }

    @Test
    fun `wrapped group cite with flag key resolves to Group target`() {
        // Defensive: some wire shapes wrap the flag in an object.
        val r = parse("""{"group":{"flag":"~sampel-palnet/my-group"}}""")
        val t = r.target as CiteTarget.Group
        assertEquals("~sampel-palnet/my-group", t.flag)
    }

    @Test
    fun `wrapped group cite with id key resolves to Group target`() {
        val r = parse("""{"group":{"id":"~sampel-palnet/my-group"}}""")
        val t = r.target as CiteTarget.Group
        assertEquals("~sampel-palnet/my-group", t.flag)
    }

    @Test
    fun `wrapped group cite without flag or id falls through to Reference`() {
        val r = parse("""{"group":{"other":"x"}}""")
        assertNull(r.target)
        assertEquals("Reference", r.label)
    }

    // ─── desk cites ────────────────────────────────────────────────

    @Test
    fun `desk cite with flag carries an App label`() {
        val r = parse("""{"desk":{"flag":"~sampel/landscape","where":""}}""")
        assertEquals("App ~sampel/landscape", r.label)
        assertNull(r.target)
    }

    @Test
    fun `desk cite without flag falls back generically`() {
        val r = parse("""{"desk":{"where":""}}""")
        assertEquals("App reference", r.label)
    }

    // ─── file / bait / url cites ──────────────────────────────────

    @Test
    fun `file cite with url + name becomes a Url target`() {
        val r = parse("""
            {"file":{"url":"https://x/y.png","name":"photo.png","size":12345}}
        """.trimIndent())
        val t = r.target as CiteTarget.Url
        assertEquals("https://x/y.png", t.url)
        assertTrue(r.label.contains("photo.png"))
        assertTrue(r.label.contains("KB"))  // 12345 → 12.1 KB
    }

    @Test
    fun `bait cite with url is treated like a file`() {
        val r = parse("""{"bait":{"url":"https://x/y","name":"thing"}}""")
        assertNotNull(r.target)
    }

    @Test
    fun `top-level url on the cite itself is caught as a last resort`() {
        val r = parse("""{"url":"https://example.com","title":"hi"}""")
        val t = r.target as CiteTarget.Url
        assertEquals("https://example.com", t.url)
        assertEquals("hi", r.label)
    }

    // ─── unknown fallback ──────────────────────────────────────────

    @Test
    fun `unknown cite keys fall through to the generic Reference label`() {
        val r = parse("""{"surprise":"!"}""")
        assertEquals("Reference", r.label)
        assertNull(r.target)
    }
}
