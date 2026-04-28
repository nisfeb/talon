// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/MessageEmbeddingEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity

/**
 * On-device sentence embedding for a single message, used by semantic
 * search. `vector` is the raw float32 array (little-endian) the
 * embedder produced — we keep it as a BLOB rather than expanding to a
 * column-per-dim because dimensionality varies per model and SQLite
 * doesn't gain anything from columnar floats here.
 *
 * Keyed by (whom, id) to match `messages`. Cleared when the parent
 * row is hard-deleted; ride-along soft-deletes leave the embedding in
 * place since we still want the row searchable from history.
 */
@Entity(tableName = "message_embeddings", primaryKeys = ["whom", "id"])
data class MessageEmbeddingEntity(
    val whom: String,
    val id: String,
    /** float32 little-endian, length = dim. */
    val vector: ByteArray,
    /** The model's output dimensionality, captured here so a model
     *  swap can't silently mix vector shapes in the cosine kernel. */
    val dim: Int,
    /** Hash of the source text — re-embed when contentJson changes. */
    val textHash: Long,
) {
    // ByteArray's auto-generated equals is referential. Override so
    // tests / set-dedupe can compare two rows by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEmbeddingEntity) return false
        return whom == other.whom && id == other.id &&
            dim == other.dim && textHash == other.textHash &&
            vector.contentEquals(other.vector)
    }
    override fun hashCode(): Int {
        var h = whom.hashCode()
        h = 31 * h + id.hashCode()
        h = 31 * h + vector.contentHashCode()
        h = 31 * h + dim
        h = 31 * h + textHash.hashCode()
        return h
    }
}
