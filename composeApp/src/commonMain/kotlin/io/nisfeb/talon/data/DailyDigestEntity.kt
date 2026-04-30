package io.nisfeb.talon.data

import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * Today's daily digest snapshot. Generated once per day per active
 * ship at the user-configured time. Overwritten on re-fire — see
 * spec §Persistence.
 */
@Entity(
    tableName = "daily_digests",
    primaryKeys = ["ship", "dateLocal"],
)
data class DailyDigestEntity(
    val ship: String,
    val dateLocal: String,        // "yyyy-MM-dd" in user's local TZ
    val generatedAtMs: Long,
    val summaryText: String?,     // null when AI off / failed
    val itemsJson: String,        // serialized List<DigestItem>
    val weatherJson: String?,     // serialized WeatherToday; null on failure
)

@Serializable
data class DigestItem(
    val whom: String,
    val postId: String,
    val authorPatp: String,
    val sentMs: Long,
    val bucket: Bucket,
    val snippet: String,
    /** Watchword bucket only — the term that matched. Null otherwise. */
    val matchedTerm: String? = null,
)

@Serializable
enum class Bucket { MENTION, WATCHWORD, UNREAD }

@Serializable
data class WeatherToday(
    val highF: Double,
    val lowF: Double,
    val conditionCode: Int,       // open-meteo WMO code
    val conditionLabel: String,
    val emoji: String,
)
