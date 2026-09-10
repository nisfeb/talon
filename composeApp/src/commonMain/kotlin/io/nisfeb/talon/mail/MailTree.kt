package io.nisfeb.talon.mail

/**
 * A thread is a tree, not a list. A message names its parent and
 * nothing else about the ordering is stored, so two messages naming the
 * same parent are a branch.
 *
 * This matters for more than layout. A reply or forward ships the path
 * from the root to the message it answers and never the sibling
 * branches, so the path is exactly the disclosure a send makes. The
 * picture and the count of what travels are two renderings of one fact,
 * and [pathTo] is that fact: anything shown to a user about what a
 * reply carries must come from here, or the two can disagree.
 */
data class MailNode(
    val message: MailMessage,
    val children: List<MailNode> = emptyList(),
    /**
     * True when this message names a parent the thread does not
     * contain, so it is drawn as a root without being one. Happens when
     * the parent is a copy this build cannot read; a flat reading would
     * silently show it as answering whatever sits above it.
     */
    val orphaned: Boolean = false,
)

/**
 * The thread's messages as a forest. Roots first, siblings oldest
 * first, ties broken by id so the order is stable across reads.
 */
fun threadTree(messages: List<MailMessage>): List<MailNode> {
    if (messages.isEmpty()) return emptyList()
    val byId = messages.associateBy { it.id }
    val childrenOf = mutableMapOf<String, MutableList<MailMessage>>()
    val roots = mutableListOf<MailMessage>()
    val orphans = mutableSetOf<String>()

    for (m in messages) {
        val parent = m.prev
        when {
            parent == null -> roots += m
            byId.containsKey(parent) -> childrenOf.getOrPut(parent) { mutableListOf() } += m
            else -> {
                // Names a parent we do not hold. Still shown, and marked,
                // because dropping it loses mail and hiding the gap makes
                // it look like a reply to the message above.
                roots += m
                orphans += m.id
            }
        }
    }

    fun order(list: List<MailMessage>) = list.sortedWith(compareBy({ it.sent }, { it.id }))

    // A content-addressed id cannot really cycle, but a malformed thread
    // must not be able to hang the reader.
    val seen = mutableSetOf<String>()
    fun build(m: MailMessage): MailNode {
        if (!seen.add(m.id)) return MailNode(m)
        return MailNode(
            message = m,
            children = order(childrenOf[m.id].orEmpty()).map { build(it) },
            orphaned = m.id in orphans,
        )
    }

    return order(roots).map { build(it) }
}

/**
 * The messages a reply or forward from [id] would carry: the path from
 * its root down to it, in order, and nothing from any sibling branch.
 *
 * Empty when the id is not in the tree, which is the safe answer — a
 * caller showing "0 messages travel" is wrong in a way a user notices,
 * where a caller silently showing a whole thread is not.
 */
fun pathTo(forest: List<MailNode>, id: String): List<MailMessage> {
    fun walk(node: MailNode, acc: MutableList<MailMessage>): Boolean {
        acc += node.message
        if (node.message.id == id) return true
        for (c in node.children) if (walk(c, acc)) return true
        acc.removeAt(acc.size - 1)
        return false
    }
    for (root in forest) {
        val acc = mutableListOf<MailMessage>()
        if (walk(root, acc)) return acc
    }
    return emptyList()
}

/** Flatten the forest depth-first, carrying each node's depth, for a
 *  reader that draws indentation rather than a separate tree pane. */
fun flatten(forest: List<MailNode>, depth: Int = 0): List<Pair<MailNode, Int>> =
    forest.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }

/** Does this thread actually branch? A reader can offer the tree only
 *  where there is one, and say nothing where the thread is a line. */
fun branches(forest: List<MailNode>): Boolean {
    if (forest.size > 1) return true
    fun any(n: MailNode): Boolean = n.children.size > 1 || n.children.any { any(it) }
    return forest.any { any(it) }
}

/**
 * The newest message that can be answered. A node carrying a forged
 * copy cannot be replied to, so a reader defaulting to "the tip" has to
 * mean the newest honest one.
 */
fun newestAnswerable(messages: List<MailMessage>): MailMessage? =
    messages.filter { it.verdict != Verdict.FORGED }.maxByOrNull { it.sent }
