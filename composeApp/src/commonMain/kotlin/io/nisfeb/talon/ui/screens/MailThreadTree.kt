package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.MailMessage
import io.nisfeb.talon.mail.TreeLayout
import io.nisfeb.talon.mail.Verdict
import io.nisfeb.talon.mail.layoutTree
import io.nisfeb.talon.mail.litPath

private val NODE_W = 150.dp
private val NODE_H = 40.dp
private val COL = NODE_W + 34.dp
private val ROW = 50.dp
private val PAD = 10.dp

/** The dashed edge an orphan hangs from, so "names a parent we do not
 *  hold" and "is a thread root" stay different facts on the picture. */
private val STUB = 18.dp

/**
 * The shape of a conversation, drawn.
 *
 * A flat reading cannot show which branch a reply is about to hand to
 * somebody, because a flat reading has no branches in it. Here the edges
 * from a root down to the selected node are lit, and that lit path is
 * exactly what a reply or forward carries.
 *
 * The box scrolls; the page does not. A thread eight generations deep is
 * wider than a phone and there is no honest way around that, but a pane
 * the user chose to scroll sideways is not the same as a layout that
 * does it to them.
 */
@Composable
fun MailThreadTree(
    messages: List<MailMessage>,
    selected: String?,
    nameFor: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout: TreeLayout = remember(messages) { layoutTree(messages) }
    val lit = remember(layout, selected) { litPath(layout, selected) }
    val byId = remember(layout) { layout.nodes.associateBy { it.message.id } }

    val width = PAD * 2 + STUB + COL * maxOf(1, layout.cols) - (COL - NODE_W)
    val height = PAD * 2 + ROW * maxOf(1, layout.rows) - (ROW - NODE_H)

    val edge = MaterialTheme.colorScheme.outlineVariant
    val litEdge = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.requiredSize(width, height)) {
            Canvas(Modifier.matchParentSize()) {
                val nodeW = NODE_W.toPx()
                val nodeH = NODE_H.toPx()
                val col = COL.toPx()
                val rowH = ROW.toPx()
                val pad = PAD.toPx()
                val stub = STUB.toPx()
                fun cx(depth: Int) = pad + stub + depth * col
                fun cy(row: Float) = pad + row * rowH

                for (n in layout.nodes) {
                    val parent = n.parentId?.let { byId[it] }
                    val y = cy(n.row) + nodeH / 2f
                    if (parent == null) {
                        if (!n.orphaned) continue
                        drawLine(
                            color = edge,
                            start = Offset(cx(n.depth) - stub, y),
                            end = Offset(cx(n.depth), y),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                        continue
                    }
                    // An elbow, not a diagonal: a straight run out of the
                    // parent, one turn, a straight run into the child.
                    // Diagonals crossing at a shallow angle are the thing
                    // that makes a deep thread unreadable.
                    val onPath = n.message.id in lit && parent.message.id in lit
                    val stroke = if (onPath) litEdge else edge
                    val w = if (onPath) 2.2f else 1.4f
                    val px = cx(parent.depth) + nodeW
                    val py = cy(parent.row) + nodeH / 2f
                    val nx = cx(n.depth)
                    val mid = px + (nx - px) / 2f
                    drawLine(stroke, Offset(px, py), Offset(mid, py), w)
                    drawLine(stroke, Offset(mid, py), Offset(mid, y), w)
                    drawLine(stroke, Offset(mid, y), Offset(nx, y), w)
                }
            }

            for (n in layout.nodes) {
                TreeNode(
                    message = n.message,
                    nameFor = nameFor,
                    selected = n.message.id == selected,
                    onPath = n.message.id in lit,
                    onClick = { onSelect(n.message.id) },
                    modifier = Modifier.offset(
                        x = PAD + STUB + COL * n.depth,
                        y = PAD + rowOffset(n.row),
                    ),
                )
            }
        }
    }
}

private fun rowOffset(row: Float): Dp = ROW * row

@Composable
private fun TreeNode(
    message: MailMessage,
    nameFor: (String) -> String,
    selected: Boolean,
    onPath: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val forged = message.verdict == Verdict.FORGED
    val edge = when {
        selected -> MaterialTheme.colorScheme.primary
        forged -> MaterialTheme.colorScheme.error
        onPath -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val ground = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        onPath -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
        else -> MaterialTheme.colorScheme.surface
    }
    Box(
        modifier
            .size(NODE_W, NODE_H)
            .clip(RoundedCornerShape(4.dp))
            .background(ground)
            .border(if (selected) 2.dp else 1.dp, edge, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    nameFor(message.from),
                    style = MaterialTheme.typography.labelMedium
                        .copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (forged) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "FORGED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (message.verdict == Verdict.UNVERIFIED) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                message.body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: "(no text)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
