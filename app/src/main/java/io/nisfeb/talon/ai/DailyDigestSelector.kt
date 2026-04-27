package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.WatchwordHitEntity

/**
 * Pure bucket assembly + cross-bucket dedupe. The DailyDigest facade
 * fetches all the inputs from DAO + StoryCache and hands them in;
 * this function does the rest. Pure-function shape makes the logic
 * unit-testable without an in-memory database.
 *
 * See spec §Generation pipeline / steps 3 + 4.
 */
object DailyDigestSelector {

    private const val MENTION_CAP = 50
    private const val WATCHWORD_CAP = 50
    private const val UNREAD_CAP = 100
    private const val SNIPPET_MAX = 200

    /**
     * @param mentionCandidates messages already pre-filtered to "in window,
     *  not authored by us". The selector applies the patp-boundary check.
     * @param mentionPlainText map from postId → already-extracted plain
     *  text (caller uses StoryCache.textFor). Extracted at the facade
     *  level because StoryCache is not pure.
     * @param watchwordHits in-window watchword hits.
     * @param unreadCandidates per-chat newest messages in the window
     *  (already excluded muted / watchword-excluded chats by the caller).
     * @param unreadCounts per-chat unread count from `unreads` table.
     */
    fun assemble(
        ourPatp: String,
        mentionCandidates: List<MessageEntity>,
        mentionPlainText: Map<String, String>,
        watchwordHits: List<WatchwordHitEntity>,
        unreadCandidates: Map<String, List<MessageEntity>>,
        unreadCounts: Map<String, Int>,
    ): List<DigestItem> {
        val out = LinkedHashMap<Pair<String, String>, DigestItem>()

        // 1. Mentions (highest priority — go in first so dedupe keeps them)
        var mentionAdded = 0
        for (m in mentionCandidates) {
            if (mentionAdded >= MENTION_CAP) break
            val text = mentionPlainText[m.id] ?: continue
            if (!DailyDigestMentionMatcher.containsMention(text, ourPatp)) continue
            val key = m.whom to m.id
            out[key] = DigestItem(
                whom = m.whom,
                postId = m.id,
                authorPatp = m.author,
                sentMs = m.sentMs,
                bucket = Bucket.MENTION,
                snippet = text.take(SNIPPET_MAX),
            )
            mentionAdded++
        }

        // 2. Watchword hits (dedupe within bucket by whom+postId; keep first term)
        val seenHits = HashSet<Pair<String, String>>()
        var wAdded = 0
        for (h in watchwordHits) {
            if (wAdded >= WATCHWORD_CAP) break
            val key = h.whom to h.postId
            if (!seenHits.add(key)) continue        // already in this bucket
            if (out.containsKey(key)) continue       // mention beat us
            out[key] = DigestItem(
                whom = h.whom,
                postId = h.postId,
                authorPatp = "",                     // unknown from hits table
                sentMs = h.sentMs,
                bucket = Bucket.WATCHWORD,
                snippet = h.snippet,
                matchedTerm = h.term,
            )
            wAdded++
        }

        // 3. Unread (lowest priority)
        var uAdded = 0
        for ((whom, msgs) in unreadCandidates) {
            if (uAdded >= UNREAD_CAP) break
            val count = unreadCounts[whom] ?: 0
            if (count <= 0) continue
            val take = minOf(count, msgs.size)
            for (i in 0 until take) {
                if (uAdded >= UNREAD_CAP) break
                val m = msgs[i]
                val key = m.whom to m.id
                if (out.containsKey(key)) continue
                out[key] = DigestItem(
                    whom = m.whom,
                    postId = m.id,
                    authorPatp = m.author,
                    sentMs = m.sentMs,
                    bucket = Bucket.UNREAD,
                    snippet = m.contentJson.take(SNIPPET_MAX),
                )
                uAdded++
            }
        }

        return out.values.toList()
    }
}
