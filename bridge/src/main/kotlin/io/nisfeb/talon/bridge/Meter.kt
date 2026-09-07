package io.nisfeb.talon.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Live level of one Pulse source, read through `parec` at 8 kHz mono
 * in 100 ms windows. [level] is 0..1 on a 60 dB scale; [heardAgoMs]
 * says how long since the source was last clearly audible, or -1 if
 * it never was. Two of these on the virtual monitors are what makes
 * "the Space can hear the party" visible instead of a matter of faith.
 */
class LevelMeter(private val source: String) : AutoCloseable {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var heardAt = 0L
    val heardAgoMs: Long get() = if (heardAt == 0L) -1 else System.currentTimeMillis() - heardAt

    private var process: Process? = null
    private val thread = Thread(::run, "meter:$source").apply { isDaemon = true }

    fun start() = apply { thread.start() }

    private fun run() {
        val p = runCatching {
            ProcessBuilder(
                "parec", "--device=$source", "--raw", "--format=s16le",
                "--channels=1", "--rate=8000", "--latency-msec=50",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return
        process = p
        val buf = ByteArray(1600)
        val input = p.inputStream
        while (!Thread.currentThread().isInterrupted) {
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                if (n < 0) return
                got += n
            }
            var sum = 0.0
            var i = 0
            while (i < buf.size) {
                val s = (((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()).toDouble()
                sum += s * s
                i += 2
            }
            val rms = sqrt(sum / (buf.size / 2))
            val db = 20 * log10(rms / 32768.0 + 1e-9)
            val v = ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
            if (v > 0.2f) heardAt = System.currentTimeMillis()
            _level.value = v
        }
    }

    override fun close() {
        thread.interrupt()
        process?.destroy()
    }
}
