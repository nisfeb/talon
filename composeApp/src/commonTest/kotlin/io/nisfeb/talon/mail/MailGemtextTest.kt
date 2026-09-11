package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A note is a copy outside the system that checked the signature, so
 * whatever it drops is dropped for good. These pin the parts that must
 * survive being filed.
 */
class MailGemtextTest {

    private val name: (String) -> String = { it }
    private val at: (Long) -> String = { "some time ago" }

    private fun msg(
        id: String,
        verdict: Verdict = Verdict.VERIFIED,
        prev: String? = null,
        sent: Long = 1,
        body: String = "the text",
        subject: String = "Plans",
        to: List<String> = listOf("~nec"),
        attachments: List<Attachment> = emptyList(),
        bodyMime: String = "",
    ) = MailMessage(
        id = id, from = "~zod", to = to, subject = subject, body = body,
        bodyMime = bodyMime, sent = sent, prev = prev, attachments = attachments,
        verdict = verdict,
    )

    @Test
    fun `a forgery cannot be filed without the word on it`() {
        val out = MailGemtext.message(msg("f", verdict = Verdict.FORGED), name, at)
        assertTrue("FORGED" in out, out)
        assertTrue("evidence" in out)
    }

    @Test
    fun `an unchecked signature is not reported as an accusation`() {
        val out = MailGemtext.message(msg("u", verdict = Verdict.UNVERIFIED), name, at)
        assertTrue("NOT checked" in out)
        assertFalse("FORGED" in out, "no key held is not a forgery")
    }

    @Test
    fun `an ordinary verdict is stated too`() {
        // A note that only marks the bad ones cannot be told apart from
        // one written before anybody was checking.
        val out = MailGemtext.message(msg("v"), name, at)
        assertTrue("Signature checked" in out, out)
    }

    @Test
    fun `the body goes in verbatim`() {
        val out = MailGemtext.message(msg("v", body = "line one\nline two"), name, at)
        assertTrue("line one\nline two" in out)
    }

    @Test
    fun `a rendering request is reported and not honoured`() {
        val out = MailGemtext.message(
            msg("h", body = "<b>bold</b>", bodyMime = "text/html"),
            name,
            at,
        )
        assertTrue("asked for text/html" in out)
        assertTrue("<b>bold</b>" in out, "the signed text, not an interpretation of it")
    }

    @Test
    fun `attachments are the author's claims, and say so`() {
        val out = MailGemtext.message(
            msg("a", attachments = listOf(Attachment("notes.txt", 12, "text/plain", "0vhash"))),
            name,
            at,
        )
        assertTrue("by the author's account" in out)
        assertTrue("notes.txt" in out)
        assertTrue("0vhash" in out, "the address is the only checkable part")
    }

    @Test
    fun `a thread keeps every message's own verdict`() {
        val t = MailThread(
            id = "0vt",
            participants = listOf("~zod", "~nec"),
            messages = listOf(
                msg("root", sent = 1),
                msg("bad", prev = "root", sent = 2, verdict = Verdict.FORGED),
            ),
        )
        val out = MailGemtext.thread(t, name, at)
        assertTrue("Signature checked" in out)
        assertTrue("FORGED" in out, "one bad copy in a thread must not be smoothed over")
    }

    @Test
    fun `a branching thread says the order is not the shape`() {
        val t = MailThread(
            id = "0vt",
            messages = listOf(
                msg("root", sent = 1),
                msg("a", prev = "root", sent = 2),
                msg("b", prev = "root", sent = 3),
            ),
        )
        assertTrue("branches" in MailGemtext.thread(t, name, at))
    }

    @Test
    fun `a straight thread claims nothing about branches`() {
        val t = MailThread(
            id = "0vt",
            messages = listOf(msg("root", sent = 1), msg("a", prev = "root", sent = 2)),
        )
        assertFalse("branches" in MailGemtext.thread(t, name, at))
    }

    @Test
    fun `copies the build cannot read are declared as missing`() {
        val t = MailThread(id = "0vt", messages = listOf(msg("root")), unreadable = 2)
        val out = MailGemtext.thread(t, name, at)
        assertTrue("2 copies" in out)
        assertTrue("not below" in out, "a note has to say what it does not contain")
    }

    @Test
    fun `one message and a whole thread file to different notes`() {
        assertEquals("0vt", MailGemtext.seedFor("0vt", null))
        assertEquals("0vt/0vm", MailGemtext.seedFor("0vt", "0vm"))
    }
}
