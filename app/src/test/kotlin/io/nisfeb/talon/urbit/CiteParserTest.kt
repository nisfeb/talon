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

    @Test
    fun `file cite with url but no name derives a name from the URL tail`() {
        // Regression guard: mutation-testing surfaced that the
        // `url != null || name != null` branch was indistinguishable
        // from `url == null || name != null` for our other fixtures.
        // This case separates them: url set, name absent.
        val r = parse("""{"file":{"url":"https://x/photo.png?size=large"}}""")
        val t = r.target as CiteTarget.Url
        assertEquals("https://x/photo.png?size=large", t.url)
        // Tail (filename minus query) is the fallback display name.
        assertTrue(r.label.contains("photo.png"))
    }

    @Test
    fun `file cite with only a size but no url or name falls through to Reference`() {
        // url == null && name == null → skip the file-block branch.
        // Regression guard for the `!= null || != null` predicate.
        val r = parse("""{"file":{"size":1024}}""")
        assertEquals("Reference", r.label)
        assertNull(r.target)
    }

    // ─── humanFileSize branches ────────────────────────────────

    @Test
    fun `file cite with size in MB range formats as MB`() {
        val r = parse("""
            {"file":{"url":"https://x/y","name":"big","size":10485760}}
        """.trimIndent())
        // 10 MiB — should format with an "MB" suffix.
        assertTrue("label=${r.label}", r.label.contains("MB"))
    }

    @Test
    fun `file cite with size in GB range formats as GB`() {
        // 2 GiB — exceeds the mb<1024 cutoff. Catches the
        // mutation-tester finding at CiteParser.kt line 139.
        val r = parse("""
            {"file":{"url":"https://x/y","name":"huge","size":2147483648}}
        """.trimIndent())
        assertTrue("label=${r.label}", r.label.contains("GB"))
    }

    // ─── unknown fallback ──────────────────────────────────────────

    @Test
    fun `unknown cite keys fall through to the generic Reference label`() {
        val r = parse("""{"surprise":"!"}""")
        assertEquals("Reference", r.label)
        assertNull(r.target)
    }
}
