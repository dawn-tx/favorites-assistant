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
    version = 6,
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

        /** 库名写在这儿，存储探针才能自己去量这个文件，不用调用方再抄一遍字符串 */
        const val DB_NAME = "favorites.db"

        /**
         * 还在开发期，表结构改动按「清库重新从后台取」处理，不写迁移：
         * v4 把 video_info 合进 favorites；v5 加过 rev/hlcSeq；v6 把它们删掉（多人协作那套不再要），
         * 换成 serverUpdatedAt（对账用）与 mediaState（内容就绪状态），并给 url 建唯一索引。
         * 本地收藏与内容都能由后台一键重放，迁移反而要猜中 Room 生成的 DDL ——
         * 云端 CI 只编译、从不跑迁移，猜错就是装机即崩。
         *
         * journal 用 TRUNCATE 而不是 WAL：设备上实测 0 条收藏时 `favorites.db-wal` 还占 42 万字节
         * （它是没被 checkpoint 的写历史，与内容量无关），加 `-shm` 共 45 万。WAL 换来的是
         * 「写的时候还能读」，而全应用只有一个进程（manifest 里没有 android:process）、
         * 写又都被 SyncGate 串行化，这个并发用不上；换成 TRUNCATE 后每次提交就把回滚日志截干，
         * 那两个文件根本不再存在。
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .build()
    }
}
