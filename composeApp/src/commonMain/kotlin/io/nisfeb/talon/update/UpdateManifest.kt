package io.nisfeb.talon.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One downloadable build: where it is and what it hashes to. */
data class UpdateAsset(val url: String, val sha256: String)

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val minSdk: Int,
    val changelog: String,
    val mandatory: Boolean,
    /** Desktop installers by kind: appimage, deb, dmg, msi. Added by
     *  the release job once they are built; a manifest from before
     *  that has none, and desktop falls back to the releases page. */
    val desktop: Map<String, UpdateAsset> = emptyMap(),
    /** Android APKs by ABI (arm64-v8a, armeabi-v7a, x86_64); [url]
     *  and [sha256] are the universal one every device can take. */
    val android: Map<String, UpdateAsset> = emptyMap(),
) {
    /**
     * The APK for a device that runs [supportedAbis], best first: the
     * split for its own architecture is a fraction of the universal
     * one, which is only the answer when no split fits.
     */
    fun androidAssetFor(supportedAbis: List<String>): UpdateAsset =
        supportedAbis.firstNotNullOfOrNull { android[it] } ?: UpdateAsset(url, sha256)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

        fun parse(raw: String): UpdateManifest? = runCatching {
            val obj = JSON.parseToJsonElement(raw).jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
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
                desktop = assets(obj["desktop"]),
                android = assets(obj["android"]),
            )
        }.getOrNull()

        /** A map of assets, keeping only the ones that check out. */
        private fun assets(el: JsonElement?): Map<String, UpdateAsset> =
            (el as? JsonObject).orEmpty().mapNotNull { (key, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val h = o["sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!u.startsWith("https://") || !SHA256_RE.matches(h)) return@mapNotNull null
                key to UpdateAsset(u, h.lowercase())
            }.toMap()
    }
}
