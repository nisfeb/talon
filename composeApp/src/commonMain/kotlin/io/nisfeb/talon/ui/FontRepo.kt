package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The ship answered that it has no grubbery to keep files in. */
class NoGrubbery : Exception("Your ship has no grubbery to keep fonts in.")

/**
 * The owner's fonts on their ship, in grubbery's file store under
 * `talon/fonts`: `<id>.font` for one Talon put there, the file's own
 * name for one the owner put there through grubbery's page. Private: grubbery serves them only with the
 * owner's cookie, and these are their own copies of fonts they may have
 * bought, not something to put at a public address.
 *
 * The routes are grubbery's ball API (lib/ball-api.hoon): PUT /dir and
 * PUT /file create (409 when already there, which for a file named by
 * its contents' hash is the same file), GET /file reads, DELETE /file
 * removes. A ship without grubbery answers 404 to all of them.
 */
class FontShip(
    private val http: HttpClient,
    private val shipUrl: () -> String?,
    private val cookie: () -> String?,
) {
    private suspend fun send(method: HttpMethod, path: String, body: ByteArray? = null): Pair<Int, ByteArray> {
        val base = shipUrl()?.trimEnd('/') ?: error("Not signed in to a ship.")
        val resp = http.request("$base/grubbery/api/$path") {
            this.method = method
            cookie()?.let { header(HttpHeaders.Cookie, it) }
            if (body != null) setBody(body)
        }
        return resp.status.value to resp.readRawBytes()
    }

    /** Put a font's file on the ship as [name]; already there is as good. */
    suspend fun put(name: String, bytes: ByteArray) {
        for (dir in listOf("talon", "talon/fonts")) {
            val (status, _) = send(HttpMethod.Put, "dir/$dir")
            if (status == 404) throw NoGrubbery()
            check(status in 200..299 || status == 409) { "The ship would not make a place for fonts." }
        }
        val (status, _) = send(HttpMethod.Put, "file/talon/fonts/$name", bytes)
        if (status == 404) throw NoGrubbery()
        check(status in 200..299 || status == 409) { "The ship would not keep the font." }
    }

    /**
     * The files in the ship's talon/fonts, by name: one small request
     * (GET /kids, `{"files":[...],"dirs":[...]}`), where reading each file
     * to learn whether it is there would fetch every font every time.
     */
    suspend fun names(): Set<String> {
        val (status, bytes) = send(HttpMethod.Get, "kids/talon/fonts")
        if (status == 404) return emptySet()
        check(status in 200..299) { "The ship would not list its fonts." }
        val files = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString())
            .let { it as? kotlinx.serialization.json.JsonObject }?.get("files") as? kotlinx.serialization.json.JsonArray
        return files.orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
    }

    /** A font's file from the ship, or null where it has none. */
    suspend fun get(name: String): ByteArray? {
        val (status, bytes) = send(HttpMethod.Get, "file/talon/fonts/$name")
        if (status == 404) return null
        check(status in 200..299) { "The ship would not give the font." }
        return bytes
    }

    suspend fun delete(name: String) {
        val (status, _) = send(HttpMethod.Delete, "file/talon/fonts/$name")
        check(status in 200..299 || status == 404) { "The ship would not remove the font." }
    }
}

/**
 * Installing, removing and fetching fonts, on [scope] so leaving the
 * screen midway finishes the job. The list and the choice travel in the
 * synced [UiSettings.fontSettings]; the files travel through [ship].
 */
