package com.favkit.tablet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteRow::class,
        TagRow::class,
        FavoriteTagRow::class,
        PendingOpRow::class,
    ],
    version = 4,
    // 不导出 schema：调试期看运行时版本用 RoomInspector 即可。导出会让 debug/release 两个
    // KSP 任务写同一个目录，云端并行构建里会留下半截空文件，Room 读它直接报 "Empty schema file"。
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favorites(): FavoritesDao
    abstract fun tags(): TagsDao
    abstract fun refs(): FavoriteTagDao
    abstract fun pendingOps(): PendingOpsDao

    companion object {

        /**
         * 还在开发期，表结构改动按「清库重新从后台取」处理，不写迁移：
         * v4 把 video_info 合进 favorites，本地收藏与解析结果都能由后台一键重放，
         * 迁移反而要猜中 Room 生成的 DDL —— 云端 CI 只编译、从不跑迁移，猜错就是装机即崩。
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "favorites.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
    }
}
