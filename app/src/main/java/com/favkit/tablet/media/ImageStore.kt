package com.favkit.tablet.media

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import com.favkit.tablet.data.AppSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 图片磁盘缓存（Coil 那份）的落点、容量上限与开关口。
 *
 * 为什么要专门管它：Coil 2 的默认上限是「分区剩余空间的 2%，夹在 10MB~250MB 之间」，
 * 在平板上基本就是 250MB 这个天花板，而协议里没有小图地址，卡片和图集拉的是原图 ——
 * 实测每来一条收藏就涨 200KB 上下，删掉收藏却一字节不掉。所以这里既设一个明确上限，
 * 也给删除和「清空图片缓存」留出入口。
 *
 * 上限来自 DataStore，可在设置面板里改。Coil 的 `DiskCache.maxSize` 是 val、没有运行期
 * 改容量的接口，所以改上限要连 DiskCache 带整个 ImageLoader 一起重建（与视频缓存那边
 * 重建 SimpleCache 同一套路）；重建走的目录不变，已缓存的图一张不丢。
 */
class ImageStore(context: Context, private val settings: AppSettings) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rebuildMutex = Mutex()

    /** 上次建 loader 用的上限，字节；与 settings 里的 MB 值对齐后再比，避免重复重建 */
    @Volatile
    private var limitBytes: Long = DEFAULT_BYTES

    /** 当前生效的 DiskCache，可能随上限调整被整体换掉；取的永远是 Coil 单例上的那个 */
    private val disk: DiskCache?
        get() = app.imageLoader.diskCache

    /** 与 install() 里给 Coil 的目录是同一个，探针和报数都指向它 */
    private fun directory(): File = File(app.cacheDir, DIR_NAME)

    init {
        // 容量上限来自 DataStore，放到后台协程里补一次，避免在构造期做磁盘读（与 MediaCache 一致）
        scope.launch { runCatching { applyLimitFromSettings() } }
    }

    /**
     * 目录里的真实字节，口径与 `MediaCache.totalCachedBytes()`、存储探针一致。
     *
     * 不用 `DiskCache.size`：那个数是 Coil 自己 journal 里记的总量，journal 丢了或被外部
     * 清理动过就会跟磁盘对不上 —— 视频缓存那边刚因为按索引报数出过「显示还剩一堆」的怪事，
     * 这里不再犯第二次。目录上限几十到几百 MB，文件数一两百到上千个，走一遍不贵。
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
                urls.forEach(MediaOriginTracker::forget)
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

    /**
     * 按设置里的上限重建 Coil 的单例 loader。数值没变时直接返回。
     *
     * 顺序有讲究：先把新 loader 装上单例再 `shutdown()` 旧 loader。核对过 Coil 2.7.0 的源码：
     * `RealImageLoader.shutdown()` 只取消请求协程并清内存缓存，不碰磁盘缓存；而旧 loader 的
     * DiskLruCache 没有公开的 close，只能靠 shutdown 断流。极小概率上新旧两个实例还在同一目录
     * 各写各的 journal，但 Coil 在 journal 损坏时会自动清目录重建，最坏结果只是缓存图重新拉一遍，
     * 不会崩。副作用：换 loader 时内存缓存被一起换掉，正在屏上的图片会重新解码一次。
     */
    suspend fun applyLimitFromSettings() = rebuildMutex.withLock {
        val bytes = settings.current().imageCacheLimitMb * MB
        if (bytes == limitBytes) return@withLock
        withContext(Dispatchers.IO) {
            val old = app.imageLoader
            val fresh = ImageLoader.Builder(app)
                .okHttpClient(originClient)
                .diskCache(
                    DiskCache.Builder()
                        .directory(File(app.cacheDir, DIR_NAME))
                        .maxSizeBytes(bytes)
                        .build(),
                )
                .build()
            Coil.setImageLoader(fresh)
            runCatching { old.shutdown() }
            limitBytes = bytes
        }
    }

    companion object {
        /** 与 Coil 默认落点的目录名一致，换上限不会把已经缓存的图丢掉 */
        private const val DIR_NAME = "image_cache"
        private const val MB = 1024L * 1024L

        /** 图片网络请求只做一件事：把 Cloudflare 的缓存结果记到来源表。 */
        private val originClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    recordImageOrigin(
                        chain.request().url.toString(),
                        response.header("cf-cache-status"),
                    )
                    response
                }
                .build()
        }

        /** 默认 32MB：按实测每条收藏 ~250KB 图片，够放一百多条的封面与图集 */
        const val DEFAULT_LIMIT_MB = 32L
        const val DEFAULT_BYTES: Long = DEFAULT_LIMIT_MB * MB

        /**
         * 装到 Coil 的单例上，AsyncImage 与 Coil 组件取的都是这一个 loader。
         * 只覆盖 diskCache，其余保持默认，免得换 loader 时把解码器、内存缓存一起改了。
         *
         * `Coil.setImageLoader` 有 ImageLoader 和 ImageLoaderFactory 两个重载，这里显式写出工厂类型，
         * 不让重载解析靠 lambda 猜。这里只装默认上限的工厂，真正按 DataStore 校准留给
         * ImageStore 实例 init 里的 applyLimitFromSettings，磁盘读不占启动路径。
         */
        fun install(context: Context, limitBytes: Long = DEFAULT_BYTES) {
            val app = context.applicationContext
            Coil.setImageLoader(ImageLoaderFactory {
                ImageLoader.Builder(app)
                    .okHttpClient(originClient)
                    .diskCache(
                        DiskCache.Builder()
                            .directory(File(app.cacheDir, DIR_NAME))
                            .maxSizeBytes(limitBytes)
                            .build(),
                    )
                    .build()
            })
        }
    }
}
