package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.ChannelPreview
import io.nisfeb.talon.urbit.Said
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.StoryPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cite of a post in a channel this ship is not in: its host's answer, and its channel's preview. */
data class Outside(val said: Said, val preview: ChannelPreview?)

/**
 * Answers asked of a host once a session, a key at a time, so a chat
 * full of one reference asks once; every ask is an ames flow on the
 * host. One that has not [settled] is asked again after [retryMs].
 */
internal class SessionAsks<K, V>(private val settled: (V) -> Boolean, private val retryMs: Long = 2 * 60_000L) {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val got = HashMap<K, Pair<V, Long>>()
    private val mutexes = HashMap<K, Mutex>()

    private fun kept(key: K): Pair<V, Long>? = kotlinx.atomicfu.locks.synchronized(lock) { got[key] }
        ?.takeIf { (value, at) -> settled(value) || io.nisfeb.talon.util.nowMs() - at < retryMs }

    /** [key]'s answer, from [ask] where none is kept; [again] asks anew. */
    suspend fun get(key: K, again: Boolean = false, ask: suspend () -> V): V {
        if (!again) kept(key)?.let { return it.first }
        val mutex = kotlinx.atomicfu.locks.synchronized(lock) { mutexes.getOrPut(key) { Mutex() } }
        return mutex.withLock {
            (if (again) null else kept(key))?.first
                ?: ask().also { value -> kotlinx.atomicfu.locks.synchronized(lock) { got[key] = value to io.nisfeb.talon.util.nowMs() } }
        }
    }

    fun clear() = kotlinx.atomicfu.locks.synchronized(lock) { got.clear() }
}

internal object OutsideCites {
    private val posts = SessionAsks<Triple<String, String, String?>, Said>(settled = { it !is Said.NoAnswer })
    private val previews = SessionAsks<String, ChannelPreview?>(settled = { it != null })

    /**
     * The post, then its channel's preview where the host showed the post.
     * Its preview is held by the same rule, and a private group's is never
     * answered at all: asked anyway, it held the card for its whole wait.
     * [again] asks the host anew (a retry).
     */
    suspend fun load(whom: String, postDa: String, replyDa: String?, resolver: CiteResolver, again: Boolean = false): Outside {
        val said = posts.get(Triple(whom, postDa, replyDa), again) { resolver.fetchOutside(whom, postDa, replyDa) }
        val preview = if (said is Said.Post) previews.get(whom, again) { resolver.previewChannel(whom) } else null
        return Outside(said, preview)
    }

    /** Forgets every answer: a test's fresh start. */
    fun clear() {
        posts.clear()
        previews.clear()
    }
}

/** "Group · Channel", the way the lists name a channel, from what its preview gave. */
internal fun ChannelPreview.placeName(): String =
    listOfNotNull(groupTitle ?: groupFlag, channelTitle).joinToString(" · ")

/** What a cite's card says in place of a post it could not show. */
internal fun outsideNote(said: Said?): String = when (said) {
    Said.Denied -> "Only members of its group can see this post"
    Said.Gone -> "This post is no longer there"
    Said.NoAnswer -> "Its group's host did not answer"
    else -> "Referenced post"
}

/**
 * One post of a channel this ship is not in, opened from a cite: the
 * post itself where its host shows it, and the group's page to join it
 * from, where the host says which group it is.
 */
@Composable
fun ReferencedPostDialog(
    cite: StoryPart.Citation,
    resolver: CiteResolver,
    onOpenGroup: (flag: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val whom = cite.openTarget ?: return
    val postDa = cite.postDa ?: return
    var outside by remember(cite) { mutableStateOf<Outside?>(null) }
    var tries by remember(cite) { mutableIntStateOf(0) }
    LaunchedEffect(cite, tries) {
        outside = OutsideCites.load(whom, postDa, cite.replyDa, resolver, again = tries > 0)
    }
    val got = outside
    val preview = got?.preview
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("referenced-post"),
        title = { Text(preview?.placeName() ?: "A post in a group you're not in") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                when (val said = got?.said) {
                    null -> CircularProgressIndicator()
                    is Said.Post -> {
                        val post = said.message
                        Text(
                            LocalDisplayName.current(post.author),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        if (post.sentMs > 0) {
                            Text(
                                "${io.nisfeb.talon.util.formatMonthDayYear(post.sentMs)} ${io.nisfeb.talon.util.formatClock(post.sentMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val parts = remember(post.id, post.contentJson) { StoryCache.partsFor(post.id, post.contentJson) }
                        StoryRenderer(parts = parts, modifier = Modifier.fillMaxWidth())
                    }
                    Said.Denied -> Text("Only members of this group can see this post.")
                    Said.Gone -> Text("This post is no longer there. It was deleted, or never was.")
                    Said.NoAnswer -> Text("Its group's host did not answer. It may be offline.")
                }
            }
        },
        confirmButton = {
            when {
                preview != null -> TextButton(onClick = { onOpenGroup(preview.groupFlag) }) { Text("Join group") }
                got?.said == Said.NoAnswer -> TextButton(onClick = { outside = null; tries++ }) { Text("Try again") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
