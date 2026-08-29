package io.nisfeb.talon.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName
import platform.posix.arc4random_buf

// kotlinx-coroutines 1.8.1 has no Dispatchers.IO on native; Default is
// the portable choice. Ktor Darwin is async (NSURLSession) and Room
// native manages its own threads, so the only blocking work routed here
// is small JSON-settings file IO, which Default handles fine.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    val bytes = ByteArray(n)
    if (n == 0) return bytes
    bytes.usePinned { pinned ->
        // arc4random_buf is Darwin's CSPRNG — no seeding, no failure mode.
        arc4random_buf(pinned.addressOf(0), n.convert())
    }
    return bytes
}

actual fun timeZoneShortLabel(zoneId: String, atMs: Long): String {
    val tz = NSTimeZone.timeZoneWithName(zoneId) ?: return zoneId
    val date = NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0)
    return tz.abbreviationForDate(date) ?: zoneId
}

// iOS is not a macOS desktop host; Cmd-modifier logic never applies.
actual val isMacOsHost: Boolean = false

actual val tempDirPath: String = NSTemporaryDirectory().trimEnd('/')
