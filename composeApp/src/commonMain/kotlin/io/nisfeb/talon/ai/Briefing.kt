package io.nisfeb.talon.ai

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.latestPerConversation
import io.nisfeb.talon.orrery.Brief
import io.nisfeb.talon.orrery.Decider
import io.nisfeb.talon.orrery.attempt
import io.nisfeb.talon.ui.icons.ASSISTANT_NAME
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Archie's opener: the day's numbers, said at once, and the rest, written
 * by the model while they are spoken. sneagan, 2026-10-17: "you have 2 new
 * emails and 32 chat messages, 2 of which are questions for you. your
 * first meeting is in about 30 minutes and Andrea is out of town today so
 * you're on drop off duty. shall I read you the full brief?"
 * See docs/superpowers/specs/2026-10-17-voice-briefing-design.md.
 */
object Briefing {
    /** How many unread messages the decision model judges at most. */
    const val MAX_JUDGED = 50

    /** How long the questions may take: fifty of them, more than the calendar's one. */
    const val QUESTIONS_WAIT_MS = 8_000L

    /** The conversation's title in Assistant history: "Archie, Friday 17 October". */
    fun title(day: LocalDate): String =
        "$ASSISTANT_NAME, ${day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.day} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }}"

    /**
     * The numbers, said before any model answers. [mail] null where the
     * inbox could not be listed: never "no new mail" from a list not read.
     * [questions] null where the decision model did not say. [mailLate]
     * where there is mail that has not answered yet: said, not left out.
     */
    fun countsLine(mail: Int?, chats: Int?, questions: Int?, mailLate: Boolean = false): String {
        val m = mail?.takeIf { it > 0 }?.let { if (it == 1) "1 new email" else "$it new emails" }
        val q = questions?.takeIf { it > 0 && chats != null && chats > 0 }?.coerceAtMost(chats ?: 0)
            ?.let { if (it == 1) ", 1 of them a question for you" else ", $it of them questions for you" }.orEmpty()
        val c = chats?.takeIf { it > 0 }?.let { (if (it == 1) "1 chat message" else "$it chat messages") + q }
        val said = when {
            m != null && c != null -> "You have $m and $c."
            m != null -> "You have $m" + if (chats == 0) ", and no new messages." else "."
            c != null -> "You have $c" + if (mail == 0) ", and no new mail." else "."
            mail == 0 && chats == 0 -> "No new mail or messages."
            chats == 0 -> "No new messages."
            mail == 0 -> "No new mail."
            else -> ""
        }
        return if (mailLate && mail == null) "$said $MAIL_LATE".trim() else said
    }

    const val MAIL_LATE = "Your mail has not answered yet."

    /** One unread message for the decision model: who, where, and what they said. */
    data class Said(val from: String, val where: String, val text: String)

    /**
     * Unread messages as the chat list counts them: each conversation the
     * list shows, by its unread count.
     */
    suspend fun chatUnread(db: AppDatabase): Int {
        val shown = db.latestPerConversation().first().mapTo(HashSet()) { it.whom }
        return db.unreads().getAll().filter { it.whom in shown }.sumOf { it.count }
    }

    /**
     * The unread messages themselves, for list_unread: each conversation
     * [chatUnread] counts, newest first, with at most [per] of its unread
     * messages, oldest first, and none of [me]'s own. Only [from]'s, when
     * given. Unread starts at the ship's own first unread message: a
     * conversation's count counts reactions and replies too, so it is the
     * fallback only, and read it would have said seen messages as new.
     */
    suspend fun unread(db: AppDatabase, me: String, per: Int, from: String? = null): List<Pair<String, List<MessageEntity>>> {
        val shown = db.latestPerConversation().first().mapTo(HashSet()) { it.whom }
        val owner = "~" + me.removePrefix("~")
        return db.unreads().getAll().filter { it.count > 0 && it.whom in shown }.sortedByDescending { it.recencyMs }.map { u ->
            val recent = db.messages().latestAnyFor(u.whom, UNREAD_SCAN)
            val first = recent.firstOrNull { it.id == u.firstUnreadId }
            val new = if (first != null) recent.filter { it.sentMs >= first.sentMs } else recent.take(u.count)
            u.whom to new.filter { it.author != owner && (from == null || it.author == from) }.take(per).asReversed()
        }.filter { it.second.isNotEmpty() }
    }

    /** How far back [unread] looks in one conversation. */
    private const val UNREAD_SCAN = 50

    /** Where list_unread's answer gives the conversations' ids, which are not read out. */
    const val UNREAD_IDS = "Conversation ids:"

