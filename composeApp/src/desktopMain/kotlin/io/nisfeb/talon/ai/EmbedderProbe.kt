package io.nisfeb.talon.ai

import io.nisfeb.talon.util.AppDirs
import io.nisfeb.talon.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Decides whether the on-device embedder is safe to run on THIS desktop
 * host. DJL's HuggingFace Rust tokenizer (pulled in by the ONNX engine)
 * SIGSEGVs against some Linux libstdc++ ABIs — a hard JVM abort that
 * can't be caught in-process. It runs fine on most modern distros
 * (verified on Arch/Manjaro libstdc++ 6.0.35); the one confirmed crash
 * was a Mageia/OpenMandriva host. So we don't trust a static flag — we
 * probe in a *child* JVM once: a clean exit means safe, a crash/error
 * means unsafe. The verdict is cached so the probe (and ~80 MB model
 * download) happens at most once per [PROBE_VERSION].
 *
 * The verdict takes effect on the NEXT launch: [cachedVerdict] is read
 * when the capability flag initialises, while [probeIfUnknown] writes
 * the file on a background thread. First run after upgrade is therefore
 * conservative (lexical-only search, flat assistant history); a good
 * host upgrades to full semantic search + topic-grouping next start.
 */
object EmbedderProbe {
    private const val TAG = "EmbedderProbe"

    // Bump when the model or engine changes so a stale "bad" verdict
    // from an older embedder build gets re-probed rather than trusted.
    private const val PROBE_VERSION = 1
    private const val PROBE_TIMEOUT_MS = 180_000L // cold run downloads the model

    private val file: File get() = File(AppDirs.userData, "embedder_probe")
    private val okMarker get() = "v$PROBE_VERSION:ok"
    private val versionPrefix get() = "v$PROBE_VERSION:"

    /** True only if a prior probe of the current version succeeded. */
    fun cachedVerdict(): Boolean = read() == okMarker

    private fun read(): String? =
        runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

    /**
     * Run the child-process probe if this version hasn't been decided
     * yet. Fire-and-forget on a daemon thread — never blocks startup.
     */
    fun probeIfUnknown() {
        if (read()?.startsWith(versionPrefix) == true) return
        thread(isDaemon = true, name = "embedder-probe") {
            val ok = runProbe()
            runCatching {
                AppDirs.userData.mkdirs()
                file.writeText(versionPrefix + if (ok) "ok" else "bad")
            }
            Log.i(TAG, "verdict: ${if (ok) "ok" else "bad"} (takes effect next launch)")
        }
    }

    /** Spawn a fresh JVM that does one embed and exits 0 on success.
     *  A native SIGSEGV kills that child (non-zero / signal), leaving
     *  this process untouched — which is the whole point. */
    private fun runProbe(): Boolean = runCatching {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cp = System.getProperty("java.class.path") ?: return false
        val proc = ProcessBuilder(javaBin, "-cp", cp, "io.nisfeb.talon.ai.EmbedderProbeMainKt")
            .redirectErrorStream(true)
            .start()
        if (!proc.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            return false
        }
        proc.exitValue() == 0
    }.getOrElse {
        Log.w(TAG, "probe spawn failed: ${it.message}")
        false
    }
}
