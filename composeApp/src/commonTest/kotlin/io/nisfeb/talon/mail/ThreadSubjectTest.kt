package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A thread is called what its row in the listing is called: the newest
 * message that is not forged. And a long list of names folds.
 */
class ThreadSubjectTest {
    private fun m(id: String, subject: String, verdict: Verdict = Verdict.VERIFIED) =
        MailMessage(id = id, from = "~zod", subject = subject, verdict = verdict)

    @Test
    fun `a subject changed in a reply names the thread`() {
        assertEquals("Plans, now Friday", threadSubject(listOf(m("a", "Plans"), m("b", "Plans, now Friday"))))
    }

    @Test
    fun `a forged copy never names it, unless every copy is forged`() {
        assertEquals("Plans", threadSubject(listOf(m("a", "Plans"), m("b", "Click here", Verdict.FORGED))))
        assertEquals("Click here", threadSubject(listOf(m("b", "Click here", Verdict.FORGED))))
        assertEquals("", threadSubject(emptyList()))
    }

    @Test
    fun `a long list folds to five and a count, and opens whole`() {
        val names = (1..9).map { "~ship$it" }
        assertEquals(names.take(5) to 4, foldNames(names, open = false))
        assertEquals(names to 0, foldNames(names, open = true))
        // Folding away a single name hides nothing worth the click.
        assertEquals(names.take(6) to 0, foldNames(names.take(6), open = false))
    }
}
