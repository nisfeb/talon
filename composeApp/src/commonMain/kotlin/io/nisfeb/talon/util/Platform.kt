package io.nisfeb.talon.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.Clock

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
 * Cryptographically-secure random bytes. Backs security-sensitive
 * tokens (E2EE room keys) so it must NOT be kotlin.random.Random —
 * JVM uses SecureRandom, iOS uses SecRandomCopyBytes.
 */
expect fun secureRandomBytes(n: Int): ByteArray
