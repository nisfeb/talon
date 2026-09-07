package io.nisfeb.talon.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The PulseAudio side of a party: the virtual devices and `pactl`
 * stream moves that connect the bridge, a Space app and anything else
 * (Spotify, a soundboard) to each other. Linux with PulseAudio or
 * PipeWire-Pulse only; [available] says whether `pactl` answers.
 *
 * Who hears what:
 *  - audio sent to [SPACE] reaches the party line (the bridge captures
 *    its monitor and sends it up);
 *  - audio sent to [MIC] reaches the Space (the Space app captures its
 *    monitor as its microphone);
 *  - audio sent to [BOTH] reaches both.
 */
object Pulse {
    const val MIC = "TalonBridgeMic"
    const val SPACE = "TalonBridgeSpace"
    const val BOTH = "TalonBridgeBoth"
    const val MIC_MONITOR = "$MIC.monitor"
    const val SPACE_MONITOR = "$SPACE.monitor"
    const val DEFAULT_SINK = "@DEFAULT_SINK@"
    const val DEFAULT_SOURCE = "@DEFAULT_SOURCE@"

    data class Device(val index: Int, val name: String, val description: String)

    /** One playing (sink-input) or recording (source-output) stream. */
    data class Stream(
        val index: Int,
        val app: String,
        val pid: Long?,
        val target: String,
        val corked: Boolean = false,
        /** Stream volume in percent; Pulse allows above 100. */
        val volume: Int = 100,
    ) {
        /** Chromium names its capture "Brave input"; the app is Brave. */
        val appName: String get() = app.removeSuffix(" input")

        /** Same program: by process when Pulse knows it, else by name. */
        fun sameApp(other: Stream): Boolean =
            if (pid != null && other.pid != null) pid == other.pid else appName == other.appName
    }

    val available: Boolean by lazy { runCatching { pactl("--version") }.isSuccess }

    fun sinks(): List<Device> = parseDevices(pactl("-f", "json", "list", "sinks"))
    fun sources(): List<Device> = parseDevices(pactl("-f", "json", "list", "sources"))
    fun playback(): List<Stream> = parseStreams(pactl("-f", "json", "list", "sink-inputs"), "sink", sinks())
    fun capture(): List<Stream> = parseStreams(pactl("-f", "json", "list", "source-outputs"), "source", sources())

    fun hasDevices(sinks: List<Device> = sinks()): Boolean =
        sinks.map { it.name }.containsAll(listOf(MIC, SPACE, BOTH))

    /** Loads the null sinks and the combine sink that are missing. Idempotent. */
    fun ensureDevices() {
        val have = sinks().map { it.name }.toSet()
        fun nullSink(name: String) = pactl(
            "load-module", "module-null-sink", "sink_name=$name",
            "sink_properties=device.description=$name", "rate=48000", "channels=1",
        )
        if (MIC !in have) nullSink(MIC)
        if (SPACE !in have) nullSink(SPACE)
        if (BOTH !in have) {
            pactl(
                "load-module", "module-combine-sink", "sink_name=$BOTH",
                "slaves=$MIC,$SPACE", "sink_properties=device.description=$BOTH",
            )
        }
    }

    fun movePlayback(index: Int, sink: String) { pactl("move-sink-input", "$index", sink) }
    fun moveCapture(index: Int, source: String) { pactl("move-source-output", "$index", source) }
    fun setPlaybackVolume(index: Int, percent: Int) { pactl("set-sink-input-volume", "$index", "$percent%") }
    fun setCaptureVolume(index: Int, percent: Int) { pactl("set-source-output-volume", "$index", "$percent%") }

    /**
     * Puts this process's own WebRTC streams where the bridge needs
     * them: playout to [MIC], capture from [SPACE_MONITOR]. Returns
     * true once both streams exist and sit there.
     */
    fun routeOwn(pid: Long = ProcessHandle.current().pid()): Boolean {
        val mine = playback().filter { it.pid == pid }
        mine.filter { it.target != MIC }.forEach { movePlayback(it.index, MIC) }
        val ours = capture().filter { it.pid == pid }
        ours.filter { it.target != SPACE_MONITOR }.forEach { moveCapture(it.index, SPACE_MONITOR) }
        return mine.isNotEmpty() && ours.isNotEmpty()
    }

    /** Plays a wav/ogg/flac clip into [sink] at [percent] volume; the process ends with the clip. */
    fun play(file: File, sink: String, percent: Int = 100): Process =
        ProcessBuilder("paplay", "--device=$sink", "--volume=${percent * 65536 / 100}", file.path)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

    internal fun parseDevices(json: String): List<Device> =
        Json.parseToJsonElement(json).jsonArray.map { e ->
            val o = e.jsonObject
            Device(
                index = o.getValue("index").jsonPrimitive.int,
                name = o.getValue("name").jsonPrimitive.content,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }

    internal fun parseStreams(json: String, deviceKey: String, devices: List<Device>): List<Stream> {
        val names = devices.associate { it.index to it.name }
        return Json.parseToJsonElement(json).jsonArray.map { e ->
            val o = e.jsonObject
            val props = o["properties"]?.jsonObject
            fun prop(k: String) = props?.get(k)?.jsonPrimitive?.contentOrNull
            val dev = o[deviceKey]?.jsonPrimitive?.intOrNull
            Stream(
                index = o.getValue("index").jsonPrimitive.int,
                app = prop("application.name") ?: prop("media.name") ?: "?",
                pid = prop("application.process.id")?.toLongOrNull(),
                target = names[dev] ?: dev?.toString() ?: "?",
                corked = o["corked"]?.jsonPrimitive?.booleanOrNull ?: false,
                volume = o["volume"]?.jsonObject?.values?.firstOrNull()?.jsonObject
                    ?.get("value_percent")?.jsonPrimitive?.contentOrNull
                    ?.removeSuffix("%")?.trim()?.toIntOrNull() ?: 100,
            )
        }
    }

    private fun pactl(vararg args: String): String {
        val p = ProcessBuilder("pactl", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "pactl ${args.joinToString(" ")}: ${out.trim()}" }
        return out
    }
}
