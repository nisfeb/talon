package io.nisfeb.talon.ui

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.TlonChatRepo

/** The app's one [CiteResolver]: local DB first, channel scry second. */
class TalonCiteResolver(
    private val db: AppDatabase,
    private val repo: TlonChatRepo,
) : CiteResolver {
    // A cite carries the post's number dotted, the way a scry path
    // wants it; the database keys posts on the bare digits. Compared
    // as given, no quoted post was ever found locally, and every one
    // of them went to the ship.
    // A DM quote names the chat as its sender saw it, which for the other
    // side is their own ship: the post is then found by its number.
    override suspend fun findLocal(whom: String, da: String): MessageEntity? {
        val digits = da.replace(".", "")
        return db.messages().findByDa(whom, digits)
            ?: if ('/' !in whom) db.messages().findDmByDa(digits) else null
    }

    override suspend fun fetchPost(whom: String, da: String): MessageEntity? =
        repo.fetchCitePost(whom, da)

    override suspend fun fetchReply(
        whom: String,
        postDa: String,
        replyDa: String,
    ): MessageEntity? = repo.fetchCiteReply(whom, postDa, replyDa)

    override suspend fun holds(whom: String): Boolean =
        !io.nisfeb.talon.urbit.isChannelNest(whom) || db.groups().channelGroupFor(whom) != null

    override suspend fun fetchOutside(whom: String, postDa: String, replyDa: String?) =
        repo.fetchSaid(whom, postDa, replyDa)

    override suspend fun previewChannel(whom: String) = repo.channelPreview(whom)
}

/**
 * Where a tapped citation should take the user. Resolved once, in
 * [resolveCiteJump], so every surface that renders a Story — chat,
 * thread, notebook, gallery — jumps the same way.
 *
 * [groupFlag] rides along on channel jumps so the shell can reveal the
 * owning group in the home list. A cite can point at a channel in a
 * group the user isn't currently looking at; without the reveal, the
 * chat opens but the list behind it still shows the old group.
 */
sealed interface CiteJump {
    /** A whole group (`{"cite": {"group": ...}}`). */
    data class Group(val flag: String) : CiteJump

    /** A channel, optionally scrolled to [messageId]. */
    data class Message(
        val whom: String,
        val messageId: String?,
        val groupFlag: String?,
    ) : CiteJump

    /** A post in a channel this ship is not in: shown on its own ([ReferencedPostDialog]), not as a chat it cannot read. */
    data class Outside(val cite: StoryPart.Citation) : CiteJump

    /** A reply — open [parentId]'s thread, anchored on [replyId] when
     *  the reply itself resolved. */
    data class Reply(
        val whom: String,
        val parentId: String,
        val replyId: String?,
        val groupFlag: String?,
    ) : CiteJump
}

/**
 * Turn a citation into a destination. The `da` timestamps a cite
 * carries aren't message ids, so the post (and reply) have to be
 * resolved through [resolver] — local DB first, channel scry second.
 * Routes through the same [CiteCache] the inline preview uses, so a
 * tap on a cite the user can already see costs nothing.
 *
 * Degrades instead of failing: an unresolvable post still opens the
 * channel, and an unresolvable reply still opens its parent's thread.
 */
suspend fun resolveCiteJump(
    cite: StoryPart.Citation,
    resolver: CiteResolver,
    groupFlagFor: suspend (whom: String) -> String?,
): CiteJump? {
    val whom = cite.openTarget ?: return null
    if (whom.startsWith("group:")) return CiteJump.Group(whom.removePrefix("group:"))

    val flag = runCatching { groupFlagFor(whom) }.getOrNull()
    val postDa = cite.postDa ?: return CiteJump.Message(whom, null, flag)
    if (io.nisfeb.talon.urbit.isChannelNest(whom) && !runCatching { resolver.holds(whom) }.getOrDefault(true)) {
        return CiteJump.Outside(cite)
    }

    suspend fun find(da: String, load: suspend () -> MessageEntity?) =
        runCatching {
            CiteCache.resolve(whom, da) { resolver.findLocal(whom, da) ?: load() }
        }.getOrNull()

    // A channel post's number is its message id, so the jump can name
    // it even when the ship never answered for the preview.
    val parent = find(postDa) { resolver.fetchPost(whom, postDa) }
        ?: return CiteJump.Message(whom, channelPostId(whom, postDa), flag)

    // The chat the post is in, which for a DM quote is not always the one
    // the cite names (see TalonCiteResolver.findLocal).
    val at = parent.whom
    val replyDa = cite.replyDa ?: return CiteJump.Message(at, parent.id, flag)
    val reply = find(replyDa) { resolver.fetchReply(at, postDa, replyDa) }
    return CiteJump.Reply(at, parent.id, reply?.id, flag)
}

private fun channelPostId(whom: String, postDa: String): String? =
    postDa.replace(".", "").takeIf { whom.contains('/') && it.all(Char::isDigit) }
