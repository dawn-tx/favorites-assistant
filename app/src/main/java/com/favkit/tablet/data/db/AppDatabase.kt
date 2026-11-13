package com.favkit.tablet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteRow::class,
        VideoInfoRow::class,
        TagRow::class,
        FavoriteTagRow::class,
        PendingOpRow::class,
    ],
    version = 2,
    // 不导出 schema：调试期迁移靠 RoomInspector 看运行时版本即可。导出会让 debug/release 两个
    // KSP 任务写同一个目录，云端并行构建里会留下半截空文件，Room 读它直接报 "Empty schema file"。
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favorites(): FavoritesDao
    abstract fun videos(): VideosDao
    abstract fun tags(): TagsDao
    abstract fun refs(): FavoriteTagDao
    abstract fun pendingOps(): PendingOpsDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "favorites.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // v2 把「收藏」与「后台解析的视频信息」拆成两张表，字段不兼容；
                // 本轮明确按清库处理，旧包升级后本地收藏需要重新添加。
                // Room 2.6.1 只有无参重载（dropAllTables 是 2.7 才有的），无参版本本身就是
                // 「没有迁移路径就整库重建」，效果一样。
                .fallbackToDestructiveMigration()
                .build()
    }
}
