package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity

/**
 * Pure merge planner for the one-shot dotted-id dedupe. Given a list
 * of rows currently stored under a dot-grouped id, returns the DAO
 * operations the repo should perform to collapse them into their
 * undotted twins.
 *
 * Extracted so the logic can be unit-tested without Room.
 *
 * **What broke:** `applyChatDelta` stored ids verbatim from the %chat
 * SSE envelope (which uses dot-grouped ids), while bootstrap and
 * `loadOlder` ingested the same messages through `ingestPost`, which
 * strips dots for id normalization. Result: every DM message that was
 * seen both live and via pagination ended up with two rows — one
 * dotted, one undotted. Users observed their DM history doubled; the
 * further back they scrolled the more duplicates appeared (more
 * paginate-vs-SSE overlap).
 */
internal sealed interface DedupeOp {
    /** Dotted row has no undotted twin — re-insert under the normalized id. */
    data class Rename(val from: MessageEntity, val to: MessageEntity) : DedupeOp

    /** Undotted twin already exists — drop the dotted duplicate. */
    data class Drop(val whom: String, val dottedId: String) : DedupeOp
}

internal fun planMessageDedupe(
    dottedRows: List<MessageEntity>,
    /** Set of `(whom, cleanId)` pairs that already have an undotted twin. */
    existingUndotted: Set<Pair<String, String>>,
): List<DedupeOp> {
    val ops = mutableListOf<DedupeOp>()
    // Batch-level set so earlier rename decisions don't confuse later
    // rows in the same run (if two dotted rows normalized to the same
    // id, the second should drop, not rename).
    val willCreate = mutableSetOf<Pair<String, String>>()
    for (row in dottedRows) {
        val cleanId = undotAtom(row.id)
        if (cleanId == row.id) continue  // shouldn't happen, defensive
        val key = row.whom to cleanId
        if (key in existingUndotted || key in willCreate) {
            ops += DedupeOp.Drop(row.whom, row.id)
        } else {
            ops += DedupeOp.Rename(row, row.copy(id = cleanId))
            willCreate += key
        }
    }
    return ops
}

