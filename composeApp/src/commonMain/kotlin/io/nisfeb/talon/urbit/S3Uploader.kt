package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.Log
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
 * Minimal AWS SigV4 presigned PUT for S3-compatible object storage
 * (AWS, Backblaze B2, Cloudflare R2, DigitalOcean Spaces, MinIO).
 * Mirrors what `tlon-apps/packages/api/src/client/storageApi.ts` does
 * via the AWS SDK so any %storage config that already works with the
 * Tlon webapp also works here.
 *
 * Path-style URLs (`https://endpoint/bucket/key`); auth lives in the
 * query string (`X-Amz-Algorithm`, `X-Amz-Credential`, …, `X-Amz-Signature`),
 * so the actual PUT goes out with no `Authorization` header — that
 * stops CDNs/proxies in front of S3 from stripping or rejecting it.
 * For non-DO endpoints we sign only `host`; the body is sent with
 * `UNSIGNED-PAYLOAD` and no `Cache-Control` / `x-amz-acl` headers
 * (matches Tlon's "headers only for digitaloceanspaces.com" carve-out
 * — sending those to an AWS bucket fronted by a CDN tends to come
 * back as 502 Bad Gateway).
 *
 * TODO(port/ios): this file uses java.security.MessageDigest,
 * javax.crypto.{Mac, SecretKeySpec}, java.text.SimpleDateFormat, and
 * java.util.TimeZone — none of which exist on Kotlin/Native (iOS).
 * When iOS is added as a target, the SigV4 signing path needs an
 * expect/actual split (or a multiplatform crypto library substitution
 * like the kotlinx-crypto project).
 */
object S3Uploader {

    private const val TAG = "S3Uploader"

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
        val baseUrl = "$endpoint/${config.bucket}/${encodePath(key)}"
        val host = baseUrl.toHttpHost()

        val now = Date()
        val amzDate = AMZ_DATE_FMT.format(now)
        val dateStamp = DATE_STAMP_FMT.format(now)

        // DigitalOcean Spaces requires `Cache-Control` / `Content-Type` /
        // `x-amz-acl: public-read` on the wire and in the signed-headers
        // set; without them, the put silently uploads as private. The
        // tlon-apps client special-cases the same hostname; we match.
        // For everything else (AWS, B2, R2, Spaces, MinIO behind a CDN),
        // sign only `host` and PUT bare — that's what gets the same
        // upload through proxies that 502 on Cache-Control/x-amz-acl.
        val isDigitalOcean = host.contains("digitaloceanspaces.com")
        val extraHeaders: Map<String, String> = if (isDigitalOcean) linkedMapOf(
            "cache-control" to "public, max-age=3600",
            "content-type" to contentType,
            "x-amz-acl" to "public-read",
        ) else emptyMap()

        val signedHeaderNames = (listOf("host") + extraHeaders.keys).sorted()
        val signedHeaders = signedHeaderNames.joinToString(";")
        val credentialScope = "$dateStamp/${config.region}/s3/aws4_request"
        val credential = "${creds.accessKeyId}/$credentialScope"

        // Canonical query: each key/value URI-encoded (RFC 3986
        // unreserved set, `/` IS encoded inside Credential), sorted by
        // encoded key, joined with `&`. X-Amz-Signature is appended
        // after signing — it isn't part of the canonical request.
        val queryParams = linkedMapOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to credential,
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to "3600",
            "X-Amz-SignedHeaders" to signedHeaders,
        )
        val canonicalQuery = queryParams.entries
            .map { (k, v) -> "${uriEncode(k)}=${uriEncode(v)}" }
            .sorted()
            .joinToString("&")

        val canonicalHeaders = signedHeaderNames.joinToString("") { name ->
            val v = if (name == "host") host else extraHeaders.getValue(name)
            "$name:${v.trim()}\n"
        }

        val canonicalUri = "/${config.bucket}/${encodePath(key)}"
        val canonicalRequest = buildString {
            append("PUT\n")
            append("$canonicalUri\n")
            append("$canonicalQuery\n")
            append(canonicalHeaders)
            append("\n")
            append("$signedHeaders\n")
            append("UNSIGNED-PAYLOAD")
        }

        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append("$amzDate\n")
            append("$credentialScope\n")
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val signingKey = deriveSigningKey(creds.secretAccessKey, dateStamp, config.region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val presignedUrl = "$baseUrl?$canonicalQuery&X-Amz-Signature=$signature"

        // Diagnostic: log the redacted URL + canonical request shape
        // before sending so a 502/403 trace can be cross-checked
        // against the AWS spec without reproducing locally. Strips
        // the actual signature and access-key id; everything else is
        // ship-public state already on the wire.
        val redactedUrl = presignedUrl.replace(signature, "<sig>")
            .replace(creds.accessKeyId, "<key>")
        Log.i(TAG, "S3 PUT $redactedUrl  host=$host  bytes=${bytes.size}")
        Log.i(TAG, "S3 canonicalRequest:\n$canonicalRequest")

        val requestBuilder = Request.Builder()
            .url(presignedUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
        // Only echo headers on the wire for DigitalOcean. Auth is in
        // the query string; no Authorization header anywhere.
        extraHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        // Cap the PUT at 60s — the shared client uses readTimeout=0 to
        // hold the SSE channel open, so without an explicit cap an
        // unresponsive S3 endpoint freezes the upload indefinitely.
        http.newCall(requestBuilder.build())
            .apply { timeout().timeout(60, java.util.concurrent.TimeUnit.SECONDS) }
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    Log.w(TAG, "S3 PUT ${resp.code} response body: $body")
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
        key.split('/').joinToString("/") { uriEncode(it) }

    /**
     * Percent-encode a single string per AWS SigV4 (RFC 3986 unreserved
     * set: A–Z, a–z, 0–9, `-`, `_`, `.`, `~`). Used for canonical query
     * keys/values and for path segments — `/` is NOT preserved here, so
     * callers that need a path use `encodePath` to keep separators raw.
     */
    private fun uriEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
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
