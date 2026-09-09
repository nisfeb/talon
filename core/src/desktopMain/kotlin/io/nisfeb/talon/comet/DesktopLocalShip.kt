package io.nisfeb.talon.comet

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.prepareGet
import io.ktor.http.parameters
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.nisfeb.talon.util.AppDirs
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a comet on this machine under a pseudo-terminal.
 *
 * Layout under [dir] (Talon's per-user data directory by default):
 *   vere-v4.6-<os>-<arch>[.exe]   the runtime, fetched once
 *   pier/                          the comet
 *   ports                          the HTTP port, chosen once
 *
 * The terminal is the only way to learn the login code: vere refuses
 * pipes ("not a tty"), and `-t` disables the dojo. So the runtime gets
 * a pty from pty4j, we wait for `~ship:dojo>`, send `+code` with a
 * carriage return (a newline is ignored), and read the four words.
 * Everything parsed off the terminal is in [CometTerminal].
 *
 * Ports are picked once and kept, so the session the app saved on the
 * first login stays valid across launches.
 */
class DesktopLocalShip(
    private val http: HttpClient,
    private val dir: File = File(AppDirs.userData, "comet"),
) : LocalShip {
    private val _state = MutableStateFlow<LocalShipState>(LocalShipState.Idle)
    override val state: StateFlow<LocalShipState> = _state.asStateFlow()

    private val pier: File get() = File(dir, "pier")
    private val mutex = Mutex()
    private var process: PtyProcess? = null
    private val transcript = StringBuilder()
    private val transcriptLock = Any()

    override fun pierExists(): Boolean = File(pier, ".urb").isDirectory

    override suspend fun setup(): LocalShipState.Ready = mutex.withLock {
        if (pierExists()) return@withLock startLocked()
        val bin = ensureRuntime()
        // A directory without .urb is a boot that never finished; vere
        // refuses to boot into an existing directory, so clear it.
        if (pier.exists()) pier.deleteRecursively()
        boot(bin, firstBoot = true)
    }

    override suspend fun start(): LocalShipState.Ready = mutex.withLock { startLocked() }

    private suspend fun startLocked(): LocalShipState.Ready {
        val current = _state.value
        if (current is LocalShipState.Ready && process?.isAlive == true) return current
        if (!pierExists()) fail("There is no local ship to start.")
        val bin = ensureRuntime()
        return boot(bin, firstBoot = false)
    }

    override suspend fun stop() = mutex.withLock {
        val p = process ?: return@withLock
        if (p.isAlive) {
            runCatching { send("|exit\r") }
            waitFor(20.seconds) { !p.isAlive }
            if (p.isAlive) p.destroy()
        }
        process = null
        _state.value = LocalShipState.Stopped
    }

    // ── runtime ──────────────────────────────────────────────────

    private suspend fun ensureRuntime(): File {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        val asset = VereRelease.assetFor(os, arch)
            ?: fail("There is no Urbit runtime for this computer ($os $arch).")
        val bin = File(dir, VereRelease.binaryName(asset))
        if (bin.isFile && bin.length() > 1_000_000L) return bin
        dir.mkdirs()
        val tgz = File(dir, asset)
        try {
            download(VereRelease.downloadUrl(asset), tgz)
            val member = VereRelease.memberName(asset)
            if (!Tarball.extractMember(tgz, member, bin)) fail("The runtime download had no $member inside.")
            bin.setExecutable(true)
        } finally {
            tgz.delete()
        }
        Log.i(TAG, "runtime ready: ${bin.name} (${bin.length() / 1_048_576} MB)")
        return bin
    }

    private suspend fun download(url: String, dest: File) = withContext(Dispatchers.IO) {
        http.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) fail("Runtime download failed: HTTP ${resp.status.value}.")
            val total = resp.contentLength()
            val channel = resp.bodyAsChannel()
            val buf = ByteArray(256 * 1024)
            var bytes = 0L
            dest.outputStream().buffered().use { out ->
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n <= 0) continue
                    out.write(buf, 0, n)
                    bytes += n
                    _state.value = LocalShipState.Downloading(bytes, total)
                }
            }
        }
    }

    // ── boot ─────────────────────────────────────────────────────

    private suspend fun boot(bin: File, firstBoot: Boolean): LocalShipState.Ready = withContext(Dispatchers.IO) {
        val httpPort = port()
        // Vere picks its own loopback port (12321 or the next free);
        // only the public HTTP port is ours to choose.
        val cmd = buildList {
            add(bin.absolutePath)
            if (firstBoot) add("-c")
            add(pier.absolutePath)
            add("--http-port"); add("$httpPort")
            add("--loom"); add("31")
        }
        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        synchronized(transcriptLock) { transcript.setLength(0) }
        _state.value = LocalShipState.Booting(firstBoot, "starting the runtime")
        val p = PtyProcessBuilder(cmd.toTypedArray())
            .setDirectory(dir.absolutePath)
            .setEnvironment(env)
            .setInitialColumns(160)
            .setInitialRows(40)
            .setConsole(false)
            .setUseWinConPty(true)
            .start()
        process = p
        startReader(p)
        try {
            val deadline = System.nanoTime() + (if (firstBoot) 30.minutes else 5.minutes).inWholeNanoseconds
            var prompt: String? = null
            while (prompt == null) {
                val text = snapshot()
                prompt = CometTerminal.promptShip(text)
                if (prompt != null) break
                if (!p.isAlive) {
                    // Let the reader drain what vere printed on its way out.
                    delay(750)
                    fail("The ship stopped during boot.\n" + CometTerminal.lastDetail(snapshot()))
                }
                if (System.nanoTime() > deadline) fail("The ship did not finish booting in time.")
                _state.value = LocalShipState.Booting(firstBoot, CometTerminal.lastDetail(text))
                delay(500)
            }
            val mark = transcriptLength()
            send("+code\r")
            val code = waitFor(30.seconds) { CometTerminal.code(textSince(mark)) }
                ?: fail("The ship did not answer +code.")
            val port = File(pier, ".http.ports").takeIf { it.isFile }
                ?.readText()?.let(CometTerminal::publicPort) ?: httpPort
            val url = "http://127.0.0.1:$port"
            // The prompt abbreviates a comet's name; the login cookie
            // carries the full one, and proves the code at the same time.
            val ship = waitFor(30.seconds) { loginShipName(url, code) }
                ?: CometTerminal.minedShip(snapshot())
                ?: fail("The ship is up but did not accept its own code.")
            LocalShipState.Ready(ship, url, code).also {
                _state.value = it
                Log.i(TAG, "local ship ${it.ship} up on port $port")
            }
        } catch (t: Throwable) {
            if (p.isAlive) runCatching { p.destroy() }
            process = null
            throw t
        }
    }

    /** The ship named by the auth cookie a successful login sets, or
     *  null while eyre is still coming up (or the code is wrong). */
    private suspend fun loginShipName(url: String, code: String): String? = runCatching {
        val resp = http.submitForm(
            url = "$url/~/login",
            formParameters = parameters { append("password", code) },
        )
        resp.headers.getAll("Set-Cookie").orEmpty()
            .map { it.substringBefore('=') }
            .firstOrNull { it.startsWith("urbauth-~") }
            ?.removePrefix("urbauth-")
    }.getOrNull()

    /** The HTTP port, chosen once on first boot and reused after, so
     *  the session the app saved stays valid across launches. */
    private fun port(): Int {
        val file = File(dir, "ports")
        file.takeIf { it.isFile }?.readText()?.trim()?.split(" ")?.getOrNull(0)?.toIntOrNull()?.let { return it }
        val chosen = freePort()
        dir.mkdirs()
        file.writeText("$chosen")
        return chosen
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ── terminal ─────────────────────────────────────────────────

    private fun startReader(p: PtyProcess) {
        Thread({
            val buf = ByteArray(8192)
            val input = p.inputStream
            // Everything the terminal prints also lands in terminal.log
            // under the comet directory: the one place to look when a
            // boot stalls, for the user and for us.
            val log = runCatching { File(dir, "terminal.log").outputStream().buffered() }.getOrNull()
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    runCatching { log?.write(buf, 0, n); log?.flush() }
                    val chunk = String(buf, 0, n, Charsets.UTF_8)
                    synchronized(transcriptLock) {
                        transcript.append(chunk)
                        // Keep the tail only; the boot log is long and
                        // nothing we parse is more than a screen back.
                        if (transcript.length > 256 * 1024) transcript.delete(0, transcript.length - 128 * 1024)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { log?.close() }
            }
        }, "Talon-comet-terminal").apply { isDaemon = true }.start()
    }

    private fun send(text: String) {
        val p = process ?: return
        p.outputStream.write(text.toByteArray(Charsets.UTF_8))
        p.outputStream.flush()
    }

    private fun snapshot(): String = synchronized(transcriptLock) { transcript.toString() }
    private fun transcriptLength(): Int = synchronized(transcriptLock) { transcript.length }
    private fun textSince(mark: Int): String = synchronized(transcriptLock) {
        if (mark >= transcript.length) "" else transcript.substring(mark)
    }

    private suspend fun <T> waitFor(timeout: Duration, probe: suspend () -> T?): T? {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            delay(250)
        }
        return probe()
    }

    private fun fail(why: String): Nothing {
        _state.value = LocalShipState.Failed(why)
        throw IllegalStateException(why)
    }

    private companion object {
        const val TAG = "LocalShip"
    }
}
