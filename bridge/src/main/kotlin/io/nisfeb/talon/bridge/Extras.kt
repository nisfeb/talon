package io.nisfeb.talon.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date

/** Lowers the music while people talk. Call [tick] a few times a second. */
class Ducker {
    @Volatile var enabled = false
    /** Music level while someone talks, as a percent of its normal level. */
    @Volatile var percent = 30
    @Volatile var holdMs = 1500L
    val ducking get() = saved.isNotEmpty()

    private val saved = LinkedHashMap<Int, Int>()
    private var lastVoiceAt = 0L

    fun tick(voice: Boolean, music: List<Pulse.Stream>) {
        val now = System.currentTimeMillis()
        if (voice) lastVoiceAt = now
        val want = enabled && (voice || now - lastVoiceAt < holdMs)
        if (want) {
            for (s in music) if (s.index !in saved) {
                saved[s.index] = s.volume
                runCatching { Pulse.setPlaybackVolume(s.index, s.volume * percent / 100) }
            }
        } else if (saved.isNotEmpty()) {
            release()
        }
    }

    fun release() {
        // ponytail: a stream that ended while ducked just fails to restore.
        for ((i, v) in saved) runCatching { Pulse.setPlaybackVolume(i, v) }
        saved.clear()
    }

    /** The level a stream had before ducking, so sliders and presets see the real one. */
    fun originalVolume(s: Pulse.Stream) = saved[s.index] ?: s.volume
}

@Serializable
data class AppRoute(val target: String, val volume: Int)

@Serializable
data class Preset(
    val name: String,
    val apps: Map<String, AppRoute> = emptyMap(),
    val spaceApps: List<String> = emptyList(),
    val toSpaceVolume: Int = 100,
    val toPartyVolume: Int = 100,
    val duck: Boolean = false,
    val duckPercent: Int = 30,
)

@Serializable
data class PresetFile(val active: String? = null, val presets: List<Preset> = emptyList())

/** Named routing layouts in ~/.config/talon/party-presets.json. */
object Presets {
    val file = File(System.getProperty("user.home"), ".config/talon/party-presets.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): PresetFile = runCatching { json.decodeFromString<PresetFile>(file.readText()) }.getOrDefault(PresetFile())

    fun save(pf: PresetFile) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(pf))
    }

    /** Moves and re-levels the streams a preset names; other apps are left alone. */
    fun apply(p: Preset, playback: List<Pulse.Stream>, capture: List<Pulse.Stream>) {
        for (s in capture) {
            val target = if (s.appName in p.spaceApps) Pulse.MIC_MONITOR else Pulse.DEFAULT_SOURCE
            val wired = s.target == Pulse.MIC_MONITOR
            if (wired != (s.appName in p.spaceApps)) runCatching { Pulse.moveCapture(s.index, target) }
        }
        for (s in playback) {
            val r = p.apps[s.appName] ?: continue
            if (s.target != r.target) runCatching { Pulse.movePlayback(s.index, r.target) }
            if (s.volume != r.volume) runCatching { Pulse.setPlaybackVolume(s.index, r.volume) }
        }
    }
}

/** The track a media player is on, read over MPRIS with gdbus. */
object NowPlaying {
    data class Track(val player: String, val artist: String, val title: String, val status: String) {
        val text get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private val quoted = """'((?:[^'\\]|\\.)*)'"""

