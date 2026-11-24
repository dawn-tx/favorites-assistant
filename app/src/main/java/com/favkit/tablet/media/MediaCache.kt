package com.favkit.tablet.media

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.favkit.tablet.data.AppSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放与预缓存共用同一条缓存链路：在线播放时字节顺带落盘，之后再点直接命中本地缓存播放，
 * 因此「无网络也能播放看过的视频」不需要独立的下载器。
 *
 * DataSpec 不设置自定义 key，缓存键就是资源 URI 本身，保证预缓存与播放命中同一份数据。
 */
class MediaCache(context: Context, private val settings: AppSettings) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.externalCacheDir ?: appContext.cacheDir, "media-v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val upstream by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
    }

    private var limitBytes = DEFAULT_LIMIT
    private var cache: SimpleCache = newCache(limitBytes)
    private var cachedFactory: CacheDataSource.Factory = newFactory(cache)

    init {
        // 容量上限来自 DataStore，放到后台协程里补一次，避免在构造期做磁盘读
        scope.launch { applyLimitFromSettings() }
    }

    private fun newCache(maxBytes: Long): SimpleCache = SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(maxBytes),
        StandaloneDatabaseProvider(appContext),
    )

    private fun newFactory(target: SimpleCache): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(target)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, upstream))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)

    fun mediaSourceFactory(): MediaSource.Factory = DefaultMediaSourceFactory(cachedFactory)

    /** blob 落在 externalCacheDir 还是内部 cacheDir 由系统给不给外部目录决定，探针要量的是同一个路径 */
    fun cacheRootPath(): String = cacheDir.absolutePath

    fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSourceFactory())
        .build()

    /**
     * 视频缓存的总量口径 = `media-v1` 目录里真实存在的字节。
     *
     * 不用索引汇总（`keys` + `getCachedSpans`）：系统「清除缓存」或厂商清理会直接删掉 blob 文件，
     * 而 `exoplayer_internal.db` 里的 span 记录还在，按索引报数就会出现
     * 「磁盘上什么都没有、app 里显示还剩一堆」，并且只有重启后才换一个数。
     *
     * 遍历放在 IO 线程：启动、打开设置、打开筛选抽屉都要现量一次，主线程上走磁盘就是那一下的卡顿。
     */
    suspend fun totalCachedBytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /**
     * 把一条链接留下的视频字节要回来。
     *
     * 缓存键的来历（从 media3 1.4.1 的字节码看，不是推测）：`CacheDataSource` 用 `CacheKeyFactory.DEFAULT`
     * 造键，它的实现是 `dataSpec.key != null ? dataSpec.key : dataSpec.uri.toString()`；本应用建 DataSpec
     * 不设 key，播放器那条链也一样，所以键就是 URL 字符串本身。`removeResource` 只认这个键，
     * 传收藏行里的原 URL 就正合适，别画蛇添足去 parse 一遍。
     */
    suspend fun evict(url: String) = withContext(Dispatchers.IO) {
        runCatching { cache.removeResource(url) }
    }

    /**
     * media3 的容量上限只能在建缓存时通过 evictor 定下，没有运行中改容量的接口，
     * 所以设置里改上限要连同缓存与工厂一起重建；数值没变时直接返回，
     * 调用方（MainViewModel.setCacheLimitMb）会先释放播放器再走到这里。
     */
    suspend fun applyLimitFromSettings() {
        val bytes = settings.current().cacheLimitMb * MB
        if (bytes == limitBytes) return
        withContext(Dispatchers.IO) {
            runCatching { cache.release() }
            limitBytes = bytes
            cache = newCache(bytes)
            cachedFactory = newFactory(cache)
        }
    }

    /**
     * 清空整片视频缓存：blob 与索引一起删。
     *
     * 之前只 `deleteRecursively` 缓存目录，`exoplayer_internal.db` 留在内部 storage 里，
     * 实测清完之后它还占 8 万字节、且随每次清空继续涨 —— 那些记录指向已经不存在的文件。
     * SimpleCache 的静态 delete 会连带删掉它建的索引库；实例 release 之后不能复用，
     * 所以还要连同工厂一起重建，调用方必须先释放正在使用的播放器（见 MainViewModel.clearCache）。
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { cache.release() }
        runCatching { SimpleCache.delete(cacheDir, StandaloneDatabaseProvider(appContext)) }
        runCatching { cacheDir.deleteRecursively() }
        limitBytes = settings.current().cacheLimitMb * MB
        cache = newCache(limitBytes)
        cachedFactory = newFactory(cache)
    }

    /**
     * 顺序读满整个资源使其进入 SimpleCache。只用稳定 API 手工读，
     * 不依赖 media3 标记为不稳定的 Downloader。
     */
    suspend fun prefetch(
        url: String,
        onProgress: (cached: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val spec = dataSpecFor(url)
        val source = cachedFactory.createDataSource()
        var written = 0L
        withContext(Dispatchers.IO) {
            try {
                source.open(spec)
                val buffer = ByteArray(READ_CHUNK_BYTES)
                var lastReport = 0L
                while (!isCancelled()) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    written += read
                    if (written - lastReport >= REPORT_EVERY) {
                        lastReport = written
                        onProgress(written)
                    }
                }
            } catch (end: Exception) {
                // 读到结尾、被取消或上游报错都从这里出来，已落盘的字节仍在缓存里
            } finally {
                runCatching { source.close() }
            }
        }
        onProgress(written)
        return written
    }

    private fun dataSpecFor(url: String): DataSpec = DataSpec.Builder()
        .setUri(Uri.parse(url))
        .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
        .build()

    companion object {
        private const val MB = 1024L * 1024L
        private const val DEFAULT_LIMIT = 512L * MB
        private const val REPORT_EVERY = 2L * MB
        private const val READ_CHUNK_BYTES = 128 * 1024
        private const val USER_AGENT = "FavoritesAssistant/1.0 (Android Tablet)"
    }
}
