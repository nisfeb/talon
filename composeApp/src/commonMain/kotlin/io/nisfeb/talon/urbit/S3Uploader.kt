package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

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
 * Crypto/date are multiplatform: okio for SHA-256 + HMAC-SHA256 and
 * kotlinx-datetime for the UTC ISO-basic timestamps, so this signs
 * identically on Android, JVM, and iOS with no expect/actual split.
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
    suspend fun put(
        http: HttpClient,
        creds: Credentials,
        config: Configuration,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ): String = withContext(ioDispatcher) {
        val endpoint = prefixEndpoint(creds.endpoint).trimEnd('/')
        val baseUrl = "$endpoint/${config.bucket}/${encodePath(key)}"
        val host = baseUrl.toHttpHost()

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
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
            append(sha256Hex(canonicalRequest.encodeToByteArray()))
        }

        val signingKey = deriveSigningKey(creds.secretAccessKey, dateStamp, config.region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val presignedUrl = "$baseUrl?$canonicalQuery&X-Amz-Signature=$signature"

        // Cap the PUT at 60s — the shared client uses no read timeout to
        // hold the SSE channel open, so without an explicit cap an
        // unresponsive S3 endpoint freezes the upload indefinitely.
        val resp = http.put(presignedUrl) {
            contentType(ContentType.parse(contentType))
            // Only echo headers on the wire for DigitalOcean. Auth is in
            // the query string; no Authorization header anywhere. Skip
            // content-type here — it's already set from the body above.
            extraHeaders.forEach { (k, v) -> if (k != "content-type") header(k, v) }
            setBody(bytes)
            timeout { requestTimeoutMillis = 60_000 }
        }
        if (!resp.status.isSuccess()) {
            val body = resp.bodyAsText()
            error("S3 PUT failed: HTTP ${resp.status.value}${if (body.isNotBlank()) " — $body" else ""}")
        }

        publicUrl(config, endpoint, key)
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
     * ASCII-only unreserved set (not Char.isLetterOrDigit, which would
     * treat Latin-1 high bytes as letters and leave them unencoded).
     */
    private fun uriEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                append(ch)
            } else {
                append('%')
                append(HEX_UPPER[c ushr 4])
                append(HEX_UPPER[c and 0x0f])
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
        val kDate = hmacSha256(("AWS4$secret").encodeToByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray =
        data.encodeUtf8().hmacSha256(key.toByteString()).toByteArray()

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        data.encodeUtf8().hmacSha256(key.toByteString()).hex()

    private fun sha256Hex(bytes: ByteArray): String =
        bytes.toByteString().sha256().hex()

    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    private val AMZ_DATE_FMT = LocalDateTime.Format {
        year(); monthNumber(); dayOfMonth()
        char('T')
        hour(); minute(); second()
        char('Z')
    }
    private val DATE_STAMP_FMT = LocalDateTime.Format {
        year(); monthNumber(); dayOfMonth()
    }
}
