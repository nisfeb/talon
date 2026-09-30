package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins %storage parsing + the upload-mode decision. The regression that
 * motivated this: a ship with valid S3 credentials but `service` set to
 * `presigned-url` (~dinnyt-divsud) was rejected, even though the
 * credentials upload path works in either mode — matching tlon-apps'
 * `hasCustomS3Creds`, which never looks at `service`.
 */
class StorageUploadTest {

    private fun json(s: String) = Json.parseToJsonElement(s)

    // ─── credentials parsing ──────────────────────────────────────

    @Test
    fun `parses kebab-case credentials wrapped in storage-update`() {
        val creds = parseStorageCredentials(
            json(
                """
                {"storage-update": {"credentials": {
                    "endpoint": "https://s3.example.com",
                    "access-key-id": "AKIA",
                    "secret-access-key": "shhh"
                }}}
                """.trimIndent(),
            ),
        )
        assertEquals("https://s3.example.com", creds?.endpoint)
        assertEquals("AKIA", creds?.accessKeyId)
        assertEquals("shhh", creds?.secretAccessKey)
    }

    @Test
    fun `parses camelCase credentials at the root`() {
        val creds = parseStorageCredentials(
            json(
                """{"endpoint":"e","accessKeyId":"k","secretAccessKey":"s"}""",
            ),
        )
        assertEquals("e", creds?.endpoint)
        assertEquals("k", creds?.accessKeyId)
        assertEquals("s", creds?.secretAccessKey)
    }

    // ─── configuration parsing ────────────────────────────────────

    @Test
    fun `parses configuration including the presigned-url service field`() {
        val config = parseStorageConfiguration(
            json(
                """
                {"storage-update": {"configuration": {
                    "current-bucket": "media",
                    "region": "us-west-2",
                    "public-url-base": "https://cdn.example.com",
                    "service": "presigned-url"
                }}}
                """.trimIndent(),
            ),
        )
        assertEquals("media", config?.bucket)
        assertEquals("us-west-2", config?.region)
        assertEquals("https://cdn.example.com", config?.publicUrlBase)
        assertEquals("presigned-url", config?.service)
    }

    @Test
    fun `non-object body parses to null`() {
        assertNull(parseStorageCredentials(json("\"nope\"")))
        assertNull(parseStorageConfiguration(json("42")))
    }

    // ─── the fix: service mode does not gate the credentials path ──

    @Test
    fun `presigned-url mode with full credentials is upload-ready`() {
        // The regression. Full creds present, service = presigned-url —
        // must still be ready (tlon-apps ignores `service` here).
        val creds = StorageCreds("https://s3.example.com", "AKIA", "shhh")
        val config = StorageConfig("media", "us-east-1", null, "presigned-url")
        assertTrue(storageS3Ready(creds, config))
    }

    @Test
    fun `blank credentials are not upload-ready regardless of mode`() {
        val config = StorageConfig("media", "us-east-1", null, "credentials")
        assertFalse(storageS3Ready(StorageCreds("", "AKIA", "shhh"), config))
        assertFalse(storageS3Ready(StorageCreds("e", "", "shhh"), config))
        assertFalse(storageS3Ready(StorageCreds("e", "AKIA", ""), config))
    }

    @Test
    fun `a blank bucket is not upload-ready`() {
        val creds = StorageCreds("https://s3.example.com", "AKIA", "shhh")
        val config = StorageConfig("", "us-east-1", null, "presigned-url")
        assertFalse(storageS3Ready(creds, config))
    }

    // ── upload name length ────────────────────────────────────────
    //
    // ENAMETOOLONG (os error 36) surfaced as an HTTP 500 from S3 —
    // and from memex before it — on a file whose name was simply too
    // long for the filesystem underneath. 255 bytes per path
    // component is the real limit; these pin that we stay well under
    // it, extension intact, without splitting a character.

    private fun String.utf8Len() = this.encodeToByteArray().size

    @Test
    fun `an over-long name is cut to the budget and keeps its extension`() {
        val name = "a".repeat(400) + ".jpeg"
        val out = truncateUploadName(name)
        assertTrue(out.utf8Len() <= MAX_UPLOAD_NAME_BYTES, "got ${out.utf8Len()} bytes")
        assertTrue(out.endsWith(".jpeg"), "lost the extension: $out")
    }