    /**
     * The unread messages that could ask something of [owner], newest
     * first, at most [MAX_JUDGED]: other people's in DMs and group DMs,
     * and in a channel only those that name the owner.
     */
    suspend fun unreadSaid(db: AppDatabase, owner: String, text: (id: String, contentJson: String) -> String): List<Said> {
        val me = "~" + owner.removePrefix("~")
        val out = mutableListOf<Pair<Long, Said>>()
        for (u in db.unreads().getAll().filter { it.count > 0 }) {
            val channel = '/' in u.whom
            db.messages().latestAnyFor(u.whom, u.count.coerceAtMost(10))
                .filter { it.author != me && !it.isDeleted }
                .forEach { m ->
                    val t = text(m.id, m.contentJson).trim()
                    if (t.isNotEmpty() && (!channel || Regex("""(?<![\w-])${Regex.escape(me)}(?![\w-])""").containsMatchIn(t))) {
                        out += m.sentMs to Said(m.author, u.whom, t.take(500))
                    }
                }
        }
        return out.sortedByDescending { it.first }.take(MAX_JUDGED).map { it.second }
    }

    /** What the decision model reads: the owner and the messages, numbered. */
    fun questionsState(owner: String, said: List<Said>): JsonObject = buildJsonObject {
        put("owner", "~" + owner.removePrefix("~"))
        putJsonArray("messages") {
            said.forEachIndexed { i, s -> add(buildJsonObject { put("n", i); put("from", s.from); put("in", s.where); put("text", s.text) }) }
        }
    }

    /**
     * One yes-or-no per message, each naming its own: asked of several at
     * once without it, the model answers them all alike (Decider.kt's
     * status check, measured 2026-09-19).
     */
    fun questionsAsked(count: Int): JsonObject = buildJsonObject {
        for (i in 0 until count) putJsonObject("q$i") {
            put("type", "noul")
            put("instructions", "Does message n=$i wait for the owner's answer: a question or a request put to them?")
            putJsonObject("criteria") {
                put("true", "it asks the owner something, or asks them to do or decide something, and they have not answered")
                put("false", "news, chatter, thanks, a reply to them, or a question to someone else")
            }
        }
    }

    /** How many of [said] wait for the owner's answer; null where the decision model did not say. */
    suspend fun questions(decider: Decider, owner: String, said: List<Said>): Int? {
        if (said.isEmpty()) return 0
        val d = decider.attempt(questionsState(owner, said), questionsAsked(said.size)).getOrNull() ?: return null
        return said.indices.count { i -> ((d.answers["q$i"] as? JsonObject)?.get("noul") as? JsonPrimitive)?.doubleOrNull?.let { it >= 0.5 } == true }
    }

    /** The opener's own prompt: the rest of the check-in, the counts already said. */
    val openerPrompt: String = DATA_NOT_INSTRUCTIONS + "\n\n" + """
        You are $ASSISTANT_NAME, the owner's assistant, starting a spoken check-in on
        their phone. They have just been told how much mail and chat is new:
        do not say those numbers again. In at most three short sentences,
        under 60 words in all, say what matters next today: their first
        event and how soon it is, and anything in today's brief from orrery
        that changes their day, such as someone away, a drop-off or pick-up
        that falls to them, or a deadline. End by offering to read the full
        brief when there is one today, and otherwise by asking what they
        would like. Plain spoken words: no lists, markdown, @p or tags.
    """.trimIndent()

    /** How soon [ms] from now is, as said: "in about 25 minutes", "in about 2 hours". */
    fun soon(ms: Long): String {
        val min = ((ms + 30_000) / 60_000).coerceAtLeast(1)
        return when {
            min < 2 -> "in a minute"
            min < 90 -> "in about $min minutes"
            else -> "in about ${(min + 30) / 60} hours"
        }
    }

    /** How long the opener waits for the calendar read and the mail listing. */
    const val OPENER_WAIT_MS = 5_000L

    /** The day as $ASSISTANT_NAME opens with it ([day]). */
    class Day(val mail: Int?, val mailLate: Boolean, val chats: Int?, val questions: Int?, val firstEvent: String?, val card: DictationCard)

