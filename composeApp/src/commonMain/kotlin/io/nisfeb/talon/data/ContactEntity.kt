package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.compose.runtime.Immutable
import androidx.room.PrimaryKey

/**
 * Cached contact info from %contacts. Populated by scrying /v1/all
 * (peer directory) and /v1/self (our own profile).
 */
@Immutable
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val ship: String,
    val nickname: String?,
    val bio: String?,
    val avatarUrl: String?,
    val status: String? = null,
    /** First local-time millis at which we observed the current `status`. */
    val statusUpdatedMs: Long? = null,
    /** User-set profile color as `#RRGGBB`, or null if unset. */
    val color: String? = null,
)