    /**
     * A name that survives an encode/decode round trip has no half-
     * written code point in it: Kotlin maps a split sequence or a lone
     * surrogate to U+FFFD, so the string comes back changed.
     */
    private fun String.survivesUtf8() = encodeToByteArray().decodeToString() == this

    @Test
    fun `truncation never splits a multi-byte character`() {
        // The budget is chosen so it does NOT divide by the character
        // width: 20 - 4 for ".png" leaves 16 bytes, and a 3-byte
        // character does not fit 16 evenly. A naive cut of the encoded
        // bytes lands inside the sixth character. With the default
        // budget these numbers happen to divide exactly, which is how
        // an earlier version of this test passed against a deliberately
        // broken implementation.
        val out = truncateUploadName("\u6f22".repeat(200) + ".png", maxBytes = 20)
        assertTrue(out.survivesUtf8(), "cut mid-character: $out")
        assertTrue(out.utf8Len() <= 20, "got ${out.utf8Len()} bytes")
        assertEquals("\u6f22".repeat(5) + ".png", out)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // Same trick: 22 - 4 leaves 18 bytes, and emoji are 4 bytes
        // each, so a byte-only cut lands inside the fifth one.
        val out = truncateUploadName("\uD83D\uDE00".repeat(100) + ".gif", maxBytes = 22)
        assertTrue(out.survivesUtf8(), "cut a surrogate pair in half: $out")
        assertTrue(out.utf8Len() <= 22, "got ${out.utf8Len()} bytes")
        assertEquals("\uD83D\uDE00".repeat(4) + ".gif", out)
    }

    @Test
    fun `a name that is only an over-long extension still gets a stem`() {
        val out = truncateUploadName("." + "x".repeat(400))
        assertTrue(out.isNotEmpty())
        assertTrue(out.utf8Len() <= MAX_UPLOAD_NAME_BYTES, "got ${out.utf8Len()} bytes")
        assertTrue(out != ".", "returned a bare dot")
    }

    @Test
    fun `a directory part is stripped rather than sent as a path`() {
        // A separator would split one over-long component into two
        // segments and quietly change the object's key.
        assertEquals("photo.jpg", truncateUploadName("/tmp/holiday/photo.jpg"))
        assertEquals("photo.jpg", truncateUploadName("C:\\Users\\me\\photo.jpg"))
    }

    @Test
    fun `a name exactly at the budget goes whole, and one byte over is cut`() {
        assertEquals("a".repeat(20), truncateUploadName("a".repeat(20), maxBytes = 20))
        assertEquals("a".repeat(20), truncateUploadName("a".repeat(21), maxBytes = 20))
    }

    @Test
    fun `a dotfile or a trailing dot is not taken for an extension`() {
        assertEquals(".abcdefghi", truncateUploadName(".abcdefghijkl", maxBytes = 10))
        assertEquals("abcdefghij", truncateUploadName("abcdefghijkl.", maxBytes = 10))
    }

    @Test
    fun `an extension of sixteen bytes is kept, and one of seventeen is not`() {
        val sixteen = ".abcdefghijklmno"
        assertTrue(truncateUploadName("x".repeat(200) + sixteen, maxBytes = 40).endsWith(sixteen))
        assertEquals("x".repeat(40), truncateUploadName("x".repeat(200) + sixteen + "p", maxBytes = 40))
    }

    @Test
    fun `each UTF-8 width is counted to its edge`() {
        // One, two, two and three bytes: the last of each width and the
        // first of the next. A budget of ten takes 10, 5, 5 and 3 of them.
        for ((ch, fits) in listOf('\u007F' to 10, '\u0080' to 5, '\u07FF' to 5, '\u0800' to 3)) {
            assertEquals(ch.toString().repeat(fits), truncateUploadName(ch.toString().repeat(20), maxBytes = 10), "U+${ch.code.toString(16)}")
        }
    }

    @Test
    fun `an empty name still yields something uploadable`() {
        assertEquals("file", truncateUploadName(""))
    }

    @Test
    fun `the S3 key built from a truncated name fits a path component`() {
        // The real thing: talon/<@da>-<name>. Only the part after the
        // last slash faces the 255-byte limit.
        val da = UrbitTime.unixMsToDa(1_756_500_000_000L).toString()
        val name = truncateUploadName("z".repeat(500) + ".jpeg")
        val component = "$da-$name"
        assertTrue(
            component.utf8Len() <= 255,
            "key component is ${component.utf8Len()} bytes: $component",
        )
    }
}
