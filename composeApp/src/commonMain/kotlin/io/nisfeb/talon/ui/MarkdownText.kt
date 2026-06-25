package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Tiny Markdown renderer for assistant output — just enough to make
 * answers readable: fenced code blocks (styled like chat's
 * [StoryRenderer] code, monospace on a surface), inline `code`, **bold**,
 * `#` headers, and `- ` bullet lists. Deliberately not a full CommonMark
 * implementation (no nested lists, tables, links) — the assistant rarely
 * needs more, and a real parser would be a dependency we don't want.
 */
internal sealed interface MdBlock {
    data class Code(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

/** Split Markdown source into block-level elements. Pure. */
internal fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    fun flushPara() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) out.add(MdBlock.Paragraph(t))
        para.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // consume the closing fence
                out.add(MdBlock.Code(sb.toString().trimEnd('\n')))
                continue
            }
            trimmed.startsWith("#") -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length
                out.add(MdBlock.Heading(level.coerceIn(1, 6), trimmed.dropWhile { it == '#' }.trim()))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                out.add(MdBlock.Bullet(trimmed.drop(2).trim()))
            }
            trimmed.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.trim())
            }
        }
        i++
    }
    flushPara()
    return out
}

/** Inline `code` + **bold** within one block. */
private fun inlineAnnotated(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end == -1) {
                    append(text.substring(i)); i = text.length
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end == -1) {
                    append(text.substring(i)); i = text.length
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> Surface(color = codeBg, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.text, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MdBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge)
                    Text(inlineAnnotated(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Paragraph -> Text(
                    inlineAnnotated(block.text, codeBg),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
