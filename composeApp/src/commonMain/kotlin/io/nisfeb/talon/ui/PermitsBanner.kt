package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nisfeb.talon.urbit.asText
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The apps on this ship's Grubbery whose permissions wait on the owner,
 * by grubbery's own rule on its permits page: an app is pending when it
 * has no approval, or when what it asks for now (its poke, peek and make
 * roads) is not what was approved. [asks] is `asks.json`, [approved] is
 * `approved.json`.
 */
internal fun pendingPermitApps(asks: JsonArray, approved: JsonObject): List<String> {
    fun roads(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty()
        .map { (it as? JsonObject)?.get("road").asText() ?: it.asText().orEmpty() }.sorted()
    return asks.mapNotNull { a ->
        val ask = a as? JsonObject ?: return@mapNotNull null
        val app = ask["app"].asText() ?: return@mapNotNull null
        val declared = (approved[app] as? JsonObject)?.get("declared") as? JsonObject
        val settled = declared != null && listOf("poke", "peek", "make").all { roads(ask[it]) == roads(declared[it]) }
        app.takeUnless { settled }
    }
}

/** The pending apps on [shipUrl], or null where the ship did not say (no Grubbery, signed out, no answer). */
internal suspend fun fetchPendingPermits(http: HttpClient, shipUrl: String): List<String>? = runCatching {
    suspend fun read(name: String): JsonElement? {
        val resp = http.get(shipUrl.trimEnd('/') + "/apps/grubbery/$name")
        return if (resp.status.isSuccess()) Json.parseToJsonElement(resp.bodyAsText()) else null
    }
    val asks = read("asks.json") as? JsonArray ?: return@runCatching null
    val approved = read("approved.json") as? JsonObject ?: return@runCatching null
    pendingPermitApps(asks, approved)
}.getOrNull()

/**
 * A quiet line at the top of the home list while any Grubbery app waits
 * for its permissions: an app that is not approved runs jailed, and
 * nothing else in Talon says so. It has no dismiss; it goes when the
 * approvals are done. Read on first show, when Talon comes back to the
 * front (as it does after approving in the browser), and every half hour.
 */
@Composable
fun PermitsBanner(http: HttpClient?, shipUrl: String?) {
    if (http == null || shipUrl == null) return
    var pending by remember(shipUrl) { mutableStateOf(0) }
    var checkedAt by remember(shipUrl) { mutableLongStateOf(0L) }
    val focused = LocalWindowInfo.current
    LaunchedEffect(http, shipUrl) {
        suspend fun check() {
            checkedAt = nowMs()
            fetchPendingPermits(http, shipUrl)?.let { pending = it.size }
        }
        launch {
            // Back at the front: read again, but not on every alt-tab.
            snapshotFlow { focused.isWindowFocused }.collect { f ->
                if (f && nowMs() - checkedAt > FOCUS_RECHECK_MS) check()
            }
        }
        while (true) {
            if (nowMs() - checkedAt > FOCUS_RECHECK_MS) check()
            delay(RECHECK_MS)
        }
    }
    if (pending == 0) return
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                // Read again on the way back, whenever that is.
                checkedAt = 0L
                runCatching { uriHandler.openUri(permitsUrl(shipUrl)) }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (pending == 1) "1 app needs its permissions approved." else "$pending apps need their permissions approved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Review",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ponytail: fixed intervals; a push from the ship when grubbery offers one.
private const val RECHECK_MS = 30L * 60 * 1000
private const val FOCUS_RECHECK_MS = 60L * 1000
