package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "the ability to install a font they've purchased and share it across
 * their devices": installed here, kept on the ship's grubbery, fetched by
 * the other devices, removed from all of them. Against a fake of
 * grubbery's file API (lib/ball-api.hoon) and a real font file, so the
 * font is truly loaded.
 */
class FontRepoTest {
    /** A real TrueType font from this machine; the tests that set text in one need it. */
    private val realFont: File? = listOf(
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf", "C:\\Windows\\Fonts\\arial.ttf",
    ).map(::File).firstOrNull { it.exists() }
        ?: File("/usr/share/fonts").walkTopDown().firstOrNull { it.extension.equals("ttf", true) && readFontInfo(it.readBytes()).isSuccess }

    /** grubbery's ball, as path to bytes; and every request, "METHOD path". */
    private val ball = ConcurrentHashMap<String, ByteArray>()
    private val dirs = ConcurrentHashMap.newKeySet<String>()
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var grubbery = true
    /** False: a proxy's 502 for a ship that is down, which is not a ship without grubbery. */
    @Volatile private var answering = true
    @Volatile private var holdPutMs = 0L

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath.removePrefix("/grubbery/api/")
        asked += "${req.method.value} $path"
        if (!answering) return@MockEngine respond("", HttpStatusCode.BadGateway)
        if (!grubbery) return@MockEngine respond("", HttpStatusCode.NotFound)
        when {
            req.method == HttpMethod.Put && path.startsWith("dir/") ->
                if (dirs.add(path.removePrefix("dir/"))) respond("", HttpStatusCode.Created) else respond("Already exists", HttpStatusCode.Conflict)
            req.method == HttpMethod.Put && path.startsWith("file/") -> {
                delay(holdPutMs)
                val key = path.removePrefix("file/")
                if (key.substringBeforeLast('/') !in dirs) respond("", HttpStatusCode.NotFound)
                else if (ball.putIfAbsent(key, req.body.toByteArray()) == null) respond("", HttpStatusCode.Created)
                else respond("Already exists", HttpStatusCode.Conflict)
            }
            req.method == HttpMethod.Get && path.startsWith("kids/") -> {
                val dir = path.removePrefix("kids/")
                if (dir !in dirs) respond("Not found", HttpStatusCode.NotFound)
                else respond(
                    """{"files":[${ball.keys.filter { it.substringBeforeLast('/') == dir }.joinToString { "\"${it.substringAfterLast('/')}\"" }}],"dirs":[]}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
            }
            req.method == HttpMethod.Get && path.startsWith("file/") ->
                ball[path.removePrefix("file/")]?.let { respond(it, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
            req.method == HttpMethod.Delete && path.startsWith("file/") ->
                if (ball.remove(path.removePrefix("file/")) != null) respond("", HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
            else -> respond("", HttpStatusCode.BadRequest)
        }
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = createTempDirectory(prefix = "talon-fonts-").toFile()

    /** A device: its own settings and its own files, the one ship. */
    private fun device(name: String, settings: UiSettings = InMemoryUiSettings()) = FontRepo(
        settings, FontShip(http, { "https://ship.test" }, { "session=x" }), scope,
        FontFiles(File(tmp, name).absolutePath.toPath()),
    )

    @AfterTest
    fun close() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private suspend fun settled(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(20) }

    @Test
    fun `a font installed here is set, kept on the ship, and fetched by another device`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val here = device("phone")
        here.install(bytes, font.name)
        val id = fontId(bytes)
        val family = readFontInfo(bytes).getOrThrow().family
        assertTrue(here.files.has(id), "kept on this device")
        settled { here.status.value?.contains("all your devices") == true }
        // Wire: the place for fonts made, then the file, its bytes whole.
        assertEquals(listOf("PUT dir/talon", "PUT dir/talon/fonts", "PUT file/talon/fonts/$id.font"), asked.toList())
        assertTrue(ball["talon/fonts/$id.font"].contentEquals(bytes))

        // Another device gets the list through settings sync, and the file from the ship.
        val listed = InMemoryUiSettings().apply { setFontSettings(FontSettings(listOf(InstalledFont(id, family)), family = family)) }
        val there = device("desktop", listed)
        there.sync()
        assertTrue(there.files.read(id).contentEquals(bytes), "fetched, and the same file")
        assertNull(there.status.value)
        assertNotNull(appFontFamily(listed.fontSettings.value, there.files), "text is set in it")
    }

    @Test
    fun `the font chosen is set in the theme the installing device uses`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val settings = InMemoryUiSettings()
        val here = device("phone", settings)
        here.install(font.readBytes(), font.name)
        val family = readFontInfo(font.readBytes()).getOrThrow().family
        assertEquals(family, settings.fontSettings.value.family, "installing one with the system's chosen sets it")
        assertNotNull(appFontFamily(settings.fontSettings.value, here.files))
    }

    @Test
    fun `a ship with no grubbery keeps the font on this device, and says so`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        grubbery = false
        val here = device("phone")
        here.install(font.readBytes(), font.name)
        settled { here.status.value?.contains("this device only") == true }
        assertTrue(here.files.has(fontId(font.readBytes())))
    }

    @Test
    fun `a font not shared when installed goes up the next time Talon starts`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        grubbery = false
        val here = device("phone")
        here.install(bytes, font.name)
        settled { here.status.value?.contains("this device only") == true }
        grubbery = true
        asked.clear()
        here.sync()
        assertTrue(ball["talon/fonts/${fontId(bytes)}.font"].contentEquals(bytes))
        // And once there, a start asks only for the list.
        asked.clear()
        here.sync()
        assertEquals(listOf("GET kids/talon/fonts"), asked.toList())
    }

    @Test
    fun `a file from the ship that is not the one listed is not used`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val id = fontId(bytes)
        dirs += "talon"; dirs += "talon/fonts"
        ball["talon/fonts/$id.font"] = bytes.copyOf(bytes.size / 2)
        val listed = InMemoryUiSettings().apply { setFontSettings(FontSettings(listOf(InstalledFont(id, "X")), family = "X")) }
        val there = device("desktop", listed)
        there.sync()
        assertTrue(!there.files.has(id))
        assertTrue(there.status.value?.contains("could not be fetched") == true, there.status.value)
        assertNull(appFontFamily(listed.fontSettings.value, there.files), "the system's until it arrives")
    }

    // The desktop learned of a font installed on the phone at a busy start,
    // and a fetch that failed was not tried again that session.
    @Test
    fun `a font that could not be fetched is tried again until it arrives`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val id = fontId(bytes)
        dirs += "talon"; dirs += "talon/fonts"
        ball["talon/fonts/$id.font"] = bytes
        answering = false // the ship does not answer at first
        val listed = InMemoryUiSettings().apply { setFontSettings(FontSettings(listOf(InstalledFont(id, "X")), family = "X")) }
        val there = device("desktop", listed)
        val keeping = scope.launch { there.keepInLine(waits = listOf(50L)) }
        settled { asked.size >= 2 }
        assertTrue(!there.files.has(id))
        answering = true
        settled { there.files.has(id) }
        settled { keeping.isCompleted }
        assertNull(there.status.value)
    }

    // It asked the ship every ten minutes for as long as Talon ran: a 404
    // to "kids" looked like an empty folder, so the font seemed due soon.
    @Test
    fun `a ship without grubbery is not asked for fonts again and again`() = runBlocking<Unit> {
        grubbery = false
        val listed = InMemoryUiSettings().apply { setFontSettings(FontSettings(listOf(InstalledFont("f".repeat(64), "X")), family = "X")) }
        val there = device("desktop", listed)
        val keeping = scope.launch { there.keepInLine(waits = listOf(50L)) }
        settled { keeping.isCompleted }
        assertEquals(1, asked.count { it.startsWith("GET kids") }, "one listing, then nothing to wait for")
    }

    @Test
    fun `what is not a font is refused in words and nothing is installed`() = runBlocking<Unit> {
        val settings = InMemoryUiSettings()
        val here = device("phone", settings)
        here.install("not a font at all, just some words".encodeToByteArray(), "notes.txt")
        assertTrue(here.status.value?.contains(".ttf or .otf") == true, here.status.value)
        assertTrue(settings.fontSettings.value.fonts.isEmpty())
        assertTrue(asked.isEmpty(), "nothing sent")
    }

    @Test
    fun `a removed font goes from this device, the list and the ship, and another device drops it`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val id = fontId(bytes)
        val settings = InMemoryUiSettings()
        val here = device("phone", settings)
        here.install(bytes, font.name)
        settled { ball.containsKey("talon/fonts/$id.font") }
        val family = settings.fontSettings.value.family!!
        here.remove(family)
        assertTrue(!here.files.has(id))
        assertEquals(listOf(id), settings.fontSettings.value.removed)
        assertNull(settings.fontSettings.value.family)
        settled { !ball.containsKey("talon/fonts/$id.font") }

        // A device that still has the file drops it once it hears.
        val other = InMemoryUiSettings()
        val there = device("desktop", other).also { it.files.write(id, bytes) }
        other.setFontSettings(settings.fontSettings.value)
        there.sync()
        assertTrue(!there.files.has(id))
    }

    // Leaving the screen midway: the upload runs on the repo's scope, not
    // the screen's, and finishes after the one who asked is gone.
    @Test
    fun `leaving the screen while a font is being shared still shares it`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        holdPutMs = 300
        val screen = CoroutineScope(SupervisorJob())
        val here = device("phone")
        screen.coroutineContext[kotlinx.coroutines.Job]!!.let { job ->
            here.install(font.readBytes(), font.name)
            job.cancel()
        }
        settled { here.status.value?.contains("all your devices") == true }
        assertTrue(ball.containsKey("talon/fonts/${fontId(font.readBytes())}.font"))
    }

    // ─── fonts found on the ship ───────────────────────────────────
    // A user put several fonts in talon/fonts through grubbery's page and
    // none was offered: Talon fetched only what its synced list named.

    private fun shipHas(name: String, bytes: ByteArray) {
        dirs += "talon"; dirs += "talon/fonts"
        ball["talon/fonts/$name"] = bytes
    }

    @Test
    fun `a font put in talon fonts by hand is offered, here and on every device`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val family = readFontInfo(bytes).getOrThrow().family
        shipHas("dejavu-sans.ttf", bytes)
        shipHas("readme.txt", "not a font".encodeToByteArray())
        val settings = InMemoryUiSettings()
        val here = device("desktop", settings)
        here.sync()
        val listed = settings.fontSettings.value
        assertEquals(listOf(family), listed.families, "offered")
        assertEquals("dejavu-sans.ttf", listed.fonts.single().shipName)
        assertTrue(here.files.has(fontId(bytes)))
        assertTrue(asked.none { it.startsWith("PUT") }, "kept under its own name, not copied: $asked")
        assertTrue("GET file/talon/fonts/readme.txt" !in asked, "what is not a font is not fetched")
        // Known now: a start asks only for the list.
        asked.clear()
        here.sync()
        assertEquals(listOf("GET kids/talon/fonts"), asked.toList())
        // Another device, given the list by settings sync, fetches it by its own name.
        val there = device("phone", InMemoryUiSettings().apply { setFontSettings(listed) })
        there.sync()
        assertTrue(there.files.read(fontId(bytes)).contentEquals(bytes))
        assertNull(there.status.value)
    }

    @Test
    fun `a font Talon put on the ship is offered where its list never arrived`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val id = fontId(bytes)
        shipHas("$id.font", bytes)
        val settings = InMemoryUiSettings()
        device("desktop", settings).sync()
        val f = settings.fontSettings.value.fonts.single()
        assertEquals(id, f.id)
        assertNull(f.shipName, "its name is Talon's own")
    }

    @Test
    fun `a removed font left on the ship does not come back, and nothing is deleted`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val id = fontId(bytes)
        shipHas("$id.font", bytes)
        shipHas("copy.ttf", bytes)
        val settings = InMemoryUiSettings().apply { setFontSettings(FontSettings(removed = listOf(id))) }
        device("desktop", settings).sync()
        assertTrue(settings.fontSettings.value.fonts.isEmpty())
        assertTrue(ball.containsKey("talon/fonts/$id.font") && ball.containsKey("talon/fonts/copy.ttf"))
        assertTrue(asked.none { it.startsWith("DELETE") }, "$asked")
        assertTrue("GET file/talon/fonts/$id.font" !in asked, "a removed one by its id is not fetched")
    }

    @Test
    fun `two files for one weight and style give one choice, and neither is removed`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        // The same face, another file: a different build of it.
        shipHas("a.ttf", bytes)
        shipHas("b.ttf", bytes + byteArrayOf(0))
        val settings = InMemoryUiSettings()
        val here = device("desktop", settings)
        here.sync()
        here.sync()
        assertEquals(1, settings.fontSettings.value.fonts.size)
        assertTrue(settings.fontSettings.value.removed.isEmpty(), "they would take turns removing each other")
        assertTrue(ball.containsKey("talon/fonts/a.ttf") && ball.containsKey("talon/fonts/b.ttf"))
    }

    @Test
    fun `a font put there by hand and removed goes from the ship by its own name`() = runBlocking<Unit> {
        val font = realFont ?: return@runBlocking println("no TrueType font on this machine; skipped")
        val bytes = font.readBytes()
        val family = readFontInfo(bytes).getOrThrow().family
        shipHas("dejavu-sans.ttf", bytes)
        val settings = InMemoryUiSettings()
        val here = device("desktop", settings)
        here.sync()
        here.remove(family)
        settled { !ball.containsKey("talon/fonts/dejavu-sans.ttf") }
        assertEquals(listOf(fontId(bytes)), settings.fontSettings.value.removed)
        here.sync()
        assertTrue(settings.fontSettings.value.fonts.isEmpty(), "and does not come back")
    }
}

