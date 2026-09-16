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
 * Collapse every stored copy of a message to the one thing a reader
 * can point at.
 *
 * COPIES ARE NOT MESSAGES. Several grubs can share one id and differ
 * only in signature — one genuine, the rest forged — and drawing each
 * as its own node would show a two-message conversation as five, with
 * the forgeries indistinguishable from real replies.
 *
 * Three rules, and each exists to stop a forgery deciding something:
 *
 * - The node's verdict is the LOUDEST of its copies. One forged copy
 *   makes the node forged, so a forgery cannot hide behind a genuine
 *   copy of the same id.
 * - The copy that SPEAKS is the newest that is not forged, falling
 *   back to the newest of all when there is nothing honest to choose.
 *   `sent` is a signed field the author picks, so letting a forged copy
 *   speak would let whoever poked the chain choose what the node says.
 * - The parent is the SPEAKER's, not the first copy's. A forged copy
 *   naming a different parent would otherwise re-hang a genuine branch
 *   somewhere else in the picture.
 */
fun collapse(messages: List<MailMessage>): List<MailMessage> {
    if (messages.isEmpty()) return emptyList()
    val byId = LinkedHashMap<String, MutableList<MailMessage>>()
    for (m in messages) byId.getOrPut(m.id) { mutableListOf() } += m
    return byId.values.map { copies ->
        val honest = copies.filter { it.verdict != Verdict.FORGED }
        val speaker = (honest.ifEmpty { copies })
            .maxByOrNull { it.sent } ?: copies.first()
        val loudest =
            if (copies.any { it.verdict == Verdict.FORGED }) Verdict.FORGED else speaker.verdict
        if (loudest == speaker.verdict) speaker else speaker.copy(verdict = loudest)
    }
}

/** How many stored copies each id has. More than one is worth showing:
 *  it is the shape a forgery arrives in. */
fun copyCounts(messages: List<MailMessage>): Map<String, Int> =
    messages.groupingBy { it.id }.eachCount()

/**
 * The thread's messages as a forest. Roots first, siblings oldest
 * first, ties broken by id so the order is stable across reads.
 */
fun threadTree(raw: List<MailMessage>): List<MailNode> {
    // Collapsed HERE rather than by the caller, so this and the drawn
    // layout cannot end up disagreeing about which copy speaks.
    val messages = collapse(raw)
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
    // must not be able to hang the reader. And none of this recurses:
    // a ten-thousand-message chain of self-replies is a shape that
    // arrives over the wire, and recursion on it is a stack overflow
    // somebody else chooses to hand us.
    val seen = mutableSetOf<String>()
    fun build(root: MailMessage): MailNode {
        val built = HashMap<String, MailNode>()
        val stack = ArrayDeque<Pair<MailMessage, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (m, childrenDone) = stack.removeLast()
            if (!childrenDone) {
                if (!seen.add(m.id)) {
                    built[m.id] = MailNode(m)
                    continue
                }
                stack.addLast(m to true)
                for (c in order(childrenOf[m.id].orEmpty()).asReversed()) stack.addLast(c to false)
            } else {
                built[m.id] = MailNode(
                    message = m,
                    children = order(childrenOf[m.id].orEmpty()).map { built.getValue(it.id) },
                    orphaned = m.id in orphans,
                )
            }
        }
        return built.getValue(root.id)
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
    // Depth-first with the path kept on the heap: the walk goes as deep
    // as the thread does, and the thread goes as deep as the wire says.
    val path = mutableListOf<MailMessage>()
    val stack = ArrayDeque<Iterator<MailNode>>()
    var level = forest.iterator()
    while (true) {
        if (level.hasNext()) {
            val n = level.next()
            path += n.message
            if (n.message.id == id) return path
            stack.addLast(level)
            level = n.children.iterator()
        } else {
            if (stack.isEmpty()) return emptyList()
            level = stack.removeLast()
            path.removeAt(path.size - 1)
        }
    }
}

/**
 * Flatten, hiding what sits under a collapsed node.
 *
 * A collapsed node keeps its own line and loses its descendants, which
 * is the Thunderbird reading: folding a reply folds the conversation
 * under it, not the reply itself. The count of what is hidden goes on
 * the node, because a fold that does not say how much it swallowed is
 * indistinguishable from a thread that simply ends there.
 */
fun flattenVisible(
    forest: List<MailNode>,
    collapsed: Set<String>,
    depth: Int = 0,
): List<VisibleNode> {
    val out = ArrayList<VisibleNode>()
    val stack = ArrayDeque<Pair<MailNode, Int>>()
    for (n in forest.asReversed()) stack.addLast(n to depth)
    while (stack.isNotEmpty()) {
        val (n, d) = stack.removeLast()
        val hidden = if (n.message.id in collapsed) countDescendants(n) else 0
        out += VisibleNode(n, d, hidden)
        if (hidden == 0) for (c in n.children.asReversed()) stack.addLast(c to d + 1)
    }
    return out
}

