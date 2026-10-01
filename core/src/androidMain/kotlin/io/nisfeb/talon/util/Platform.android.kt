package io.nisfeb.talon.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun secureRandomBytes(n: Int): ByteArray =
    ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

actual fun timeZoneShortLabel(zoneId: String, atMs: Long): String {
    val tz = java.util.TimeZone.getTimeZone(zoneId)
    return tz.getDisplayName(
        tz.inDaylightTime(java.util.Date(atMs)),
        java.util.TimeZone.SHORT,
        java.util.Locale.getDefault(),
    )
}

// No desktop keyboard; Cmd-modifier logic never applies on Android.
actual val isMacOsHost: Boolean = false

actual val tempDirPath: String =
    System.getProperty("java.io.tmpdir")?.trimEnd('/') ?: "/data/local/tmp"

actual val cacheDirPath: String get() = System.getProperty("java.io.tmpdir")?.trimEnd('/') ?: tempDirPath

// The runtime sets java.io.tmpdir to the app's cache dir,
// /data/user/<n>/<package>/cache; its files dir is the sibling.
actual val dataDirPath: String get() =
    java.io.File(cacheDirPath).parentFile?.let { java.io.File(it, "files").absolutePath } ?: cacheDirPath
