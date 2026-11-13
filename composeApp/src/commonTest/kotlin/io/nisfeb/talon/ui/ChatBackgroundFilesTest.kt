package io.nisfeb.talon.ui

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The picture behind the bubbles, kept on this device: one at a time, whole. */
class ChatBackgroundFilesTest {
    private val fs = FileSystem.SYSTEM
    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "talon-chat-background-${Random.nextLong().toULong()}"
    private val files = ChatBackgroundFiles(dir)

    @AfterTest
    fun clean() = fs.deleteRecursively(dir)

    private fun kept() = fs.list(dir).map { it.toString() }

    @Test
    fun `a picture is kept whole, a new one replaces it, and removing leaves none`() {
        val first = files.write(byteArrayOf(1, 2, 3)).also { files.keepOnly(it) }
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read(first.toPath()) { readByteArray() })
        val second = files.write(byteArrayOf(4, 5)).also { files.keepOnly(it) }
        // Named by what it holds: a new picture is a new path, so nothing
        // cached by the old path shows the old picture.
        assertNotEquals(first, second)
        assertEquals(listOf(second), kept(), "the one replaced goes, and no part file is left")
        files.keepOnly("")
        assertEquals(emptyList(), kept())
    }

    @Test
    fun `the same picture again is the same file`() {
        assertEquals(files.write(byteArrayOf(7)), files.write(byteArrayOf(7)))
    }
}
