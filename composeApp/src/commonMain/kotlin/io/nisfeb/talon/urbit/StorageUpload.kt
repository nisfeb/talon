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
 * The name an upload goes up under: 128 random bits and the file's
 * extension, which some clients read to show an image inline.
 *
 * The object is public to anyone with its URL, and the key used to be
 * `talon/<upload ms>-<file name>`. The name said what it was
 * (`IMG_20261011_1430.jpg`, `passport.pdf`), and the time made the
 * rest guessable: every iOS photo is `photo.jpg`, so a minute's
 * uploads were 60,000 tries away. The real name goes in the message,
 * where only the chat sees it (2026-10-11). It also cannot be too
 * long for a filesystem, which a long name once was.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun uploadName(fileName: String, contentType: String): String {
    fun ext(s: String) = s.lowercase().takeIf { it.length in 1..8 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }
    val ext = ext(fileName.substringAfterLast('.', "")) ?: ext(contentType.substringAfter('/', ""))
    val id = kotlin.uuid.Uuid.random().toHexString()
    return if (ext == null) id else "$id.$ext"
}

/**
 * An upload neither route took. [message] is a sentence for the person
 * who tried; what each route said is in [cause], for "Copy error
 * details". The line used to be the routes' own text, S3's XML and all:
 * "image failed: image upload failed: memex=memex upload-url failed:
 * HTTP 500; storage=S3 PUT failed: HTTP 403 — <?xml …".
 */
class UploadFailed(message: String, cause: Throwable) : Exception(message, cause)

/** What [memex] and [storage] failing to take one upload says, plainly. */
internal fun uploadFailureLine(memex: Throwable?, storage: Throwable?): String {
    fun status(t: Throwable?) = t?.message?.let { Regex("HTTP (\\d{3})").find(it)?.groupValues?.get(1)?.toInt() }
    val hosting = when {
        memex == null || memex.message.orEmpty().startsWith("no memex token") -> null
        io.nisfeb.talon.util.isTransientNetworkError(memex) -> "Tlon's image hosting didn't answer"
        (status(memex) ?: 0) >= 500 -> "Tlon's image hosting had a problem on its side"
        status(memex) != null -> "Tlon's image hosting refused it"
        else -> "Tlon's image hosting couldn't take it"
    }
    val own = when {
        storage == null || storage.message.orEmpty().startsWith("no %storage") -> null
        status(storage) == 401 || status(storage) == 403 ->
            "your ship's storage turned down its keys (see Storage in Landscape's settings)"
        io.nisfeb.talon.util.isTransientNetworkError(storage) -> "your ship's storage didn't answer"
        else -> "your ship's storage couldn't take it"
    }
    val why = listOfNotNull(hosting, own)
    return if (why.isEmpty()) "The upload didn't go through: this ship has no image storage set up."
    else "The upload didn't go through: ${why.joinToString(", and ")}."
}
