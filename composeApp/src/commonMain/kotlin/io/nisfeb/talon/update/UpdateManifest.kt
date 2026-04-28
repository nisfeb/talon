// TEMPORARY DUPLICATE: ported from
// app/src/main/java/io/nisfeb/talon/update/UpdateManifest.kt during
// the CMP desktop port (Task D4 prerequisite). Pure Kotlin already;
// just relocated into commonMain. Keep in lockstep with the
// production copy until app/ is removed in Stage F.
package io.nisfeb.talon.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
