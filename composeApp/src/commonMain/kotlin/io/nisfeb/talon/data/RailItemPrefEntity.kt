package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sparse rail-visibility override. Only contains rows for items the
 * user has explicitly hidden. Absence of a row means the item is
 * visible (the default). [SettingsSyncImpl] keeps the wire form
 * sparse the same way — a "show on rail" toggle deletes the row
 * + the `%settings` entry; "hide" upserts both.
 */
@Entity(tableName = "rail_item_prefs")
data class RailItemPrefEntity(
    @PrimaryKey val itemName: String,   // RailItem.name
    val visible: Boolean,
)
