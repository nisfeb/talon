package io.nisfeb.talon.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun secureRandomBytes(n: Int): ByteArray =
    ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