    /**
     * What $ASSISTANT_NAME opens with: the unread mail (null where the inbox
     * could not be listed, never zero from a list not read), the chat
     * list's unread, how many of those wait for the owner ([decider]; null
     * where it did not say), the next event today, and the card, today's
     * events and unread mail, which [seen] is given too. [calendarRead] is
     * waited for, a while at most, before the calendar's rows are read;
     * [zone] after it, since the calendar's own zone may come with it.
     */
    suspend fun day(
        db: AppDatabase,
        owner: String,
        mail: io.nisfeb.talon.mail.MailRepo?,
        calendar: io.nisfeb.talon.calendar.CalendarRepo?,
        decider: Decider?,
        zone: () -> kotlinx.datetime.TimeZone,
        twentyFour: Boolean,
        name: (String) -> String,
        seen: CardSeen,
        calendarRead: suspend () -> Unit = {},
    ): Day = kotlinx.coroutines.coroutineScope {
        val inbox = async { runCatching { mail?.listing(io.nisfeb.talon.mail.MailView.INBOX) }.getOrNull() }
        val questions = async {
            if (decider == null) null
            else runCatching { questions(decider, owner, unreadSaid(db, owner, io.nisfeb.talon.urbit.StoryCache::textFor)) }.getOrNull()
        }
        val chats = runCatching { chatUnread(db) }.getOrNull()
        // Capped: a slow calendar or mail held the opener, with nothing said.
        kotlinx.coroutines.withTimeoutOrNull(OPENER_WAIT_MS) { calendarRead() }
        val z = zone()
        val now = io.nisfeb.talon.util.nowMs()
        val today = kotlin.time.Instant.fromEpochMilliseconds(now).toLocalDateTime(z).date
        val rows = calendar?.rows?.value.orEmpty()
        val next = rows.filter { !it.isTask && !it.all && it.l > now && kotlin.time.Instant.fromEpochMilliseconds(it.l).toLocalDateTime(z).date == today }.minByOrNull { it.l }
        val unreadMail = kotlinx.coroutines.withTimeoutOrNull(OPENER_WAIT_MS) { inbox.await() }?.threads?.filter { it.unread }
        seen.events(rows, today, today)
        seen.mail(unreadMail.orEmpty())
        Day(
            mail = unreadMail?.size,
            mailLate = mail != null && unreadMail == null,
            chats = chats,
            questions = kotlinx.coroutines.withTimeoutOrNull(QUESTIONS_WAIT_MS) { questions.await() },
            firstEvent = next?.let { "${it.name.ifBlank { "an event" }} at ${DictationCards.clock(it.l, z, twentyFour)}, ${soon(it.l - now)}" },
            card = seen.card(z, name, twentyFour, now),
        )
    }

    /**
     * The rest of the opener, written by the model from [digest]
     * ([openerDigest]) while the counts are said. Where the model fails,
     * the owner is asked what they would like: the counts alone still
     * open it.
     */
    suspend fun openerRest(completer: AgentLoop.Completer, digest: String): String = try {
        AgentLoop(completer, emptyList(), openerPrompt, 2).run(digest, confirm = { _, _ -> false })
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        VoiceConversation.WHAT_NEXT
    }

    const val DAY_TOOL = "day_overview"

    /** [day]'s lines for the model to word: the counts, the next event today, and today's brief. */
    fun dayTool(day: suspend () -> String) = Tool(
        spec = ToolSpec(
            DAY_TOOL,
            "The owner's day as $ASSISTANT_NAME opens it: their new mail and chat messages, how many of those wait for their answer, the next event today, and today's brief from orrery when there is one. Call it when they ask to be briefed or how their day looks. Today's events and unread mail are shown to them under your answer, so do not list those again.",
            toolSchema(required = emptyList()),
        ),
        write = false,
    ) { day() }

    /** What the opener is written from. [brief] only when it is today's. */
    fun openerDigest(now: String, firstEvent: String?, brief: Brief?): String = buildString {
        appendLine(now)
        appendLine(firstEvent?.let { "First event today: $it" } ?: "No more events today.")
        if (brief != null) append("Today's brief from orrery:\n").append(brief.text) else append("No brief today.")
    }

    /** [brief] when it is the one for [today] ("YYYY-MM-DD" on the owner's clock). */
    fun todays(brief: Brief?, today: LocalDate): Brief? = brief?.takeIf { it.day == today.toString() }

    /**
     * The brief as it is read aloud, a paragraph at a time: tags said as
     * "A1", not "open bracket A1", and the map link left for the card.
     */
    fun spokenParagraphs(text: String): List<String> =
        text.split(Regex("""\n\s*\n"""))
            .map { p -> DictationSpeech.plain(p.replace(Regex("""\[([A-Z]\d+)]"""), "$1").replace(Regex("""(?m)^\s*\[(\d+)]"""), "$1.")) }
            .filter { it.isNotBlank() }

    private val ENDS = setOf(
        "that's all", "thats all", "that's it", "thats it", "that is all", "that will be all", "that'll be all",
        "thanks", "thank you", "no thanks", "no thank you", "stop", "bye", "goodbye", "good bye", "bye bye",
        "nothing", "nothing else", "no", "nope", "i'm good", "im good", "all good", "done", "i'm done", "im done", "we're done", "were done",
    )

    /**
     * Whether [words] end the conversation: only the whole of what was said,
     * as [DictationFlow.heard] reads "send", so "thanks, what's my first
     * meeting" goes on.
     */
    fun ends(words: String): Boolean {
        val said = words.lowercase().replace('’', '\'').replace(Regex("""[^a-z' ]"""), " ").replace(Regex("""\s+"""), " ").trim()
            .removePrefix("ok ").removePrefix("okay ").removeSuffix(" ${ASSISTANT_NAME.lowercase()}").trim()
            .removeSuffix(" thanks").removeSuffix(" thank you").trim()
        return said in ENDS
    }
}
