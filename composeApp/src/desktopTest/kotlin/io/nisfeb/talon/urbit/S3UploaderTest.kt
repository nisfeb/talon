package io.nisfeb.talon.urbit

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the wire shape of [S3Uploader.put]. The signing code is
 * hand-rolled and any drift from AWS SigV4 surfaces here rather than
 * at upload time. We don't talk to a real bucket — an OkHttp
 * interceptor captures the [Request] and short-circuits with 200.
 *
 * The two cases mirror tlon-apps' storageApi.ts split: bare PUT for
 * everything except DigitalOcean Spaces, headers-on-the-wire for DO.
 */
class S3UploaderTest {

    private val creds = S3Uploader.Credentials(
        endpoint = "https://s3.us-east-1.amazonaws.com",
        accessKeyId = "test-access-key",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    )

    private val awsConfig = S3Uploader.Configuration(
        bucket = "talon-test",
        region = "us-east-1",
        publicUrlBase = null,
    )

    private val doConfig = S3Uploader.Configuration(
        bucket = "talon-test",
        region = "nyc3",
        publicUrlBase = null,
    )

    private val doCreds = S3Uploader.Credentials(
        endpoint = "https://nyc3.digitaloceanspaces.com",
        accessKeyId = "DOEXAMPLE",
        secretAccessKey = "DOEXAMPLEKEY",
    )

    private fun captureClient(captured: AtomicReference<Request>) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

    @Test
    fun `aws endpoint puts via presigned url with no auth header and no acl headers`() {
        val captured = AtomicReference<Request>()
        S3Uploader.put(
            http = captureClient(captured),
            creds = creds,
            config = awsConfig,
            key = "talon/foo.png",
            bytes = "hello".toByteArray(),
            contentType = "image/png",
        )

        val req = captured.get()
        assertNotNull(req, "interceptor never fired")

        // Auth must be in the query string, not in headers.
        assertNull(req.header("Authorization"), "presigned PUT must not carry Authorization")

        // Public-read / cache-control belong to the DO carve-out only.
        assertNull(req.header("x-amz-acl"), "x-amz-acl on AWS endpoint trips proxies (502)")
        assertNull(req.header("Cache-Control"), "Cache-Control on AWS endpoint trips proxies (502)")

        val url = req.url
        assertEquals("PUT", req.method)
        assertEquals("/talon-test/talon/foo.png", url.encodedPath)
        assertEquals("AWS4-HMAC-SHA256", url.queryParameter("X-Amz-Algorithm"))
        assertEquals("3600", url.queryParameter("X-Amz-Expires"))
        assertEquals("host", url.queryParameter("X-Amz-SignedHeaders"))

        val credential = url.queryParameter("X-Amz-Credential")
        assertNotNull(credential, "X-Amz-Credential missing")
        assertTrue(
            credential.startsWith("test-access-key/"),
            "credential should start with access-key-id, was $credential",
        )
        assertTrue(credential.endsWith("/us-east-1/s3/aws4_request"))

        val date = url.queryParameter("X-Amz-Date")
        assertNotNull(date)
        assertTrue(
            date.matches(Regex("""\d{8}T\d{6}Z""")),
            "X-Amz-Date should be ISO basic UTC, was $date",
        )

        val signature = url.queryParameter("X-Amz-Signature")
        assertNotNull(signature, "X-Amz-Signature missing")
        assertTrue(
            signature.matches(Regex("""[0-9a-f]{64}""")),
            "signature should be 64 hex chars, was $signature",
        )
    }

    @Test
    fun `digitalocean endpoint signs and sends acl plus cache-control`() {
        val captured = AtomicReference<Request>()
        S3Uploader.put(
            http = captureClient(captured),
            creds = doCreds,
            config = doConfig,
            key = "talon/foo.png",
            bytes = "hello".toByteArray(),
            contentType = "image/png",
        )

        val req = captured.get()
        assertNotNull(req)

        // DO Spaces silently uploads private without these headers.
        assertEquals("public-read", req.header("x-amz-acl"))
        assertEquals("public, max-age=3600", req.header("Cache-Control"))

        val signedHeaders = req.url.queryParameter("X-Amz-SignedHeaders")
        // The header set must be lexicographic and include exactly the
        // four we sign for DO.
        assertEquals("cache-control;content-type;host;x-amz-acl", signedHeaders)
    }

    @Test
    fun `key is path-encoded so spaces and special chars dont break signing`() {
        val captured = AtomicReference<Request>()
        S3Uploader.put(
            http = captureClient(captured),
            creds = creds,
            config = awsConfig,
            // Real keys don't carry spaces (TlonChatRepo sanitizes via
            // [^A-Za-z0-9._-]_), but the encoder is the load-bearing
            // line — pin it so a future caller that skips the sanitize
            // step still produces a signable URL.
            key = "talon/space file.png",
            bytes = "hello".toByteArray(),
            contentType = "image/png",
        )

        val req = captured.get()
        assertNotNull(req)
        // Space → %20 (NOT '+'); the encoded path must match the
        // canonical URI used in signing or AWS rejects with 403.
        assertEquals("/talon-test/talon/space%20file.png", req.url.encodedPath)
    }
}
