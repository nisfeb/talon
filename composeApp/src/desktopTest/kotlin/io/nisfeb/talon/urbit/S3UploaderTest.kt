package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the wire shape of [S3Uploader.put]. The signing code is
 * hand-rolled and any drift from AWS SigV4 surfaces here rather than
 * at upload time. We don't talk to a real bucket — a Ktor [MockEngine]
 * captures the outgoing request and short-circuits with 200.
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

    private fun captureClient(slot: Array<HttpRequestData?>) =
        HttpClient(MockEngine { request ->
            slot[0] = request
            respond("", HttpStatusCode.OK)
        })

    @Test
    fun `aws endpoint puts via presigned url with no auth header and no acl headers`() = runBlocking {
        val slot = arrayOfNulls<HttpRequestData>(1)
        S3Uploader.put(
            http = captureClient(slot),
            creds = creds,
            config = awsConfig,
            key = "talon/foo.png",
            bytes = "hello".encodeToByteArray(),
            contentType = "image/png",
        )

        val req = slot[0]
        assertNotNull(req, "mock engine never fired")

        // Auth must be in the query string, not in headers.
        assertNull(req.headers["Authorization"], "presigned PUT must not carry Authorization")

        // Public-read / cache-control belong to the DO carve-out only.
        assertNull(req.headers["x-amz-acl"], "x-amz-acl on AWS endpoint trips proxies (502)")
        assertNull(req.headers["Cache-Control"], "Cache-Control on AWS endpoint trips proxies (502)")

        val url = req.url
        assertEquals(HttpMethod.Put, req.method)
        assertEquals("/talon-test/talon/foo.png", url.encodedPath)
        assertEquals("AWS4-HMAC-SHA256", url.parameters["X-Amz-Algorithm"])
        assertEquals("3600", url.parameters["X-Amz-Expires"])
        assertEquals("host", url.parameters["X-Amz-SignedHeaders"])

        val credential = url.parameters["X-Amz-Credential"]
        assertNotNull(credential, "X-Amz-Credential missing")
        assertTrue(
            credential.startsWith("test-access-key/"),
            "credential should start with access-key-id, was $credential",
        )
        assertTrue(credential.endsWith("/us-east-1/s3/aws4_request"))

        val date = url.parameters["X-Amz-Date"]
        assertNotNull(date)
        assertTrue(
            date.matches(Regex("""\d{8}T\d{6}Z""")),
            "X-Amz-Date should be ISO basic UTC, was $date",
        )

        val signature = url.parameters["X-Amz-Signature"]
        assertNotNull(signature, "X-Amz-Signature missing")
        assertTrue(
            signature.matches(Regex("""[0-9a-f]{64}""")),
            "signature should be 64 hex chars, was $signature",
        )
    }

    @Test
    fun `digitalocean endpoint signs and sends acl plus cache-control`() = runBlocking {
        val slot = arrayOfNulls<HttpRequestData>(1)
        S3Uploader.put(
            http = captureClient(slot),
            creds = doCreds,
            config = doConfig,
            key = "talon/foo.png",
            bytes = "hello".encodeToByteArray(),
            contentType = "image/png",
        )

        val req = slot[0]
        assertNotNull(req)

        // DO Spaces silently uploads private without these headers.
        assertEquals("public-read", req.headers["x-amz-acl"])
        assertEquals("public, max-age=3600", req.headers["Cache-Control"])

        val signedHeaders = req.url.parameters["X-Amz-SignedHeaders"]
        // The header set must be lexicographic and include exactly the
        // four we sign for DO.
        assertEquals("cache-control;content-type;host;x-amz-acl", signedHeaders)
    }

    @Test
    fun `key is path-encoded so spaces and special chars dont break signing`() = runBlocking {
        val slot = arrayOfNulls<HttpRequestData>(1)
        S3Uploader.put(
            http = captureClient(slot),
            creds = creds,
            config = awsConfig,
            // Real keys don't carry spaces (TlonChatRepo sanitizes via
            // [^A-Za-z0-9._-]_), but the encoder is the load-bearing
            // line — pin it so a future caller that skips the sanitize
            // step still produces a signable URL.
            key = "talon/space file.png",
            bytes = "hello".encodeToByteArray(),
            contentType = "image/png",
        )

        val req = slot[0]
        assertNotNull(req)
        // Space → %20 (NOT '+'); the encoded path must match the
        // canonical URI used in signing or AWS rejects with 403.
        assertEquals("/talon-test/talon/space%20file.png", req.url.encodedPath)
    }

    // An endpoint is not always a bare host. A ship serving Jars sits
    // at https://ship/jars, and that prefix is in the URL the server
    // reads: it signed /jars/default/<key> while we had signed
    // /default/<key>, and answered 403 to every upload. Issue #17.
    @Test
    fun `an endpoint with a path of its own signs the path it sends`() = runBlocking {
        val slot = arrayOfNulls<HttpRequestData>(1)
        val url = S3Uploader.put(
            http = captureClient(slot),
            creds = creds.copy(endpoint = "https://my.ship/jars"),
            config = S3Uploader.Configuration(bucket = "default", region = "us-east-1", publicUrlBase = null),
            key = "talon/foo.png",
            bytes = "hello".encodeToByteArray(),
            contentType = "image/png",
        )

        val req = slot[0]
        assertNotNull(req)
        assertEquals("/jars/default/talon/foo.png", req.url.encodedPath)
        // What is signed is read off the URL that is sent, so the two
        // cannot drift; this is that same reading, from the outside.
        assertEquals(
            req.url.encodedPath,
            S3Uploader.canonicalPathOf(req.url.toString()),
            "the signed path is the sent path",
        )
        // The address handed back is under the prefix too, or the
        // picture uploads and then cannot be fetched.
        assertEquals("https://my.ship/jars/default/talon/foo.png", url)
    }

    @Test
    fun `a path is everything after the host, without the query`() {
        assertEquals("/bucket/k.png", S3Uploader.canonicalPathOf("https://s3.example.com/bucket/k.png"))
        assertEquals("/jars/default/k.png", S3Uploader.canonicalPathOf("https://my.ship/jars/default/k.png"))
        assertEquals("/a/b/c/bucket/k.png", S3Uploader.canonicalPathOf("https://my.ship/a/b/c/bucket/k.png"))
        assertEquals("/bucket/k.png", S3Uploader.canonicalPathOf("https://s3.example.com/bucket/k.png?X-Amz-Date=x"))
        assertEquals("/bucket/sp%20ace.png", S3Uploader.canonicalPathOf("https://s3.example.com/bucket/sp%20ace.png"))
        // A host with nothing after it has no path, which is a slash.
        assertEquals("/", S3Uploader.canonicalPathOf("https://s3.example.com"))
    }
}
