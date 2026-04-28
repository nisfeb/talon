package io.nisfeb.talon.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The shape both update channels resolve to. Backed by the same
 * latest.json hosted on GitHub Releases (Channel 1) and pushed by
 * the talon-updates Urbit agent (Channel 2).
 *
 * url + sha256 are belt-and-braces: TLS protects the download, but
 * the hash also catches mid-air swap if a user routes through a
 * sketchy proxy or ends up with a partial/cached download.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val minSdk: Int,
    val changelog: String,
    val mandatory: Boolean,
) {
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

        fun parse(raw: String): UpdateManifest? = runCatching {
            val obj = JSON.parseToJsonElement(raw).jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            // Defense in depth: never accept a non-HTTPS URL even
            // if the manifest came from a "trusted" source.
            if (!url.startsWith("https://")) return@runCatching null
            val sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            if (!SHA256_RE.matches(sha256)) return@runCatching null
            UpdateManifest(
                versionCode = obj["versionCode"]?.jsonPrimitive?.intOrNull
                    ?: return@runCatching null,
                versionName = obj["versionName"]?.jsonPrimitive?.contentOrNull
                    ?: return@runCatching null,
                url = url,
                sha256 = sha256.lowercase(),
                minSdk = obj["minSdk"]?.jsonPrimitive?.intOrNull ?: 26,
                changelog = obj["changelog"]?.jsonPrimitive?.contentOrNull ?: "",
                mandatory = obj["mandatory"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }
}
