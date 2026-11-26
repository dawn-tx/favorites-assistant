package com.favkit.tablet.media

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一次媒体请求最终是从哪里来的；这三个值互斥，按 Local > Edge > Origin 的顺序判定。 */
enum class MediaOrigin {
    LOCAL,
    EDGE,
    ORIGIN,
}

/**
 * 记录最近一次真实加载来源。视频和图片共用同一张表，键是绝对媒体 URL。
 * 这里不负责发探测请求，只记录已经发生的播放/图片加载路径。
 */
object MediaOriginTracker {
    private val _origins = MutableStateFlow<Map<String, MediaOrigin>>(emptyMap())
    val origins: StateFlow<Map<String, MediaOrigin>> = _origins.asStateFlow()

    fun record(url: String, origin: MediaOrigin) {
        if (url.isBlank()) return
        _origins.update { current ->
            if (current[url] == origin) current else current + (url to origin)
        }
    }

    fun forget(url: String) {
        _origins.update { it - url }
    }

    fun clear() {
        _origins.value = emptyMap()
    }
}

/**
 * 给 CacheDataSource 套一层：
 * - 本地缓存直接满足请求时，下一次上游 open 不会发生 -> LOCAL；
 * - 打开上游时由 TrackingHttpDataSource 记录 EDGE / ORIGIN。
 */
private class TrackingCacheDataSource(
    private val delegate: DataSource,
    private val session: SourceSession,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        session.networkOpened = false
        val length = delegate.open(dataSpec)
        if (!session.networkOpened) {
            MediaOriginTracker.record(dataSpec.uri.toString(), MediaOrigin.LOCAL)
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun close() = delegate.close()

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
}

private class TrackingHttpDataSource(
    private val delegate: HttpDataSource,
    private val session: SourceSession,
) : HttpDataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val length = delegate.open(dataSpec)
        session.networkOpened = true
        val status = delegate.responseHeaders.entries
            .firstOrNull { it.key.equals("cf-cache-status", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
        val url = delegate.uri?.toString() ?: dataSpec.uri.toString()
        MediaOriginTracker.record(
            url,
            if (status.equals("HIT", ignoreCase = true)) MediaOrigin.EDGE else MediaOrigin.ORIGIN,
        )
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun close() = delegate.close()

    override fun setRequestProperty(name: String, value: String) {
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate.clearAllRequestProperties()
    }

    override fun getResponseCode(): Int = delegate.responseCode

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
}

private class TrackingHttpDataSourceFactory(
    private val delegate: HttpDataSource.Factory,
    private val session: SourceSession,
) : HttpDataSource.Factory {
    override fun createDataSource(): HttpDataSource =
        TrackingHttpDataSource(delegate.createDataSource(), session)

    override fun setDefaultRequestProperties(
        defaultRequestProperties: Map<String, String>,
    ): HttpDataSource.Factory {
        delegate.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }
}

private class SourceSession {
    @Volatile
    var networkOpened: Boolean = false
}

/** 每个 CacheDataSource 实例都带自己的 session，避免播放器/预取并发时互相覆盖。 */
class TrackingCacheDataSourceFactory(
    private val context: Context,
    private val cache: SimpleCache,
    private val upstream: HttpDataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val session = SourceSession()
        val cacheSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(context, TrackingHttpDataSourceFactory(upstream, session)),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSource()
        return TrackingCacheDataSource(cacheSource, session)
    }
}

/** Coil 网络请求的响应头也记录进来；本地命中由 AsyncImage 的 dataSource 单独记录为 LOCAL。 */
fun recordImageOrigin(url: String, headerValue: String?) {
    if (headerValue.equals("HIT", ignoreCase = true)) {
        MediaOriginTracker.record(url, MediaOrigin.EDGE)
    } else {
        MediaOriginTracker.record(url, MediaOrigin.ORIGIN)
    }
}