class FontRepo(
    private val settings: UiSettings,
    private val ship: FontShip,
    private val scope: CoroutineScope,
    val files: FontFiles = FontFiles.default,
) {
    private val _status = MutableStateFlow<String?>(null)
    /** What happened to the last thing asked, in words; null when there is nothing to say. */
    val status: StateFlow<String?> = _status.asStateFlow()
    private val lock = Mutex()

    /**
     * A file the owner picked, installed here at once and shared through
     * the ship behind it. Refused, in words, if it is not a font this
     * app can set text in.
     */
    fun install(bytes: ByteArray, fileName: String) {
        val info = readFontInfo(bytes).getOrElse { _status.value = it.message; return }
        val id = fontId(bytes)
        if (!keep(id, bytes)) {
            _status.value = "That font would not load here, so it was not installed."
            return
        }
        val font = InstalledFont(id, info.family, info.weight, info.italic, fileName)
        val before = settings.fontSettings.value
        val after = before.adding(font).let { it.copy(family = it.family ?: info.family) }
        // A new file for a weight and style already installed replaces it.
        (before.fonts - after.fonts.toSet()).forEach { files.delete(it.id) }
        settings.setFontSettings(after)
        _status.value = "Sharing ${info.family} with your other devices…"
        scope.launch {
            _status.value = runCatching { lock.withLock { ship.put(font.shipFile, bytes) } }.fold(
                onSuccess = { "${info.family} is installed on all your devices." },
                onFailure = {
                    if (it is NoGrubbery) "${info.family} is installed on this device only: ${it.message}"
                    else "${info.family} is installed here; it was not shared yet" +
                        (io.nisfeb.talon.util.readableReason(it.message)?.let { r -> " ($r)" } ?: "") +
                        ". It is tried again next time Talon starts."
                },
            )
        }
    }

    /** A family taken off this device, every other one, and the ship. */
    fun remove(family: String) {
        val before = settings.fontSettings.value
        val gone = before.fonts.filter { it.family == family }
        settings.setFontSettings(before.removingFamily(family))
        gone.forEach { files.delete(it.id) }
        _status.value = null
        scope.launch {
            gone.forEach { f -> runCatching { lock.withLock { ship.delete(f.shipFile) } } }
        }
    }

    /**
     * Bring this device in line with the list: fetch what another device
     * installed, share what this one has that the ship may not, drop the
     * files of fonts removed elsewhere, and take up fonts on the ship the
     * list does not name. Run on start and when the list changes. A file
     * fetched is kept only if it is the one listed.
     */
    suspend fun sync(): Int = lock.withLock {
        val s = settings.fontSettings.value
        for (id in s.removed) if (files.has(id)) files.delete(id)
        // Asked once; a ship that will not say is treated as having none.
        val onShip = runCatching { ship.names() }.getOrDefault(emptySet())
        var missing = 0
        for (f in s.fonts) {
            if (files.has(f.id)) {
                // Installed here, not yet on the ship: shared now.
                if (f.shipFile !in onShip) runCatching { ship.put(f.shipFile, files.read(f.id) ?: return@runCatching) }
                continue
            }
            val got = if (f.shipFile in onShip) runCatching { ship.get(f.shipFile) }.getOrNull() else null
            if (got == null || fontId(got) != f.id) { missing++; continue }
            if (!keep(f.id, got)) missing++
        }
        takeUp(onShip - s.fonts.map { it.shipFile }.toSet(), s)
        _status.value = if (missing == 0) null
        else "$missing font file${if (missing == 1) "" else "s"} could not be fetched from your ship yet; text uses the system font until ${if (missing == 1) "it arrives" else "they arrive"}."
        io.nisfeb.talon.util.Log.i("FontRepo", "fonts: ${s.fonts.size} listed, ${s.fonts.size - missing} here, $missing missing")
        missing
    }

    /**
     * [sync] until every listed font is here, waiting longer between
     * tries. A fetch that failed (a busy ship at start, a dropped
     * request) was not tried again until the list changed or the app
     * restarted, while the page said the font would arrive.
     */
    suspend fun keepInLine(waits: List<Long> = RETRY_WAITS_MS) {
        var i = 0
        while (sync() > 0) {
            kotlinx.coroutines.delay(waits[minOf(i, waits.lastIndex)])
            i++
        }
    }

    /**
     * Fonts in the ship's talon/fonts the list does not name: put there
     * through grubbery's own page, or by a device whose list never got
     * here. They were never offered. Each is fetched, checked, and added.
     * Never one the owner removed (a removal is not undone by a copy left
     * behind), never in place of one listed for the same family, weight
     * and style (two such files would take turns removing each other),
     * and nothing on the ship is deleted here.
     */
    /** [bytes] kept here as [id] when the platform can load them; false, and nothing kept, when not. */
    private fun keep(id: String, bytes: ByteArray): Boolean {
        files.write(id, bytes)
        if (fontLoads(files.path(id).toString())) return true
        files.delete(id)
        return false
    }

    private suspend fun takeUp(names: Set<String>, s: FontSettings) {
        val found = mutableListOf<InstalledFont>()
        for (name in names) {
            if (!FONT_FILE.matches(name)) continue
            if (name.endsWith(".font") && name.removeSuffix(".font") in s.removed) continue
            // ponytail: a file named by hand is fetched to learn its id on
            // every start while it is not taken up (removed, or a duplicate);
            // remember name to id if those pile up.
            val bytes = runCatching { ship.get(name) }.getOrNull() ?: continue
            val id = fontId(bytes)
            if (id in s.removed || (s.fonts + found).any { it.id == id }) continue
            val info = readFontInfo(bytes).getOrNull() ?: continue
            if ((s.fonts + found).any { it.family == info.family && it.weight == info.weight && it.italic == info.italic }) continue
            if (!keep(id, bytes)) continue
            found += InstalledFont(id, info.family, info.weight, info.italic, fileName = name, shipName = name.takeIf { it != "$id.font" })
        }
        if (found.isEmpty()) return
        io.nisfeb.talon.util.Log.i("FontRepo", "took up ${found.size} font(s) found on the ship")
        // Onto the list as it is now: a choice made meanwhile stays.
        val now = settings.fontSettings.value
        settings.setFontSettings(now.copy(fonts = now.fonts + found.filter { f -> now.fonts.none { it.id == f.id } }))
    }

    companion object {
        /** A name in talon/fonts that may be a font. */
        private val FONT_FILE = Regex(".*\\.(ttf|otf|font)", RegexOption.IGNORE_CASE)

        /** 30 s, then doubling, at most ten minutes: one small listing a try. */
        val RETRY_WAITS_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
    }
}

/** The font repo a shell provides, for Settings; null where none is set up. */
val LocalFontRepo = androidx.compose.runtime.staticCompositionLocalOf<FontRepo?> { null }

/**
 * The font repo for a shell: made once, and brought in line with the
 * list on start and whenever it changes, here or from another device.
 * One function for both shells, so neither can forget the fetching.
 */
@androidx.compose.runtime.Composable
fun rememberFontRepo(
    settings: UiSettings,
    http: HttpClient,
    shipUrl: () -> String?,
    cookie: () -> String?,
    scope: CoroutineScope,
): FontRepo {
    val repo = androidx.compose.runtime.remember(settings, http, scope) { FontRepo(settings, FontShip(http, shipUrl, cookie), scope) }
    val listed = settings.fontSettings.collectAsState().value
    androidx.compose.runtime.LaunchedEffect(repo, listed.fonts, listed.removed) { runCatching { repo.keepInLine() } }
    return repo
}
