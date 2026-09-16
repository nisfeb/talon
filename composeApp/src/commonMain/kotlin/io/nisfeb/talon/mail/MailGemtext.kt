package io.nisfeb.talon.mail

/**
 * Signed mail, rendered as gemtext for Lattice.
 *
 * The rule that shapes all of this: a verdict travels with the message
 * it belongs to. A note is a copy outside the system that checked the
 * signature, so anything it loses is lost for good, and a forgery that
 * arrives in somebody's notes without the word on it has been laundered
 * by the act of filing it.
 *
 * Bodies go in verbatim. They are plain text and the author's rendering
 * instruction is only ever reported, so there is nothing to interpret.
 */
object MailGemtext {

    /** One message, as its own note. */
    fun message(m: MailMessage, nameFor: (String) -> String, when_: (Long) -> String): String =
        buildString {
            append("# ").append(m.subject.ifBlank { "(no subject)" }).append("\n\n")
            append(provenance(m, nameFor, when_))
            append("\n")
            append(body(m))
            append(attachments(m))
        }.trimEnd() + "\n"

    /**
     * A whole thread, in send order.
     *
     * The note says how it was read, because a tree flattened into a
     * document loses which reply answered which. A reader who needs
     * that has to go back to the mail, and saying so is better than
     * quietly presenting a branch as a sequence.
     */
    fun thread(
        t: MailThread,
        nameFor: (String) -> String,
        when_: (Long) -> String,
    ): String = buildString {
        val shown = collapse(t.messages)
        val subject = shown.firstOrNull()?.subject?.ifBlank { null } ?: "(no subject)"
        append("# ").append(subject).append("\n\n")
        // Messages, not copies: several grubs under one id are one
        // message, and counting copies would overstate the conversation.
        append("Signed mail, ")
            .append(shown.size)
            .append(if (shown.size == 1) " message" else " messages")
        if (t.participants.isNotEmpty()) {
            append(", between ").append(t.participants.joinToString { nameFor(it) })
        }
        append(".\n")
        if (t.unreadable > 0) {
            append("\n")
            append(unreadableNote(t.unreadable))
            append("\n")
        }
        val branching = branches(threadTree(t.messages))
        if (branching) {
            append(
                "\nThis conversation branches. Written out in order below, " +
                    "which does not show which reply answered which.\n",
            )
        }
        val counts = copyCounts(t.messages)
        for (m in collapse(t.messages).sortedWith(compareBy({ it.sent }, { it.id }))) {
            append("\n## ").append(nameFor(m.from))
            append(" · ").append(when_(m.sent))
            append("\n\n")
            append(provenance(m, nameFor, when_, short = true))
            val n = counts[m.id] ?: 1
            if (n > 1) {
                append(n).append(" stored copies of this message; the verdict above ")
                    .append("is the strongest among them.\n")
            }
            append("\n")
            append(body(m))
            append(attachments(m))
        }
    }.trimEnd() + "\n"

    /**
     * Who wrote it, to whom, and what the ship concluded about the
     * signature. The verdict is never omitted, including when it is the
     * ordinary one: a note that only marks the bad ones cannot be told
     * apart from a note written before anyone was checking.
     */
    private fun provenance(
        m: MailMessage,
        nameFor: (String) -> String,
        when_: (Long) -> String,
        short: Boolean = false,
    ): String = buildString {
        if (!short) {
            append("From ").append(nameFor(m.from))
            if (m.to.isNotEmpty()) append(" to ").append(m.to.joinToString { nameFor(it) })
            append(" · ").append(when_(m.sent)).append("\n")
        } else if (m.to.isNotEmpty()) {
            append("To ").append(m.to.joinToString { nameFor(it) }).append("\n")
        }
        append(verdictLine(m.verdict)).append("\n")
        if (m.bodyMime.isNotBlank() && m.bodyMime != "text/plain") {
            append("The author asked for ").append(m.bodyMime)
                .append("; the text below is what was signed.\n")
        }
    }

    internal fun verdictLine(v: Verdict): String = when (v) {
        Verdict.VERIFIED -> "Signature checked against the author's key."
        // Not an accusation, and the note has to say which it is.
        Verdict.UNVERIFIED ->
            "Signature NOT checked: no key held for that ship at that life."
        Verdict.FORGED ->
            "FORGED: the signature does not match these contents. Kept as evidence."
    }

    internal fun unreadableNote(n: Int): String =
        if (n == 1) "1 copy on the ship is in a form this build cannot read, and is not below."
        else "$n copies on the ship are in a form this build cannot read, and are not below."

    private fun body(m: MailMessage): String =
        m.body.trimEnd().ifBlank { "(no text)" } + "\n"

    private fun attachments(m: MailMessage): String {
        if (m.attachments.isEmpty()) return ""
        return buildString {
            append("\nAttached, by the author's account:\n")
            for (a in m.attachments) {
                // The name and type are the author's claims; only the
                // address is checkable. The note says so rather than
                // presenting them as facts about the bytes.
                append("* ").append(a.name.ifBlank { "(unnamed)" })
                if (a.mime.isNotBlank()) append(" · ").append(a.mime)
                append(" · ").append(a.hash).append("\n")
            }
        }
    }

    /** A slug seed that is stable for a thread and unique per message,
     *  so re-publishing edits in place rather than piling up notes. */
    fun seedFor(threadId: String, messageId: String?): String =
        if (messageId == null) threadId else "$threadId/$messageId"
}
