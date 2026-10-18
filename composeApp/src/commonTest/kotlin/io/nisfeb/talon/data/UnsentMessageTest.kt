package io.nisfeb.talon.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which of our messages are deleted here only: the ones the ship never took (review A4-1). */
class UnsentMessageTest {
    private fun row(id: String, status: String?) = MessageEntity("~bus", id, "~zod", 1, "[]", "/chat", status = status)

    @Test
    fun `a stand-in or one waiting or refused is unsent`() {
        assertTrue(row("local_1", null).isUnsent(), "a stand-in, whatever its state")
        assertTrue(row("local_1", "pending").isUnsent())
        assertTrue(row("~zod/1", "queued").isUnsent())
        assertTrue(row("~zod/1", "failed").isUnsent())
    }

    @Test
    fun `one in flight under its own id or sent is not`() {
        assertFalse(row("~zod/1", "pending").isUnsent(), "on its way under its own id")
        assertFalse(row("~zod/1", null).isUnsent(), "sent")
        assertFalse(row("170141184506", null).isUnsent(), "the ship's own id")
    }
}
