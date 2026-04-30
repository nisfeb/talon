package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem

/**
 * Turn a deduped list of digest items into a transcript string for
 * the AI summary prompt. See spec §Generation/AI summary.
 *
 * Format: bucket header lines followed by `author: snippet` lines.
 * Headers are only emitted for non-empty buckets so the model isn't
 * told about empty sections.
 */
object DailyDigestPrompt {

    private const val SNIPPET_MAX = 200

    fun format(
        items: List<DigestItem>,
        displayName: (String) -> String,
    ): String {
        if (items.isEmpty()) return "(no messages)"
        val byBucket = items.groupBy { it.bucket }
        val sb = StringBuilder()
        for (bucket in listOf(Bucket.MENTION, Bucket.WATCHWORD, Bucket.UNREAD)) {
            val rows = byBucket[bucket] ?: continue
            sb.append("[")
            sb.append(bucketHeader(bucket))
            sb.append("]\n")
            for (r in rows) {
                val name = displayName(r.authorPatp)
                val snippet = r.snippet.replace('\n', ' ').take(SNIPPET_MAX)
                if (name.isNotBlank()) {
                    sb.append(name).append(": ")
                }
                sb.append(snippet).append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun bucketHeader(b: Bucket) = when (b) {
        Bucket.MENTION -> "mentions"
        Bucket.WATCHWORD -> "watchwords"
        Bucket.UNREAD -> "unread"
    }
}
