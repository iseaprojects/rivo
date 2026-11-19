package io.github.iseaprojects.rivo.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The library database.
 *
 * Version 1 is the first release, so there is no migration yet. Any future schema change
 * ships a `Migration` in [create] and bumps [VERSION] together with the exported schema
 * under `app/schemas`; `fallbackToDestructiveMigration` is deliberately absent so a
 * release build can never drop a user's saved videos during an upgrade.
 */
@Database(
    entities = [
        SavedVideoEntity::class,
        RecentVideoEntity::class,
        CustomPlaylistEntity::class,
        PlaylistItemEntity::class,
        SearchHistoryEntity::class,
        SubscriptionEntity::class,
        SavedPlaylistEntity::class,
    ],
    version = LibraryDatabase.VERSION,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun library(): LibraryDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "rivo-library.db"

        fun create(context: Context): LibraryDatabase = Room
            .databaseBuilder(context.applicationContext, LibraryDatabase::class.java, FILE_NAME)
            .build()
    }
}