data class VisibleNode(val node: MailNode, val depth: Int, val hidden: Int)

fun countDescendants(n: MailNode): Int {
    var count = 0
    val stack = ArrayDeque<MailNode>()
    stack.addAll(n.children)
    while (stack.isNotEmpty()) {
        val c = stack.removeLast()
        count++
        stack.addAll(c.children)
    }
    return count
}

/** Does this thread actually branch? A reader can offer the tree only
 *  where there is one, and say nothing where the thread is a line. */
fun branches(forest: List<MailNode>): Boolean {
    if (forest.size > 1) return true
    val stack = ArrayDeque<MailNode>()
    stack.addAll(forest)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (n.children.size > 1) return true
        stack.addAll(n.children)
    }
    return false
}

/**
 * The newest message that can be answered. A node carrying a forged
 * copy cannot be replied to, so a reader defaulting to "the tip" has to
 * mean the newest honest one.
 */
fun newestAnswerable(messages: List<MailMessage>): MailMessage? =
    collapse(messages).filter { it.verdict != Verdict.FORGED }.maxByOrNull { it.sent }

/** One node placed on the drawing: [depth] is its generation, [row] its
 *  line, fractional where a parent sits between its children. */
data class PlacedNode(
    val message: MailMessage,
    val parentId: String?,
    val depth: Int,
    val row: Float,
    val orphaned: Boolean,
)

data class TreeLayout(val nodes: List<PlacedNode>, val rows: Int, val cols: Int)

/**
 * Lay a thread out as a picture: a leaf takes the next free row, a
 * parent sits at the midpoint of its first and last child, and x is
 * simply the generation.
 *
 * Iterative on purpose. A thousand-message linear thread is a shape
 * that arrives over the wire, and recursion on it is a stack overflow
 * somebody else chooses to hand us.
 *
 * A parent naming an id we do not hold, or one that walks back into
 * itself, has its link cut here rather than guarded at every later use.
 * Both arrive over the wire and neither can be trusted to terminate.
 */
fun layoutTree(raw: List<MailMessage>): TreeLayout {
    val messages = collapse(raw)
    if (messages.isEmpty()) return TreeLayout(emptyList(), 0, 0)

    class N(val m: MailMessage, var parent: String?, var orphan: Boolean, var depth: Int, var row: Float)

    val byId = LinkedHashMap<String, N>()
    for (m in messages) if (m.id !in byId) byId[m.id] = N(m, m.prev, false, 0, 0f)

    for (n in byId.values) {
        val p = n.parent
        if (p != null && p !in byId) {
            n.orphan = true
            n.parent = null
            continue
        }
        val seen = mutableSetOf(n.m.id)
        var cur = n.parent
        while (cur != null) {
            if (!seen.add(cur)) { n.parent = null; break }
            cur = byId[cur]?.parent
        }
    }

    val kids = mutableMapOf<String, MutableList<N>>()
    val roots = mutableListOf<N>()
    for (n in byId.values) {
        val p = n.parent
        if (p == null) roots += n else kids.getOrPut(p) { mutableListOf() } += n
    }
    val order = compareBy<N>({ it.m.sent }, { it.m.id })
    kids.values.forEach { it.sortWith(order) }
    roots.sortWith(order)

    var row = 0
    var cols = 1
    val stack = ArrayDeque<Pair<N, Boolean>>()
    for (r in roots.asReversed()) stack.addLast(r to false)
    while (stack.isNotEmpty()) {
        val (n, entered) = stack.removeLast()
        val k = kids[n.m.id]
        if (!entered && !k.isNullOrEmpty()) {
            stack.addLast(n to true)
            for (c in k.asReversed()) {
                c.depth = n.depth + 1
                stack.addLast(c to false)
            }
            continue
        }
        if (!k.isNullOrEmpty()) {
            n.row = (k.first().row + k.last().row) / 2f
        } else {
            n.row = row.toFloat()
            row++
        }
        if (n.depth + 1 > cols) cols = n.depth + 1
    }

    return TreeLayout(
        nodes = byId.values.map { PlacedNode(it.m, it.parent, it.depth, it.row, it.orphan) },
        rows = row,
        cols = cols,
    )
}

/** The ids on the path from a root down to [id], for lighting edges. */
fun litPath(layout: TreeLayout, id: String?): Set<String> {
    if (id == null) return emptySet()
    val byId = layout.nodes.associateBy { it.message.id }
    val lit = mutableSetOf<String>()
    var cur = byId[id]
    while (cur != null && lit.add(cur.message.id)) {
        cur = cur.parentId?.let { byId[it] }
    }
    return lit
}
