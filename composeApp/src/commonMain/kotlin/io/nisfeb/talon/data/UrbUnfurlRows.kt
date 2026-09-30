package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The last preview card read for a urb:// address: its title and snippet,
 * both null where the page had none, and when it was read. Kept so a card
 * shows at once after a restart; a remote page took forty seconds and more
 * to read over Ames, every start. See [io.nisfeb.talon.urbit.UrbUnfurlCache].
 */
@Entity(tableName = "urb_unfurls")
data class UrbUnfurlEntity(
    @PrimaryKey val urbUrl: String,
    val title: String?,
    val snippet: String?,
    val fetchedAtMs: Long,
)

@Dao
interface UrbUnfurlDao {
    @Query("SELECT * FROM urb_unfurls WHERE urbUrl = :urbUrl")
    suspend fun get(urbUrl: String): UrbUnfurlEntity?

    @Upsert
    suspend fun put(row: UrbUnfurlEntity)
}

internal const val URB_UNFURLS_SQL =
    "CREATE TABLE IF NOT EXISTS `urb_unfurls` (`urbUrl` TEXT NOT NULL, `title` TEXT, `snippet` TEXT, `fetchedAtMs` INTEGER NOT NULL, PRIMARY KEY(`urbUrl`))"

/** 49 to 50: urb:// previews kept. Android runs the same statement its own way. */
val URB_UNFURLS_MIGRATION = object : Migration(49, 50) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(URB_UNFURLS_SQL)
}
