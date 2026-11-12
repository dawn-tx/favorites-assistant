package com.favkit.tablet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteRow::class, TagRow::class, FavoriteTagRow::class, PendingOpRow::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favorites(): FavoritesDao
    abstract fun tags(): TagsDao
    abstract fun refs(): FavoriteTagDao
    abstract fun pendingOps(): PendingOpsDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "favorites.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
