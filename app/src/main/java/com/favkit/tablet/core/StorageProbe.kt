package com.favkit.tablet.core

import android.content.Context
import android.system.Os
import com.favkit.tablet.data.db.AppDatabase
import com.favkit.tablet.media.MediaCache
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 存储明细探针：只读地量一遍应用自己的私有目录，用来对着系统「设置 → 应用 → 存储」那两个数，
 * 看多出来的字节到底落在哪个文件上。
 *
 * 每个文件报两列：表观 = `File.length()`，占盘 = `stat` 的 `st_blocks × 512`。
 * 系统按占盘计，而磁盘按块分配，几 KB 的数据库文件成串排下来能比表观大好些倍 ——
 * 只看表观一定对不上系统那个数，所以两列一起给。
 *
 * 全程只有 listFiles / stat / 数行数，不删不改不建，测量本身不会改变被测对象。
 */
class StorageProbe(
    private val context: Context,
    private val db: AppDatabase,
    private val media: MediaCache,
) {

    suspend fun report(): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val internalDir = app.dataDir
        val externalDir = app.externalCacheDir
        buildString {
            appendLine("表观 = 文件长度，占盘 = 实际占的磁盘块（与系统「存储」同源），单位都是字节")
            appendLine()
            appendLine("内部目录：${internalDir.absolutePath}")
            appendLine("外部缓存：${externalDir?.absolutePath ?: "（不可用）"}")
            appendLine("视频缓存目录：${media.cacheRootPath()}")
            appendLine("内部合计：${total(internalDir, false).num()}${total(internalDir, true).num()}")
            externalDir?.let { appendLine("外部合计：${total(it, false).num()}${total(it, true).num()}") }
            appendLine()
            appendLine(COLUMN_HEAD)
            dirSection("数据库", File(internalDir, "databases"))
            dirSection("DataStore", File(internalDir, "datastore"))
            dirSection("files", File(internalDir, "files"))
            dirSection("no_backup", app.noBackupFilesDir)
            dirSection("shared_prefs", File(internalDir, "shared_prefs"))
            dirSection("内部 cache", app.cacheDir)
            externalDir?.let { dirSection("外部 cache", it) }
            dirSection("视频缓存 blob", File(media.cacheRootPath()))
            appendLine()
            appendLine(dbBlock())
        }
    }

    /** 一个目录展开一层：文件按占盘从大到小列前若干条，子目录各给递归合计，末行是本目录合计。 */
    private fun StringBuilder.dirSection(title: String, dir: File) {
        appendLine("[$title] ${dir.absolutePath}")
        val entries = dir.listFiles()?.toList()
        if (entries.isNullOrEmpty()) {
            appendLine(if (dir.isDirectory) "  （空目录）" else "  （不存在）")
            return
        }
        val files = entries.filter { it.isFile }.sortedByDescending { disk(it) }
        files.take(MAX_LISTED_FILES).forEach { f ->
            appendLine("  ${f.name.padEnd(COLUMN)}${f.length().num()}${disk(f).num()}")
        }
        if (files.size > MAX_LISTED_FILES) {
            appendLine("  …另有 ${files.size - MAX_LISTED_FILES} 个文件未列出，已计入合计")
        }
        entries.filter { it.isDirectory }
            .sortedByDescending { total(it, true) }
            .forEach { d ->
                appendLine("  ${"${d.name}/".padEnd(COLUMN)}${total(d, false).num()}${total(d, true).num()}")
            }
        appendLine("  ${"合计（含子目录）".padEnd(COLUMN)}${total(dir, false).num()}${total(dir, true).num()}")
    }

    /** 库里到底有什么：行数拆成未软删 / 墓碑 / 总行，再给每行平均，空页与索引都摊在这个数里 */
    private suspend fun dbBlock(): String = buildString {
        val app = context.applicationContext
        val live = db.favorites().cachedCount()
        val all = db.favorites().rowCountAll()
        val tombstones = db.favorites().tombstoneCount()
        val pending = db.pendingOps().observeCount().first()
        val main = app.getDatabasePath(AppDatabase.DB_NAME)
        appendLine("[收藏库内容]")
        appendLine("  favorites 行：$all（未软删 $live · 墓碑 $tombstones）· 待传操作 $pending")
        appendLine("  ${AppDatabase.DB_NAME.padEnd(COLUMN)}${main.length().num()}${disk(main).num()}")
        for (suffix in SIDE_FILES) {
            val side = File(main.parentFile, main.name + suffix)
            if (side.exists()) {
                appendLine("  ${(main.name + suffix).padEnd(COLUMN)}${side.length().num()}${disk(side).num()}")
            }
        }
        if (all > 0) {
            appendLine("  每行平均 ${main.length() / all} 字节（含索引、空页与墓碑）")
        }
    }

    private fun disk(file: File): Long = runCatching {
        Os.stat(file.absolutePath).st_blocks * BLOCK_BYTES
    }.getOrDefault(file.length())

    private fun total(dir: File, useDisk: Boolean): Long = runCatching {
        dir.walkBottomUp.filter { it.isFile }.sumOf { f -> if (useDisk) disk(f) else f.length() }
    }.getOrDefault(0L)

    private fun Long.num(): String = String.format(Locale.US, "%,13d", this)

    companion object {
        private const val COLUMN = 34
        private const val MAX_LISTED_FILES = 12
        private const val BLOCK_BYTES = 512L
        private val SIDE_FILES = listOf("-wal", "-shm")
        private val COLUMN_HEAD =
            "  " + "文件".padEnd(COLUMN) + "表观".padStart(13) + "占盘".padStart(13)
    }
}
