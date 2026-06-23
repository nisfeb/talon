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
