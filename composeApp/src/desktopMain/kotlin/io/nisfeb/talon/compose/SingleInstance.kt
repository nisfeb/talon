package io.nisfeb.talon.compose

import io.nisfeb.talon.util.AppDirs
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Process-wide single-instance enforcement for the desktop build.
 *
 * Why we need this: each AppImage launch mounts at its own
 * `/tmp/.mount_Talon-XXXXXX/` and starts a fresh JVM with its own
 * DJL ONNX runtime, its own Room session, and its own SSE channel.
 * Two+ instances racing on the same `~/.djl.ai/` extracted natives
 * have produced silent embedder failures
 * (`Could not initialize class ai.djl.onnxruntime.engine.OrtNDManager`)
 * and a 2-minute reconnect storm where each instance's watchdog
 * keeps the others' SSE channels in churn. The user found themselves
 * with 10 concurrent Talons consuming 6+ GB.
 *
 * Mechanism: an OS-level advisory lock on
 * `~/.config/talon/.instance.lock` (or the equivalent platform path)
 * via `FileChannel.tryLock`. The OS releases the lock when the
 * holding process exits, even on crash or kill -9 — no stale-PID
 * cleanup needed. Held for the JVM's lifetime via a process-wide
 * reference that the GC can't collect.
 *
 * If the lock can't be acquired, [acquireOrExit] shows a brief
 * dialog and exits with code 0 (not an error — there's already a
 * good Talon running). If anything goes wrong getting to that point
 * (read-only home, weird FS), we log and let the launch proceed
 * rather than locking the user out of their app over a diagnostic
 * concern.
 */
object SingleInstance {

    private var heldLock: FileLock? = null
    private var heldChannel: FileChannel? = null

    /**
     * Acquire the lock or exit the JVM with a "Talon is already
     * running" dialog. Call this first thing in `main()` — before any
     * heavy initialization that would race with the other instance
     * (DJL native extraction, SQLite open, etc.).
     */
    fun acquireOrExit() {
        val lockFile = runCatching {
            File(AppDirs.userData, ".instance.lock").also { f ->
                if (!f.exists()) f.createNewFile()
            }
        }.getOrNull() ?: return // user-data dir unwritable; let launch proceed

        val raf = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull() ?: return
        val channel = raf.channel
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Throwable) {
            // Don't lock the user out over a lock-system quirk.
            null
        }
        if (lock == null) {
            // Another instance holds the lock. Tell the user and exit
            // cleanly. exit code 0 because this is the right outcome,
            // not an error.
            runCatching { channel.close() }
            runCatching { raf.close() }
            showAlreadyRunningDialog()
            kotlin.system.exitProcess(0)
        }
        // Hold the references so neither the channel nor the lock
        // gets garbage-collected (which would release the lock
        // prematurely). The OS releases on process exit.
        heldLock = lock
        heldChannel = channel
    }

    private fun showAlreadyRunningDialog() {
        runCatching {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "Talon is already running. Look for the open window or check " +
                    "your taskbar / dock.",
                "Talon",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }
}
