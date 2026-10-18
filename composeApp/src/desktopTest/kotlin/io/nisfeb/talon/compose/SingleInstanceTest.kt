package io.nisfeb.talon.compose

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A failing lock call used to say "already running" and exit. */
class SingleInstanceTest {
    @Test fun `a lock held elsewhere is another talon`() {
        assertNull(SingleInstance.takeLock { null }.getOrThrow())
        assertNull(SingleInstance.takeLock { throw OverlappingFileLockException() }.getOrThrow())
    }

    @Test fun `a lock call that fails is not another talon`() {
        assertTrue(SingleInstance.takeLock { throw IOException("No locks available") }.isFailure)
    }

    @Test fun `a free lock is taken`() {
        val f = java.io.File.createTempFile("talon-lock", ".lock")
        RandomAccessFile(f, "rw").use { raf ->
            val lock = SingleInstance.takeLock { raf.channel.tryLock() }.getOrThrow()
            assertNotNull(lock)
            lock.release()
        }
        f.delete()
    }
}
