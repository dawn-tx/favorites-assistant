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
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun mediaSourceFactory(): MediaSource.Factory = DefaultMediaSourceFactory(cachedFactory)

    fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSourceFactory())
        .build()

    fun totalCachedBytes(): Long = runCatching { cache.cachedBytes }.getOrDefault(0L)

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
     * 清空整片视频缓存。SimpleCache 一旦 release 就不能再用，所以这里重建实例与工厂；
     * 调用方必须先释放正在使用的播放器（见 MainViewModel.clearCache），新播放器会拿到新的工厂。
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { cache.release() }
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
        val source = cachedFactory.createDataSource(spec)
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
