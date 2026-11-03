package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Url
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Talon's upload against Jars itself (github.com/mopfel-winrux/jars), not
 * against Talon's own idea of SigV4. Every Jars fix until 2026-11-03 was
 * checked only that way, and the next difference came back as the same
 * 403. Run it against a fake ship with Jars installed and set up as its
 * README says (`:s3-server [%configure-storage '<url>']`):
 *
 *   TALON_JARS_URL=http://localhost:8095 TALON_JARS_SHIP=~dys \
 *   TALON_JARS_COOKIE="$(awk '$6 ~ /^urbauth/ {print $6"="$7}' <jar>)" \
 *   ./gradlew :composeApp:desktopTest --tests '*JarsUploadLiveTest'
 *
 * Skipped without them, as the other live tests are.
 */
class JarsUploadLiveTest {
    private val url = System.getenv("TALON_JARS_URL")?.trimEnd('/')
    private val ship = System.getenv("TALON_JARS_SHIP")
    /** The session cookie as name=value, from the jar the awk line reads. */
    private val cookie = System.getenv("TALON_JARS_COOKIE")

    /** A 1x1 PNG: IHDR, IDAT, IEND, nothing for the metadata strip to take. */
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    private fun live(block: suspend (repo: TlonChatRepo, http: HttpClient, channel: UrbitChannel) -> Unit) {
        if (url.isNullOrBlank() || ship.isNullOrBlank() || cookie.isNullOrBlank()) {
            println("JarsUploadLiveTest: no ship given; skipped")
            return
        }
        val (name, value) = cookie.split('=', limit = 2)
        val saved = SavedSession(url, ship, name, value, Url(url).host)
        val store = object : SessionStore {
            override fun all() = listOf(saved)
            override fun active() = saved
            override fun activeShip() = saved.ship
            override fun save(entry: SavedSession, makeActive: Boolean) {}
            override fun setActive(ship: String) {}
            override fun remove(ship: String) {}
            override fun clearAll() {}
        }
        val session = UrbitSession(createAppHttpClient(), store).apply { tryRestore(ship) }
        val channel = session.openChannel()
        val dir = createTempDirectory(prefix = "talon-jars-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val repo = TlonChatRepo(db).apply { attachForTest(channel, ship, http = session.http, base = url) }
        try {
            runBlocking { block(repo, session.http, channel) }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a picture uploads to Jars through Talon's own path, and reads back as sent`() = live { repo, http, _ ->
        val at = repo.uploadImage(png, "image/png", "cat.png")
        assertTrue(at.startsWith("$url/jars/"), "Jars's address for it: $at")
        val back = http.get(at)
        assertEquals(200, back.status.value, "Jars serves it")
        assertContentEquals(io.nisfeb.talon.util.stripImageMetadata(png), back.readRawBytes())
    }

    // The report behind this test: a ship behind Cloudflare and a tailnet.
    // Jars rebuilds the signature from the Host the request arrives with,
    // Talon signs the host in %storage's endpoint, and a proxy that hands
    // the ship a Host of its own breaks the match.
    @Test
    fun `through a proxy, the upload passes when the Host is kept and fails as reported when it is changed`() = live { _, http, channel ->
        val creds = parseStorageCredentials(channel.scry("storage", "/credentials"))!!
        val config = parseStorageConfiguration(channel.scry("storage", "/configuration"))!!
        val origin = Url(url!!)
        fun put(through: Int) = runBlocking {
            S3Uploader.put(
                http = http,
                creds = S3Uploader.Credentials(
                    // The proxy's address, path and all, in place of the ship's.
                    endpoint = "http://localhost:$through" + Url(creds.endpoint).encodedPath,
                    accessKeyId = creds.accessKeyId,
                    secretAccessKey = creds.secretAccessKey,
                ),
                config = S3Uploader.Configuration(config.bucket, config.region.ifBlank { "us-east-1" }, config.publicUrlBase),
                key = "talon/proxy-test.png",
                bytes = png,
                contentType = "image/png",
            )
        }
        proxy(origin.host, origin.port, rewriteHost = null).use { kept ->
            put(kept.localPort)
        }
        proxy(origin.host, origin.port, rewriteHost = "${origin.host}:${origin.port}").use { changed ->
            val e = assertFailsWith<IllegalStateException> { put(changed.localPort) }
            assertTrue("HTTP 403" in e.message!! && "Authentication failed" in e.message!!, "the user's error: ${e.message}")
        }
    }

    /**
     * A plain HTTP proxy to [host]:[port] for one connection at a time,
     * passing each request on as it came or with its Host line set to
     * [rewriteHost], as a tunnel configured to override it does.
     */
    private fun proxy(host: String, port: Int, rewriteHost: String?): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { relay(client, host, port, rewriteHost) }
            }
        }
        return server
    }

    private fun relay(client: Socket, host: String, port: Int, rewriteHost: String?) = client.use {
        Socket(host, port).use { upstream ->
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val lines = head.toString().split("\r\n").map { line ->
                if (rewriteHost != null && line.startsWith("Host:", ignoreCase = true)) "Host: $rewriteHost" else line
            }
            upstream.getOutputStream().write(lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1))
            // The ship's answer, then the end of it: a 403 carries no length,
            // so the client reads to the close.
            val back = thread(isDaemon = true) {
                runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
                runCatching { client.close() }
            }
            runCatching { input.copyTo(upstream.getOutputStream()) }
            back.join(10_000)
        }
    }
}
