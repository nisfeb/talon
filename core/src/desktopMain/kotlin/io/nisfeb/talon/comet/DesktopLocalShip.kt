package io.nisfeb.talon.comet

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.parameters
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a comet on this machine under a pseudo-terminal.
 *
 * Layout under [dir] (Talon's per-user data directory by default):
 *   vere-v4.6-<os>-<arch>[.exe]   the runtime, fetched once per version
 *   runtime-version                which one the pier runs on
 *   pier/                          the comet
 *   ports                          the HTTP port, chosen once
 *   ship                           the comet's name, once known
 *   code                           the login code, read once at first
 *                                  boot (owner-only file, like the
 *                                  session cookies the app keeps)
 *   terminal.log                   what the terminal printed, last run
 *
 * The terminal is the only way to learn the login code: vere refuses
 * pipes ("not a tty"), and `-t` disables the dojo. So the runtime gets
 * a pty from pty4j, we wait for `~ship:dojo>`, send `+code` with a
 * carriage return (a newline is ignored), and read the four words.
 * Everything parsed off the terminal is in [CometTerminal].
 *
 * Learned from booting real comets, and load-bearing:
 *  - the prompt abbreviates a comet (`~falwed_litzod:dojo>`), so the
 *    full name comes from the login cookie;
 *  - the code is stable across boots and a restarted ship is often
 *    busy (an update landing) exactly when the dojo would be asked,
 *    so the code from first boot is kept and later starts log in
 *    with it, waiting the update out;
 *  - after `|exit` the worker releases the pier a moment after the
 *    front process exits, and a boot before that dies at once.
 */
class DesktopLocalShip(
    private val http: HttpClient,
    private val dir: File = File(AppDirs.userData, "comet"),
) : LocalShip {
    private val _state = MutableStateFlow<LocalShipState>(LocalShipState.Idle)
    override val state: StateFlow<LocalShipState> = _state.asStateFlow()
    private val _terminal = MutableStateFlow("")
    override val terminal: StateFlow<String> = _terminal.asStateFlow()

    private val pier: File get() = File(dir, "pier")
    private val lockFile: File get() = File(pier, ".vere.lock")
    private val codeFile: File get() = File(dir, "code")
    private val shipFile: File get() = File(dir, "ship")
    private val versionFile: File get() = File(dir, "runtime-version")
    private val mutex = Mutex()
    private var process: PtyProcess? = null
    private val transcript = StringBuilder()
    private val transcriptLock = Any()

    override fun pierExists(): Boolean = File(pier, ".urb").isDirectory

    override suspend fun setup(): LocalShipState.Ready = mutex.withLock {
        if (pierExists()) return@withLock startLocked()
        val bin = ensureRuntime(installedVersion())
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
        val bin = ensureRuntime(installedVersion())
        clearStaleLock()
        return boot(bin, firstBoot = false)
    }

    override suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val p = process ?: return
        if (p.isAlive) {
            runCatching { send("|exit") }
            waitFor(20.seconds) { !p.isAlive }
            if (p.isAlive) p.destroy()
        }
        // The front process exits first; its worker unmaps the pier a
        // moment later and drops the lock. A boot started before that
        // dies with "serf unexpectedly shut down".
        waitFor(20.seconds) { if (lockFile.exists()) null else true }
        delay(500)
        process = null
        _state.value = LocalShipState.Stopped
    }

    override fun send(line: String) {
        val p = process ?: return
        if (!p.isAlive) return
        runCatching {
            p.outputStream.write((line.trimEnd('\r', '\n') + "\r").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        }
    }

    // ── what Settings shows ──────────────────────────────────────

    override fun describe(): LocalShipInfo? {
        if (!pierExists()) return null
        return LocalShipInfo(
            ship = shipFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() },
            pierPath = pier.absolutePath,
            runtimeVersion = installedVersion(),
        )
    }

    override suspend fun pierBytes(): Long = withContext(Dispatchers.IO) {
        pier.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    override fun keptCode(): String? =
        codeFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun checkRuntimeUpdate(): RuntimeUpdate? = withContext(Dispatchers.IO) {
        val resp = http.get(VereRelease.LATEST_URL) {
            header("Accept", "application/vnd.github+json")
        }
        if (!resp.status.isSuccess()) error("release check failed: HTTP ${resp.status.value}")
        val tag = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["tag_name"]?.jsonPrimitive?.content
            ?: error("release check: no tag")
        val latest = VereRelease.versionFromTag(tag) ?: return@withContext null
        val installed = installedVersion()
        if (VereRelease.isNewer(latest, installed)) RuntimeUpdate(installed, latest) else null
    }

    override suspend fun upgradeRuntime(version: String) = mutex.withLock {
        val wasRunning = process?.isAlive == true
        // Fetch first: a failed download leaves the old runtime in charge.
        val bin = ensureRuntime(version)
        stopLocked()
        versionFile.writeText(version)
        // Older runtimes are dead weight once the pier has migrated.
        dir.listFiles { f -> f.name.startsWith("vere-v") && f != bin }?.forEach { it.delete() }
        Log.i(TAG, "runtime upgraded to $version")
        if (wasRunning || pierExists()) {
            clearStaleLock()
            boot(bin, firstBoot = false)
        }
        Unit
    }

    // ── runtime ──────────────────────────────────────────────────

    private fun installedVersion(): String =
        versionFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: VereRelease.VERSION

    private suspend fun ensureRuntime(version: String): File {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        val asset = VereRelease.assetFor(os, arch)
            ?: fail("There is no Urbit runtime for this computer ($os $arch).")
        val bin = File(dir, VereRelease.binaryName(asset, version))
        if (bin.isFile && bin.length() > 1_000_000L) return bin
        dir.mkdirs()
        val tgz = File(dir, asset)
        try {
            download(VereRelease.downloadUrl(asset, version), tgz)
            val member = VereRelease.memberName(asset, version)
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
        _terminal.value = ""
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
            val port = File(pier, ".http.ports").takeIf { it.isFile }
                ?.readText()?.let(CometTerminal::publicPort) ?: httpPort
            val url = "http://127.0.0.1:$port"
            // The code does not change between boots, and a restarted
            // ship is often busy (an OTA landing) exactly when the dojo
            // would be asked. So the code read at first boot is kept,
            // and later starts just log in with it; the dojo is the
            // fallback, for a code reset or a lost file.
            val cached = keptCode()
            if (cached != null) {
                // A ship restarted while an update is landing (the
                // Landscape OTA arrives minutes after first boot) answers
                // nothing for a while; that is normal, so wait it out.
                _state.value = LocalShipState.Booting(firstBoot, "waiting for the ship to finish installing updates")
                val ship = waitFor(10.minutes) { loginShipName(url, cached) }
                if (ship != null) {
                    return@withContext ready(ship, url, cached, port)
                }
                Log.w(TAG, "the kept code was refused; asking the dojo")
            }
            // Typing the instant the prompt shows can land while the
            // dojo is still linking and crash the command, so wait for
            // the terminal to go quiet first, and ask again if an
            // answer does not come (a busy ship can take a while).
            var code: String? = null
            var asks = 0
            while (code == null && asks < 8) {
                waitQuiet(1500)
                val mark = transcriptLength()
                send("+code")
                asks++
                code = waitFor(15.seconds) { CometTerminal.code(textSince(mark)) }
            }
            if (code == null) fail("The ship did not answer +code.")
            // The prompt abbreviates a comet's name; the login cookie
            // carries the full one, and proves the code at the same time.
            val ship = waitFor(2.minutes) { loginShipName(url, code) }
                ?: CometTerminal.minedShip(snapshot())
                ?: fail("The ship is up but did not accept its own code.")
            keepSecret(codeFile, code)
            ready(ship, url, code, port)
        } catch (t: Throwable) {
            if (p.isAlive) runCatching { p.destroy() }
            process = null
            throw t
        }
    }

    private fun ready(ship: String, url: String, code: String, port: Int): LocalShipState.Ready {
        runCatching { shipFile.writeText(ship) }
        return LocalShipState.Ready(ship, url, code).also {
            _state.value = it
            Log.i(TAG, "local ship ${it.ship} up on port $port")
        }
    }

    private fun keepSecret(file: File, text: String) {
        runCatching {
            file.writeText(text)
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }.onFailure { Log.w(TAG, "could not keep ${file.name}", it) }
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

    /** A lock left by a runtime that is no longer running (a crash, a
     *  kill) would make vere refuse the pier; clear it. */
    private fun clearStaleLock() {
        val lock = lockFile.takeIf { it.isFile } ?: return
        val pid = lock.readText().trim().toLongOrNull()
        val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        if (!alive) {
            Log.w(TAG, "clearing a stale pier lock (pid $pid)")
            lock.delete()
        }
    }

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
                    val shown: String
                    synchronized(transcriptLock) {
                        transcript.append(chunk)
                        // Keep the tail only; the boot log is long and
                        // nothing we parse is more than a screen back.
                        if (transcript.length > 256 * 1024) transcript.delete(0, transcript.length - 128 * 1024)
                        shown = transcript.takeLast(16 * 1024).toString()
                    }
                    _terminal.value = CometTerminal.clean(shown)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { log?.close() }
            }
        }, "Talon-comet-terminal").apply { isDaemon = true }.start()
    }

    private fun snapshot(): String = synchronized(transcriptLock) { transcript.toString() }
    private fun transcriptLength(): Int = synchronized(transcriptLock) { transcript.length }
    private fun textSince(mark: Int): String = synchronized(transcriptLock) {
        if (mark >= transcript.length) "" else transcript.substring(mark)
    }

    /** Wait until the terminal has printed nothing for [quietMs]
     *  (bounded at ten times that, in case it never settles). */
    private suspend fun waitQuiet(quietMs: Long) {
        val deadline = System.nanoTime() + quietMs * 10 * 1_000_000
        var last = transcriptLength()
        var lastChange = System.nanoTime()
        while (System.nanoTime() < deadline) {
            delay(200)
            val now = transcriptLength()
            if (now != last) { last = now; lastChange = System.nanoTime() }
            else if (System.nanoTime() - lastChange >= quietMs * 1_000_000) return
        }
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
