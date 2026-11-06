package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.util.dataDirPath
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The picture behind a conversation's bubbles, kept on this device in the
 * app's data dir. It is this device's alone: a picture would have to go
 * through the ship to travel, and a colour behind the bubbles is the
 * theme's (sneagan, 2026-11-06).
 */
class ChatBackgroundFiles(private val dir: Path = "$dataDirPath/chat-background".toPath()) {
    private val fs = FileSystem.SYSTEM

    /** [bytes] kept, written whole or not at all; the path for the setting. */
    fun write(bytes: ByteArray): String {
        fs.createDirectories(dir)
        val path = dir / "${bytes.toByteString().sha256().hex()}.img"
        val part = dir / "${path.name}.part"
        fs.write(part) { write(bytes) }
        fs.atomicMove(part, path)
        return path.toString()
    }

    /** Every picture but [keep] goes: the one replaced, or all of them on "". */
    fun keepOnly(keep: String) {
        fs.listOrNull(dir)?.forEach { if (it.toString() != keep) runCatching { fs.delete(it) } }
    }

    companion object {
        val default: ChatBackgroundFiles by lazy { ChatBackgroundFiles() }
    }
}

/** The chat's picture, filling the box the list is in, when there is one. */
@Composable
fun BoxScope.ChatBackdrop(look: ChatLook) {
    val image = look.image ?: return
    coil3.compose.AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
    )
}

/** [c] as it is drawn: on a picture, made solid on the page, so the photo does not show through. */
@Composable
fun onChatPicture(c: Color): Color =
    if (LocalChatLook.current.image == null) c else c.compositeOver(MaterialTheme.colorScheme.surface)

/** Text laid straight on the chat: on a picture, on a pill of the page's colour so it reads. */
@Composable
fun Modifier.chatLabel(): Modifier =
    if (LocalChatLook.current.image == null) this
    else background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(50))
        .padding(horizontal = 8.dp, vertical = 2.dp)
