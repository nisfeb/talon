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
    override suspend fun findLocal(whom: String, da: String): MessageEntity? =
        db.messages().findByDa(whom, da)

    override suspend fun fetchPost(whom: String, da: String): MessageEntity? =
        repo.fetchCitePost(whom, da)

    override suspend fun fetchReply(
        whom: String,
        postDa: String,
        replyDa: String,
    ): MessageEntity? = repo.fetchCiteReply(whom, postDa, replyDa)
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

    suspend fun find(da: String, load: suspend () -> MessageEntity?) =
        runCatching {
            CiteCache.resolve(whom, da) { resolver.findLocal(whom, da) ?: load() }
        }.getOrNull()

    val parent = find(postDa) { resolver.fetchPost(whom, postDa) }
        ?: return CiteJump.Message(whom, null, flag)

    val replyDa = cite.replyDa ?: return CiteJump.Message(whom, parent.id, flag)
    val reply = find(replyDa) { resolver.fetchReply(whom, postDa, replyDa) }
    return CiteJump.Reply(whom, parent.id, reply?.id, flag)
}
