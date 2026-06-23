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
    fun `credentials mode with full credentials is upload-ready`() {
        val creds = StorageCreds("https://s3.example.com", "AKIA", "shhh")
        val config = StorageConfig("media", "us-east-1", null, "credentials")
        assertTrue(storageS3Ready(creds, config))
    }

    @Test
    fun `missing service is upload-ready when credentials are present`() {
        val creds = StorageCreds("https://s3.example.com", "AKIA", "shhh")
        val config = StorageConfig("media", "us-east-1", null, null)
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
}
