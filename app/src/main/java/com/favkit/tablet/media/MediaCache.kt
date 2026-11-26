package com.favkit.tablet.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.favkit.tablet.data.AppSettings
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var cachedFactory: DataSource.Factory = newFactory(cache)

    /** 最近一次实际加载来源；键是绝对媒体 URL */
    val origins = MediaOriginTracker.origins

    /** 全局 429 冷却：期间新的视频播放/预取直接不发起请求，提示条显示剩余时间 */
    private val _throttleRemainingMs = MutableStateFlow(0L)
    val throttleRemainingMs: StateFlow<Long> = _throttleRemainingMs.asStateFlow()
    @Volatile private var throttleUntilMs = 0L
    private var throttleJob: Job? = null

    init {
        // 容量上限来自 DataStore，放到后台协程里补一次，避免在构造期做磁盘读
        scope.launch { applyLimitFromSettings() }
    }

    private fun newCache(maxBytes: Long): SimpleCache = SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(maxBytes),
        StandaloneDatabaseProvider(appContext),
    )

    private fun newFactory(target: SimpleCache): DataSource.Factory =
        TrackingCacheDataSourceFactory(appContext, target, upstream)

    fun mediaSourceFactory(): MediaSource.Factory = DefaultMediaSourceFactory(cachedFactory)
        .setLoadErrorHandlingPolicy(Http429RetryPolicy(::blockThrottle))

    fun remainingThrottleMs(): Long = (throttleUntilMs - System.currentTimeMillis()).coerceAtLeast(0)

    private fun blockThrottle(waitMs: Long) {
        val until = System.currentTimeMillis() + waitMs.coerceAtLeast(1_000)
        if (until <= throttleUntilMs) return
        throttleUntilMs = until
        throttleJob?.cancel()
        throttleJob = scope.launch {
            while (true) {
                val left = remainingThrottleMs()
                _throttleRemainingMs.value = left
                if (left <= 0) break
                delay(1_000)
            }
        }
    }

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
        MediaOriginTracker.forget(url)
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
        MediaOriginTracker.clear()
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
        if (remainingThrottleMs() > 0) {
            onProgress(0)
            return 0
        }
        val spec = dataSpecFor(url)
        var written = 0L
        withContext(Dispatchers.IO) {
            val source = cachedFactory.createDataSource()
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
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (error.responseCode == 429) blockThrottle(retryAfterMs(error.headerFields))
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

/**
 * 播放器的 429：不重试，记录全局冷却并让当前请求失败。
 *
 * Retry-After 可能是秒数或 HTTP 日期；冷却期间新的播放/预取直接拦下，
 * UI 按剩余时间提示。没有 Retry-After 时按 1 秒冷却。
 */
private class Http429RetryPolicy(
    private val onThrottled: (Long) -> Unit,
) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error !is HttpDataSource.InvalidResponseCodeException || error.responseCode != 429) {
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
        // 429 不重试：记录全局冷却，让当前请求失败、新请求直接拦下并显示倒计时。
        onThrottled(retryAfterMs(error.headerFields))
        return C.TIME_UNSET
    }
}

/** Retry-After 支持秒数与 HTTP 日期；返回原始毫秒，由冷却层决定展示时长 */
private fun retryAfterMs(headers: Map<String, List<String>>): Long {
    val raw = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
        ?.value?.firstOrNull()?.trim()
        ?: return RETRY_DEFAULT_DELAY_MS
    raw.toLongOrNull()?.let { return it * 1000 }
    return runCatching {
        val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (at - System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrDefault(RETRY_DEFAULT_DELAY_MS)
}

private const val RETRY_DEFAULT_DELAY_MS = 1_000L