    /** The track a player is playing right now, or null; paused players are not news. */
    fun read(): Track? {
        val names = gdbus("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames") ?: return null
        val players = Regex("""org\.mpris\.MediaPlayer2\.[A-Za-z0-9_.]+""").findAll(names).map { it.value }.distinct()
        for (p in players) {
            val status = gdbus(p, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                ?.let { Regex(quoted).find(it)?.groupValues?.get(1) } ?: continue
            if (status == "Stopped") continue
            val meta = gdbus(p, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Metadata") ?: continue
            val t = parse(p, status, meta) ?: continue
            if (status == "Playing") return t
        }
        return null
    }

    /** Reads a track out of gdbus's GVariant text for a Metadata property. */
    internal fun parse(player: String, status: String, meta: String): Track? {
        val title = Regex("""'xesam:title': <$quoted>""").find(meta)?.groupValues?.get(1)?.replace("\\'", "'").orEmpty()
        val artist = Regex("""'xesam:artist': <\[$quoted""").find(meta)?.groupValues?.get(1)?.replace("\\'", "'").orEmpty()
        if (title.isBlank()) return null
        return Track(player.removePrefix("org.mpris.MediaPlayer2.").substringBefore('.'), artist, title, status)
    }

    private fun gdbus(dest: String, path: String, method: String, vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("gdbus", "call", "--session", "--dest", dest, "--object-path", path, "--method", method) + args)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    }.getOrNull()
}

/**
 * Records the show as a stereo WAV: left is what the Space hears
 * (party voice plus anything sent to the Space), right is what the
 * party hears from this machine (Space voice plus anything sent to
 * the party). Two parec streams read in lock step.
 */
class ShowRecorder(private val dir: File) {
    @Volatile var file: File? = null
    @Volatile var startedAt = 0L
    private var left: Process? = null
    private var right: Process? = null
    private var thread: Thread? = null
    val recording get() = thread?.isAlive == true
    val elapsedMs get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    fun start(): File {
        stop()
        dir.mkdirs()
        val f = File(dir, "show-" + SimpleDateFormat("yyyyMMdd-HHmm").format(Date()) + ".wav")
        file = f
        val l = parec(Pulse.MIC_MONITOR).also { left = it }
        val r = parec(Pulse.SPACE_MONITOR).also { right = it }
        startedAt = System.currentTimeMillis()
        thread = Thread({
            val sink = WavPcmSink(f)
            val a = ByteArray(FRAMES * 2)
            val b = ByteArray(FRAMES * 2)
            val out = ByteArray(FRAMES * 4)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    if (!fill(l.inputStream, a) || !fill(r.inputStream, b)) break
                    // ponytail: the two streams share Pulse's clock; any drift is well under a frame per hour.
                    for (i in 0 until FRAMES) {
                        out[4 * i] = a[2 * i]; out[4 * i + 1] = a[2 * i + 1]
                        out[4 * i + 2] = b[2 * i]; out[4 * i + 3] = b[2 * i + 1]
                    }
                    sink.write(out, FRAMES, PcmFormat(48_000, 2))
                }
            } finally {
                sink.close()
            }
        }, "show-recorder").apply { isDaemon = true; start() }
        return f
    }

    fun stop() {
        thread?.interrupt()
        left?.destroy(); right?.destroy()
        thread?.join(2_000)
        thread = null; left = null; right = null
        startedAt = 0L
    }

    private fun parec(source: String) = ProcessBuilder(
        "parec", "--device=$source", "--raw", "--format=s16le", "--channels=1", "--rate=48000", "--latency-msec=50",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    private fun fill(input: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) return false
            got += n
        }
        return true
    }

    private companion object { const val FRAMES = 960 }
}

/** Your real microphone straight into the Space, the party, or both, gated by a talk switch. */
class HostMic {
    @Volatile var module: Int? = null
    @Volatile var talking = false

    fun arm(source: String, sink: String) {
        disarm()
        module = Pulse.loadLoopback(source, sink, Pulse.HOST_MIC_APP)
        sync(Pulse.playback())
    }

    fun disarm() {
        module?.let { runCatching { Pulse.unloadModule(it) } }
        module = null
        talking = false
    }

    /** Keeps the loopback's stream muted unless [talking]; call with each pactl snapshot. */
    fun sync(playback: List<Pulse.Stream>) {
        val id = module ?: return
        for (s in playback) if (s.ownerModule == id && s.muted == talking) runCatching { Pulse.setPlaybackMute(s.index, !talking) }
    }
}

/** A desktop notification, best effort. */
fun notify(title: String, body: String) {
    runCatching { ProcessBuilder("notify-send", "-a", "Talon Party Manager", title, body).start() }
}
