package io.nisfeb.talon.urbit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS SigV4 PUT for S3-compatible object storage (AWS, Backblaze
 * B2, Cloudflare R2, DigitalOcean Spaces, MinIO). Everything is explicit
 * so Urbit's %storage credentials can drive it directly without pulling
 * in the AWS SDK.
 *
 * Tlon's client uses path-style URLs (`https://endpoint/bucket/key`) —
 * we do the same so unusual endpoints with custom DNS don't break.
 */
object S3Uploader {

    data class Credentials(
        val endpoint: String,       // e.g. "https://s3.us-east-1.amazonaws.com"
        val accessKeyId: String,
        val secretAccessKey: String,
    )

    data class Configuration(
        val bucket: String,
        val region: String,         // e.g. "us-east-1"
        val publicUrlBase: String?, // CDN / custom public prefix, optional
    )

    /**
     * PUT `bytes` to the configured bucket at `key`, return the public URL
     * to reference the object (either publicUrlBase/key or endpoint/bucket/key).
     */
    fun put(
        http: OkHttpClient,
        creds: Credentials,
        config: Configuration,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ): String {
        val endpoint = prefixEndpoint(creds.endpoint).trimEnd('/')
        val url = "$endpoint/${config.bucket}/${encodePath(key)}"
        val host = url.toHttpHost()

        val now = Date()
        val amzDate = AMZ_DATE_FMT.format(now)
        val dateStamp = DATE_STAMP_FMT.format(now)
        val payloadHash = sha256Hex(bytes)

        // Headers we'll sign + send. Lexicographic order matters for the
        // canonical headers block; we sort on the fly.
        val headers = linkedMapOf(
            "cache-control" to "public, max-age=3600",
            "content-type" to contentType,
            "host" to host,
            "x-amz-acl" to "public-read",
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )

        val signedHeaders = headers.keys.sorted().joinToString(";")
        val canonicalHeaders = headers.entries
            .sortedBy { it.key }
            .joinToString("") { "${it.key}:${it.value.trim()}\n" }

        val canonicalUri = "/${config.bucket}/${encodePath(key)}"
        val canonicalRequest = buildString {
            append("PUT\n")
            append("$canonicalUri\n")
            append("\n") // query string
            append(canonicalHeaders)
            append("\n")
            append("$signedHeaders\n")
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/${config.region}/s3/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append("$amzDate\n")
            append("$credentialScope\n")
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val signingKey = deriveSigningKey(creds.secretAccessKey, dateStamp, config.region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=${creds.accessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        val requestBuilder = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> if (k != "host") requestBuilder.header(k, v) }
        requestBuilder.header("Authorization", authorization)

        // Cap the PUT at 60s — the shared client uses readTimeout=0 to
        // hold the SSE channel open, so without an explicit cap an
        // unresponsive S3 endpoint freezes the upload indefinitely.
        http.newCall(requestBuilder.build())
            .apply { timeout().timeout(60, java.util.concurrent.TimeUnit.SECONDS) }
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    error("S3 PUT failed: HTTP ${resp.code}${if (body.isNotBlank()) " — $body" else ""}")
                }
            }

        return publicUrl(config, endpoint, key)
    }

    private fun publicUrl(config: Configuration, endpoint: String, key: String): String {
        val base = config.publicUrlBase?.takeIf { it.isNotBlank() }?.trimEnd('/')
        val encodedKey = encodePath(key)
        return if (base != null) "$base/$encodedKey"
        else "$endpoint/${config.bucket}/$encodedKey"
    }

    /** Percent-encode each path segment (AWS SigV4 unreserved set). Keeps `/` as a separator. */
    private fun encodePath(key: String): String =
        key.split('/').joinToString("/") { segment ->
            buildString {
                for (byte in segment.toByteArray(Charsets.UTF_8)) {
                    val c = byte.toInt() and 0xff
                    val ch = c.toChar()
                    if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                        append(ch)
                    } else {
                        append('%')
                        append(String.format(Locale.US, "%02X", c))
                    }
                }
            }
        }

    private fun prefixEndpoint(endpoint: String): String =
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) endpoint
        else "https://$endpoint"

    private fun String.toHttpHost(): String {
        val stripped = substringAfter("://")
        return stripped.substringBefore('/')
    }

    private fun deriveSigningKey(
        secret: String,
        dateStamp: String,
        region: String,
        service: String,
    ): ByteArray {
        val kDate = hmacSha256(("AWS4$secret").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).toHex()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val chars = "0123456789abcdef".toCharArray()
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(chars[v ushr 4])
            sb.append(chars[v and 0x0f])
        }
        return sb.toString()
    }

    private val AMZ_DATE_FMT = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val DATE_STAMP_FMT = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
