package io.nisfeb.talon.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A locally-grouped assistant conversation (docs/assistant.md). Turns
 * ([AssistantHistoryEntity.conversationId]) are clustered into a
 * conversation by topic: a new question joins this conversation when its
 * on-device embedding is close to [centroid] (the running mean of the
 * conversation's question vectors), otherwise a new conversation starts.
 * That clustering is what keeps context bounded — only a conversation's
 * own recent turns feed the model.
 *
 * Local-only, like the turns themselves; not synced.
 */
@Immutable
@Entity(tableName = "assistant_conversation")
data class AssistantConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Packed running-mean of the conversation's question embeddings
     *  ([io.nisfeb.talon.ai.packEmbedding]); empty if none embedded. */
    val centroid: ByteArray,
    val dim: Int,
    val turnCount: Int,
) {
    // ByteArray breaks data-class equality (identity, not content); Room
    // doesn't need it, but override so accidental == compares content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssistantConversationEntity) return false
        return id == other.id &&
            title == other.title &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            dim == other.dim &&
            turnCount == other.turnCount &&
            centroid.contentEquals(other.centroid)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + dim
        result = 31 * result + turnCount
        result = 31 * result + centroid.contentHashCode()
        return result
    }
}
