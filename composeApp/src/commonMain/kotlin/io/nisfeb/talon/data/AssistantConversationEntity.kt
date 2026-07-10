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
 * [gid] is the cross-device identity (see [newGid]); the autoincrement
 * [id] is local-only and collides across devices. [centroid] is NOT
 * synced — it's a device-local embedder artifact (Android 100-dim vs
 * desktop 384-dim aren't comparable), so a conversation pulled from the
 * ship starts with an empty centroid and re-learns it as turns are
 * added on this device.
 */
@Immutable
@Entity(tableName = "assistant_conversation")
data class AssistantConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Cross-device id (empty only for pre-sync rows mid-migration). */
    val gid: String = "",
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
            gid == other.gid &&
            title == other.title &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            dim == other.dim &&
            turnCount == other.turnCount &&
            centroid.contentEquals(other.centroid)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + gid.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + dim
        result = 31 * result + turnCount
        result = 31 * result + centroid.contentHashCode()
        return result
    }
}
