package io.nisfeb.talon.data

/**
 * What was said on the dictation card, kept in Assistant history as text
 * (sneagan, 2026-10-17: "put the conversation into assistant history as
 * text when it's over"). Each turn is written as it happens, so the
 * history at the end is the same, and a conversation cut short by a
 * closed card or a crash keeps what was said before it.
 *
 * The conversation carries no topic centroid (dim 0), as one pulled from
 * another device does, so a question typed later never joins it by topic.
 */
object VoiceHistory {
    /** The turns' mode, telling them from typed ones. */
    const val MODE = "voice"

    /**
     * Keep one exchange: [question] is what the owner said, [answer] what
     * was said back. Makes the conversation, titled [title], with the first
     * turn ([conversation] null) and returns it for the next. [push] sends
     * the turn to the owner's other devices; a failure there keeps it here.
     */
    suspend fun record(
        db: AppDatabase,
        conversation: AssistantConversationEntity?,
        title: String,
        question: String,
        answer: String,
        now: Long,
        push: suspend (AssistantConversationEntity, AssistantHistoryEntity) -> Unit = { _, _ -> },
    ): AssistantConversationEntity {
        val convs = db.assistantConversations()
        // Trimmed away since the last turn: made again under the same gid.
        val known = conversation?.let { convs.getByGid(it.gid) }
        val conv = when {
            known != null -> known.copy(updatedAt = now, turnCount = known.turnCount + 1).also { convs.update(it) }
            else -> (conversation?.copy(id = 0, updatedAt = now, turnCount = conversation.turnCount + 1)
                ?: AssistantConversationEntity(gid = newGid(), title = title, createdAt = now, updatedAt = now, centroid = ByteArray(0), dim = 0, turnCount = 1))
                .let { it.copy(id = convs.insert(it)) }
        }
        convs.trim(ASSISTANT_CONV_KEEP)
        val turn = AssistantHistoryEntity(
            gid = newGid(),
            mode = MODE,
            question = question,
            answer = answer.ifBlank { "(no reply)" },
            createdAt = now,
            conversationId = conv.id,
            convGid = conv.gid,
        )
        db.assistantHistory().insert(turn)
        db.assistantHistory().trim(ASSISTANT_HISTORY_KEEP)
        runCatching { push(conv, turn) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        return conv
    }
}
