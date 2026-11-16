package com.favkit.tablet.media

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片磁盘缓存（Coil 那份）的落点与开关口。
 *
 * 为什么要专门管它：Coil 2 的默认上限是「分区剩余空间的 2%，夹在 10MB~250MB 之间」，
 * 在平板上基本就是 250MB 这个天花板，而协议里没有小图地址，卡片和图集拉的是原图 ——
 * 实测每来一条收藏就涨 200KB 上下，删掉收藏却一字节不掉。所以这里既设一个明确上限，
 * 也给删除和「清空图片缓存」留出入口。
 */
class ImageStore(private val context: Context) {

    private val disk: DiskCache?
        get() = context.applicationContext.imageLoader.diskCache

    /** 与 install() 里给 Coil 的目录是同一个，探针和报数都指向它 */
    private fun directory(): File = File(context.applicationContext.cacheDir, DIR_NAME)

    /**
     * 目录里的真实字节，口径与 `MediaCache.totalCachedBytes()`、存储探针一致。
     *
     * 不用 `DiskCache.size`：那个数是 Coil 自己 journal 里记的总量，journal 丢了或被外部
     * 清理动过就会跟磁盘对不上 —— 视频缓存那边刚因为按索引报数出过「显示还剩一堆」的怪事，
     * 这里不再犯第二次。目录最多 32MB，文件数就一两百个，走一遍不贵。
     */
    suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { directory().walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }

    /**
     * 按 URL 精确回收。Coil 2 里 `HttpUriFetcher` 的磁盘键就是 `options.diskCacheKey ?: url`，
     * 而请求没有单独设键，所以收藏行里那个图片 URL 直接就能定位到它自己那份，不用遍历也不用维护映射。
     * 键对不上时 `remove` 返回 false，只是没回收，不会误删别人的。
     */
    suspend fun evict(urls: Collection<String>) {
        if (urls.isEmpty()) return
        withContext(Dispatchers.IO) {
            val cache = disk
            if (cache != null) {
                runCatching { urls.forEach { url -> cache.remove(url) } }
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            val cache = disk
            if (cache != null) {
                runCatching { cache.clear() }
            }
        }
    }

    companion object {
        /** 与 Coil 默认落点的目录名一致，换上限不会把已经缓存的图丢掉 */
        private const val DIR_NAME = "image_cache"
        private const val MB = 1024L * 1024L

        /** 32MB：按实测每条收藏 ~250KB 图片，够放一百多条的封面与图集 */
        const val MAX_BYTES_MB = 32
        val MAX_BYTES: Long = MAX_BYTES_MB * MB

        /**
         * 装到 Coil 的单例上，AsyncImage 与 Coil 组件取的都是这一个 loader。
         * 只覆盖 diskCache，其余保持默认，免得换 loader 时把解码器、内存缓存一起改了。
         *
         * `Coil.setImageLoader` 有 ImageLoader 和 ImageLoaderFactory 两个重载，这里显式写出工厂类型，
         * 不让重载解析靠 lambda 猜。
         */
        fun install(context: Context) {
            val app = context.applicationContext
            Coil.setImageLoader(ImageLoaderFactory {
                ImageLoader.Builder(app)
                    .diskCache(
                        DiskCache.Builder()
                            .directory(File(app.cacheDir, DIR_NAME))
                            .maxSizeBytes(MAX_BYTES)
                            .build(),
                    )
                    .build()
            })
        }
    }
}
