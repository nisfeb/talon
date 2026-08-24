package io.nisfeb.talon.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.time.Clock

/**
 * Multiplatform replacements for JVM-only primitives that commonMain
 * used to reach for directly (System.currentTimeMillis, Dispatchers.IO).
 * Every target must resolve these — JVM backends map to the same
 * behaviour they had before; iOS supplies a native equivalent.
 *
 * See CLAUDE.md "Same-shape interface in common, impl per leaf".
 */

/** Wall-clock epoch millis. Common replacement for System.currentTimeMillis(). */
fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Dispatcher for blocking / IO-bound work. On JVM this is
 * Dispatchers.IO; iOS has no IO dispatcher so it falls back to a
 * parallelism-limited Default (see the iosMain actual).
 */
expect val ioDispatcher: CoroutineDispatcher

/**
 * Exception handler for the app's long-lived background scopes
 * (repo session loop, push relay, iOS root scope). On JVM an uncaught
 * coroutine exception logs and the app lives; on Kotlin/Native the
 * default handler calls terminate — Apple review hit exactly that as
 * an instant SIGABRT crash-loop. SupervisorJob scopes already treat
 * failures as per-job, so logging is the intended behaviour everywhere.
 */
val backgroundExceptionHandler = CoroutineExceptionHandler { _, t ->
    Log.e("BackgroundScope", "uncaught coroutine exception", t)
}

/**
 * Cryptographically-secure random bytes. Backs security-sensitive
 * tokens (E2EE room keys) so it must NOT be kotlin.random.Random —
 * JVM uses SecureRandom, iOS uses SecRandomCopyBytes.
 */
expect fun secureRandomBytes(n: Int): ByteArray

/**
 * Short zone abbreviation (e.g. "EDT", "PDT") for [zoneId] at the given
 * instant — kotlinx-datetime only exposes offsets, not the tz database's
 * short names. JVM uses java.util.TimeZone; iOS uses NSTimeZone. Falls
 * back to the zone id when no abbreviation is available.
 */
expect fun timeZoneShortLabel(zoneId: String, atMs: Long): String

/**
 * True when the desktop host is macOS — drives the Cmd-vs-Ctrl keyboard
 * modifier in App. JVM reads `os.name`; Android and iOS return false
 * (no hardware Cmd key in play). Replaces a commonMain
 * System.getProperty("os.name") call.
 */
expect val isMacOsHost: Boolean

/**
 * Platform temp directory as an absolute path (no trailing slash
 * required). JVM uses `java.io.tmpdir`; iOS uses NSTemporaryDirectory().
 * Backs [createTempFileUri].
 */
expect val tempDirPath: String
