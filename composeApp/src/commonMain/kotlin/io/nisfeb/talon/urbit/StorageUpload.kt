package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parsing + the upload-mode decision for the %storage agent's S3 config.
 *
 * Talon uploads via a direct AWS SigV4 PUT (see [S3Uploader]) whenever
 * the agent exposes full credentials, mirroring tlon-apps' `hasCustomS3Creds`
 * (packages/shared/src/store/storage/storageUtils.ts). The agent's
 * `service` field (`credentials` | `presigned-url`) only steers
 * *Tlon-hosted* ships to the memex uploader — which [TlonChatRepo]
 * already tries first. For a self-hosted ship the reference client
 * ignores `service` and signs directly, so we must NOT gate on it: a
 * ship with valid credentials but `service` set to `presigned-url` (the
 * ~dinnyt-divsud report) uploads fine once we stop rejecting it.
 */
internal data class StorageCreds(
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

internal data class StorageConfig(
    val bucket: String,
    val region: String,
    val publicUrlBase: String?,
    /** `credentials` | `presigned-url` | null. Informational only — does
     *  not gate the credentials upload path; see [storageS3Ready]. */
    val service: String?,
)

internal fun parseStorageCredentials(body: JsonElement?): StorageCreds? {
    // %storage returns {"storage-update": {"credentials": {...}}}; field
    // names may be kebab-case or camelCase depending on the agent version.
    val obj = (body as? JsonObject) ?: return null
    val inner = (obj["storage-update"] as? JsonObject)?.get("credentials") as? JsonObject
        ?: (obj["credentials"] as? JsonObject)
        ?: obj
    val endpoint = inner["endpoint"].asStr() ?: ""
    val accessKeyId = inner["access-key-id"].asStr()
        ?: inner["accessKeyId"].asStr()
        ?: ""
    val secretAccessKey = inner["secret-access-key"].asStr()
        ?: inner["secretAccessKey"].asStr()
        ?: ""
    return StorageCreds(endpoint, accessKeyId, secretAccessKey)
}

internal fun parseStorageConfiguration(body: JsonElement?): StorageConfig? {
    val obj = (body as? JsonObject) ?: return null
    val inner = (obj["storage-update"] as? JsonObject)?.get("configuration") as? JsonObject
        ?: (obj["configuration"] as? JsonObject)
        ?: obj
    val bucket = inner["current-bucket"].asStr()
        ?: inner["currentBucket"].asStr()
        ?: ""
    val region = inner["region"].asStr() ?: ""
    val publicUrlBase = inner["public-url-base"].asStr()
        ?: inner["publicUrlBase"].asStr()
    val service = inner["service"].asStr()
    return StorageConfig(bucket, region, publicUrlBase, service)
}

/**
 * True when the agent exposes everything [S3Uploader] needs for a direct
 * SigV4 PUT — endpoint, access key, secret, and a bucket. Deliberately
 * independent of [StorageConfig.service]: the credentials path is valid
 * in either mode, and gating on `service == "credentials"` was the bug
 * that broke uploads for ships left in `presigned-url` mode.
 */
internal fun storageS3Ready(creds: StorageCreds, config: StorageConfig): Boolean =
    creds.endpoint.isNotBlank() &&
        creds.accessKeyId.isNotBlank() &&
        creds.secretAccessKey.isNotBlank() &&
        config.bucket.isNotBlank()

/**
 * The longest object name any backend here will accept.
 *
 * ENAMETOOLONG (os error 36) is a *filesystem* limit on one path
 * component — 255 bytes on ext4 and most others — and both upload
 * backends land the object on a filesystem eventually. It bit a real
 * user as an HTTP 500 from S3 with `Filename too long`, after memex
 * had already 500'd on the same name, so neither path can be treated
 * as the safe one.
 *
 * 255 is the ceiling, not the budget. The S3 key prepends
 * `talon/<@da>-`, and a @da is a ~39-digit decimal, so roughly 40 of
 * those bytes are gone before the name starts; memex builds a key of
 * its own that we cannot see at all. Hence generous headroom rather
 * than shaving to the theoretical maximum — a name this long is
 * already unreadable, and the bytes buy nothing. StorageUploadTest
 * pins the real key against the 255-byte limit so this stays true.
 */
internal const val MAX_UPLOAD_NAME_BYTES = 160

/** Longest extension worth preserving; beyond this it isn't one. */
private const val MAX_EXT_BYTES = 16

/**
 * Trim [fileName] to [maxBytes] of UTF-8, keeping its extension.
 *
 * Bytes rather than characters, because the limit being enforced
 * downstream is a byte count: a name of 200 CJK characters is 600
 * bytes and would fail a check that only counted 200.
 *
 * The extension is what survives truncation, since that is what tells
 * a viewer (and a content sniffer) what the file is. No hash is mixed
 * in to keep truncated names distinct: the S3 key already carries a
 * timestamp, and memex mints its own key, so two long names cannot
 * collide by being shortened here.
 */
internal fun truncateUploadName(
    fileName: String,
    maxBytes: Int = MAX_UPLOAD_NAME_BYTES,
): String {
    // Any directory part is not ours to send — and a stray separator
    // would turn one over-long component into two path segments.
    val name = fileName.substringAfterLast('/').substringAfterLast('\\')
    if (name.isEmpty()) return "file"
    if (name.utf8Size() <= maxBytes) return name

    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && name.length - dot - 1 > 0 &&
        name.substring(dot).utf8Size() <= MAX_EXT_BYTES
    ) {
        name.substring(dot)
    } else {
        ""
    }
    val stem = if (ext.isEmpty()) name else name.substring(0, dot)
    val kept = stem.takeUtf8Bytes(maxBytes - ext.utf8Size())
    // A name that is nothing but an over-long extension still has to
    // go somewhere; give it a stem rather than returning a bare dot.
    return if (kept.isEmpty()) "file".plus(ext) else kept + ext
}

/** UTF-8 length without allocating the encoded array. */
private fun String.utf8Size(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            n += 4
            i += 2
        } else {
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            i++
        }
    }
    return n
}

/**
 * The longest prefix of this string that fits [max] UTF-8 bytes,
 * never splitting a character or a surrogate pair — a half-written
 * code point is how truncation turns a name into replacement glyphs.
 */
private fun String.takeUtf8Bytes(max: Int): String {
    if (max <= 0) return ""
    var used = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        val pair = c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()
        val size = when {
            pair -> 4
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
        if (used + size > max) break
        used += size
        i += if (pair) 2 else 1
    }
    return substring(0, i)
}
