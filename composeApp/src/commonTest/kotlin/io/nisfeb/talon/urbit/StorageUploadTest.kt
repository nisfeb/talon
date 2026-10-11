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

    // ── upload names ──────────────────────────────────────────────
    //
    // The object is public at its URL. Its name used to be the upload's
    // millisecond and the file's own name: what it was, and a key a
    // stranger could guess (2026-10-11).

    private val random = Regex("[0-9a-f]{32}")

    @Test
    fun `an upload is named by chance and its extension, never by the file`() {
        val name = uploadName("IMG_20261011_143012 passport.JPG", "image/jpeg")
        assertTrue(Regex("[0-9a-f]{32}\\.jpg").matches(name), name)
        assertTrue("IMG" !in name && "passport" !in name && "2026" !in name)
        assertTrue(uploadName("photo.jpg", "image/jpeg") != uploadName("photo.jpg", "image/jpeg"), "two photo.jpg are two names")
    }

    @Test
    fun `a name with no usable extension takes one from its type, or none`() {
        assertTrue(uploadName("scan", "application/pdf").endsWith(".pdf"))
        assertTrue(uploadName("clip.weird-ext!", "audio/mp4").endsWith(".mp4"))
        assertTrue(uploadName("C:\\Users\\me\\notes", "application/octet-stream").let { random.matches(it) }, "no extension at all")
        assertTrue(uploadName("", "").let { random.matches(it) })
        // Nothing of a long name survives to be too long for a filesystem.
        assertEquals(32 + ".jpeg".length, uploadName("z".repeat(500) + ".jpeg", "image/jpeg").length)
    }
}
