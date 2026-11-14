package com.favkit.tablet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteRow::class,
        VideoInfoRow::class,
        TagRow::class,
        FavoriteTagRow::class,
        PendingOpRow::class,
    ],
    version = 3,
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

        /**
         * v2 → v3：一条链接解析回来的不再是单个视频，而是「图片/视频若干段 ＋ 一句说明文案」。
         *
         * 只有四条 ADD COLUMN，没有新表 —— 分段清单以 JSON 存在 video_info，段级进度存在
         * favorites。手写 CREATE TABLE 要逐字猜中 Room 生成的 DDL（列序不参与校验，但类型、
         * 非空、主键、外键、索引都要对），而云端 CI 只编译、从不跑迁移，猜错就是装机即崩。
         * 这几条列全部声明成可空，SQL 里也就不需要 DEFAULT，两边天然一致。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE video_info ADD COLUMN summary TEXT")
                database.execSQL("ALTER TABLE video_info ADD COLUMN mediaJson TEXT")
                database.execSQL("ALTER TABLE favorites ADD COLUMN segmentProgress TEXT")
                database.execSQL("ALTER TABLE favorites ADD COLUMN lastSegmentIndex INTEGER")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "favorites.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3)
                // 兜底只对「没有迁移路径」的版本生效（比如最早那个没拆表的 v1 调试包）。
                // v2→v3 走上面的真迁移，本地收藏和缓存的解析结果都保得住。
                .fallbackToDestructiveMigration()
                .build()
    }
}
