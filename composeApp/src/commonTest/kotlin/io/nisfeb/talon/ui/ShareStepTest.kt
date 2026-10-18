package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * B3-2: a share whose chat the share sheet named (any app can name one)
 * waits for the owner's Send. Android's TalonApp acts on [shareStep].
 */
class ShareStepTest {
    @Test
    fun `a direct share never posts by itself`() {
        assertEquals(ShareStep.Stage, shareStep("~bus", text = false, direct = true), "a file waits in the composer")
        assertEquals(ShareStep.Stage, shareStep("chat/~bus/crew", text = false, direct = true))
        assertEquals(ShareStep.Draft, shareStep("~bus", text = true, direct = true), "text goes into the draft")
        assertEquals(ShareStep.Pick, shareStep("heap/~bus/pics", text = false, direct = true), "a gallery has no composer")
        assertEquals(ShareStep.Pick, shareStep("heap/~bus/pics", text = true, direct = true))
    }

    @Test
    fun `a chat picked in Talon takes the share as before`() {
        assertEquals(ShareStep.Post, shareStep("~bus", text = false, direct = false))
        assertEquals(ShareStep.Draft, shareStep("~bus", text = true, direct = false))
        assertEquals(ShareStep.Post, shareStep("heap/~bus/pics", text = false, direct = false))
        assertEquals(ShareStep.Post, shareStep("heap/~bus/pics", text = true, direct = false), "a gallery post of the text")
    }

    @Test
    fun `a shared file is taken once, by its own chat`() {
        val cat = PendingAttachment(byteArrayOf(1), "image/png", "cat.png", isImage = true)
        SharedAttachments.put("~bus", cat)
        assertNull(SharedAttachments.peek("~nec"), "another chat's")
        assertSame(cat, SharedAttachments.peek("~bus"))
        assertSame(cat, SharedAttachments.take("~bus"))
        assertNull(SharedAttachments.take("~bus"), "taken once")
        assertNull(SharedAttachments.peek("~bus"))
    }
}
