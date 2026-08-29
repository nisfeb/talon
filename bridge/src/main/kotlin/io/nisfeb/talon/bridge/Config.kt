package io.nisfeb.talon.bridge

import java.io.File
import java.util.Properties

/**
 * Everything the bridge needs to start, and nothing it can guess.
 *
 * Read from the environment first, then a properties file, so the
 * daemon runs from a systemd unit with `Environment=` lines or from
 * a file next to the binary — and so the ship's +code never has to
 * sit in a shell history or a process listing.
 */
data class Config(
    /** The bridge's own ship, e.g. https://bridge.example.com */
    val shipUrl: String,
    /** That ship's +code. */
    val shipCode: String,
    /** Who hosts the party line, e.g. ~ricsul-bilwyt */
    val host: String,
    /** The line's name on that host. */
    val room: String,
    /**
     * Capture device whose audio is spoken into the line, or null.
     *
     * Takes precedence over [play]: a live source is what someone
     * configured a device for, and playing a file at the same time
     * would talk over it.
     */
    val audioIn: String?,
    /** Playback device the line is played out to, or null. */
    val audioOut: String?,
    /** WAV played into the line, or null to listen only. */
    val play: File?,
    /** Whether [play] repeats when it ends. */
    val loop: Boolean,
    /** WAV the line is recorded to, or null to speak only. */
    val record: File?,
) {
    companion object {
        /**
         * Sources, in order of precedence: the environment, then
         * [file] if it exists.
         *
         * TALON_BRIDGE_SHIP_URL, _SHIP_CODE, _HOST, _ROOM, _AUDIO_IN,
         * _AUDIO_OUT, _PLAY, _LOOP, _RECORD — or the same names
         * lowercased with dots in a properties file
         * (talon.bridge.ship.url, …).
         */
        fun load(file: File = File("bridge.properties")): Config {
            val props = Properties().apply {
                if (file.isFile) file.inputStream().use { load(it) }
            }

            fun get(key: String): String? =
                System.getenv("TALON_BRIDGE_$key")?.takeIf { it.isNotBlank() }
                    ?: props.getProperty("talon.bridge." + key.lowercase().replace('_', '.'))
                        ?.takeIf { it.isNotBlank() }

            fun need(key: String): String = get(key)
                ?: error(
                    "missing TALON_BRIDGE_$key " +
                        "(or talon.bridge.${key.lowercase().replace('_', '.')} in ${file.name})",
                )

            val play = get("PLAY")?.let(::File)
            val record = get("RECORD")?.let(::File)
            val audioIn = get("AUDIO_IN")
            val audioOut = get("AUDIO_OUT")
            require(play != null || record != null || audioIn != null || audioOut != null) {
                "set TALON_BRIDGE_AUDIO_IN / _AUDIO_OUT (to relay a device) or " +
                    "_PLAY / _RECORD (to use files) — a bridge that neither " +
                    "speaks nor listens has nothing to do"
            }
            require(play == null || play.isFile) { "no such file: $play" }

            val host = need("HOST").let { if (it.startsWith("~")) it else "~$it" }
            return Config(
                shipUrl = need("SHIP_URL"),
                shipCode = need("SHIP_CODE"),
                host = host,
                room = need("ROOM"),
                audioIn = audioIn,
                audioOut = audioOut,
                play = play,
                loop = get("LOOP")?.lowercase() in setOf("1", "true", "yes"),
                record = record,
            )
        }
    }

    /** Safe to print: the +code is the one thing that must not leak. */
    override fun toString() =
        "Config(ship=$shipUrl, line=$host/$room, " +
            "in=${audioIn ?: play?.toString() ?: "—"}${if (play != null && loop) " (looping)" else ""}, " +
            "out=${listOfNotNull(audioOut, record?.toString()).joinToString(" + ").ifEmpty { "—" }})"
}
