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

actual val isMacOsHost: Boolean = run {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    "mac" in os || "darwin" in os
}

actual val tempDirPath: String =
    System.getProperty("java.io.tmpdir")?.trimEnd('/') ?: "/tmp"
