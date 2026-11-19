package com.favkit.tablet.net

import com.favkit.tablet.data.AppSettings
import com.favkit.tablet.data.SettingsSnapshot
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 与收藏后台的唯一出口：明文 JSON，一次请求一条上传操作、或一次读请求。
 *
 * 成功判据只认后台明确确认的条数（`applied == 1`）。早期版本走信封加密，后台解不开仍回 200 + applied=0，
 * App 误判成功并清空本地队列，数据被静默丢弃 —— 所以宁可后台回 4xx，也不接受「看起来成功的空响应」。
 *
 * 出站全部排在同一条 [SyncGate] 上：上传按设置里的间隔走，读请求（对账、lookup）只串行、不等 ——
 * 对账一次要翻十几页，让每页都等 3 秒是没法用的。
 */
class SyncApi(
    private val settings: AppSettings,
    private val gate: SyncGate,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 上传一条操作；后台收下后回执带回这条的最新状态（内容多半还是 pending） */
    suspend fun push(ops: List<OpDto>): SyncOutcome {
        val config = settings.current()
        val body = json.encodeToString(
            SyncRequest.serializer(),
            SyncRequest(
                deviceId = config.deviceId.ifBlank { settings.ensureDeviceId() },
                sentAt = System.currentTimeMillis(),
                ops = ops,
            ),
        )
        return exchange(config, "/sync", body, pacing = true, ::mapSyncError) { text, code ->
            when {
                code == 429 -> {
                    val throttled = runCatching {
                        json.decodeFromString(SyncResponse.serializer(), text)
                    }.getOrNull()
                    gate.noteServerWait(throttled?.nextWaitMs)
                    SyncOutcome.Retryable("后台限速：HTTP 429", retryAfterMs = throttled?.nextWaitMs)
                }

                code in 400..499 -> SyncOutcome.Rejected(rejectionReason(text) ?: "后台拒绝：HTTP $code")
                code >= 500 -> SyncOutcome.Retryable("后台繁忙：HTTP $code")
                else -> {
                    val parsed = json.decodeFromString(SyncResponse.serializer(), text)
                    gate.noteServerWait(parsed.nextWaitMs)
                    if (!parsed.ok) {
                        SyncOutcome.Rejected(parsed.error.ifBlank { "后台未应用这次改动" })
                    } else {
                        SyncOutcome.Success(
                            applied = parsed.applied,
                            serverTime = parsed.serverTime,
                            favorites = parsed.favorites,
                            nextWaitMs = parsed.nextWaitMs,
                        )
                    }
                }
            }
        }
    }

    /**
     * 拉一页**清单**（`fields=min`）：只有 url、收藏时间、后台更新时刻与内容状态，不带内容。
     *
     * 对账先翻完整的清单做差集，再只为差异的那几条取内容 —— 内容占一条字节的七成以上，
     * 千条量级翻一遍清单比翻一遍整行便宜一个数量级。
     */
    suspend fun pullManifest(cursor: String?, limit: Int): PullOutcome {
        val config = settings.current()
        val query = buildString {
            append("/favorites?fields=min&limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        return exchange(config, query, null, pacing = false, { reason -> PullOutcome.Failed(reason) }) { text, code ->
            parsePage(text, code)
        }
    }

    /** 取整行页：冷启动/滚到底补页用，每条自带内容 */
    suspend fun pull(cursor: String?, limit: Int): PullOutcome {
        val config = settings.current()
        val query = buildString {
            append("/favorites?limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        return exchange(config, query, null, pacing = false, { reason -> PullOutcome.Failed(reason) }) { text, code ->
            parsePage(text, code)
        }
    }

    private fun parsePage(text: String, code: Int): PullOutcome {
        if (code in 400..499) return PullOutcome.Failed("后台拒绝：HTTP $code")
        val parsed = json.decodeFromString(PullResponse.serializer(), text)
        return if (!parsed.ok) {
            PullOutcome.Failed(parsed.error.ifBlank { "后台没有返回这一页" })
        } else {
            PullOutcome.Loaded(parsed.favorites, parsed.nextCursor, parsed.hasMore, parsed.total)
        }
    }

    /**
     * 只为这些 url 取内容（差集算完之后的第二趟）。
     *
     * 后台一次最多 200 个，超出的由调用方分批 —— 这里不静默截断：截断等于把用户看不见的那批
     * 永远停在旧内容上。失败就是失败，交给调用方下次再试。
     */
    suspend fun lookup(urls: List<String>): LookupOutcome {
        if (urls.isEmpty()) return LookupOutcome.Loaded(emptyList())
        val body = json.encodeToString(LookupRequest.serializer(), LookupRequest(urls))
        val config = settings.current()
        return exchange(config, "/favorites/lookup", body, pacing = false, { reason -> LookupOutcome.Failed(reason) }) { text, code ->
            if (code in 400..499) {
                LookupOutcome.Failed("后台拒绝：HTTP $code")
            } else {
                val parsed = json.decodeFromString(LookupResponse.serializer(), text)
                if (!parsed.ok) LookupOutcome.Failed(parsed.error.ifBlank { "后台没有返回内容" })
                else LookupOutcome.Loaded(parsed.favorites)
            }
        }
    }

    /**
     * 打开一条还没就绪的收藏时，单独问一次它的状态。
     *
     * 这是**用户动作**触发的：点开那条才问一下。不做轮询、不做定时器 —— 用户盯着一条正在转存的
     * 大视频时，他点开的那一下就是最合理的询问时机。
     */
    suspend fun peek(url: String): LookupOutcome = lookup(listOf(url))

    /**
     * 重建内容：后台丢弃旧结果重排一次转存，立刻返回（这条回到 pending）。
     * [urls] 为空直接返回成功，不发请求。
     */
    suspend fun resolve(urls: List<String>): ResolveOutcome {
        if (urls.isEmpty()) return ResolveOutcome()
        val body = json.encodeToString(ResolveRequest.serializer(), ResolveRequest(urls = urls))
        val config = settings.current()
        return exchange(config, "/resolve", body, pacing = true, { reason -> ResolveOutcome(error = reason) }) { text, code ->
            if (code in 400..499) {
                val parsed = runCatching { json.decodeFromString(ResolveResponse.serializer(), text) }.getOrNull()
                ResolveOutcome(error = parsed?.error?.ifBlank { null } ?: "后台拒绝：HTTP $code")
            } else {
                val parsed = json.decodeFromString(ResolveResponse.serializer(), text)
                gate.noteServerWait(parsed.nextWaitMs)
                if (!parsed.ok) {
                    ResolveOutcome(error = parsed.error.ifBlank { "后台没有受理这次重建" })
                } else {
                    ResolveOutcome(parsed.favorites, parsed.unknownUrls, null)
                }
            }
        }
    }

    /**
     * 统一出口。[pacing] 为真时吃上传间隔（一次上传占一个时隙），读请求只排队不等待。
     * [parse] 收到响应体与状态码；[onFailure] 把传输层/解析层失败映射成各自的失败类型。
     */
    private suspend fun <R> exchange(
        config: SettingsSnapshot,
        path: String,
        body: String?,
        pacing: Boolean,
        onFailure: (String) -> R,
        parse: suspend (String, Int) -> R,
    ): R = gate.run(if (pacing) config.uploadIntervalMs else 0L) {
        withContext(Dispatchers.IO) {
            val url = buildUrl(config.syncEndpoint, path)
                ?: return@withContext onFailure("同步地址无效，请在设置里填写 http(s):// 开头的后台地址")
            val builder = Request.Builder().url(url)
            if (config.syncToken.isNotBlank()) builder.header("Authorization", "Bearer ${config.syncToken}")
            builder.header("X-Device-Platform", "android-tablet")
            if (body != null) {
                builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    parse(response.body?.string().orEmpty(), response.code)
                }
            } catch (io: IOException) {
                onFailure(io.message ?: "网络不可用")
            } catch (broken: Exception) {
                onFailure("后台响应格式不符：${broken.message ?: "解析失败"}")
            }
        }
    }

    private fun mapSyncError(reason: String): SyncOutcome =
        if (reason.startsWith("同步地址无效")) SyncOutcome.Rejected(reason) else SyncOutcome.Retryable(reason)

    /** 后台 4xx 的中文原因优先于状态码展示 */
    private fun rejectionReason(text: String): String? = runCatching {
        json.decodeFromString(SyncResponse.serializer(), text).error
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun buildUrl(endpoint: String, path: String): String? {
        val base = endpoint.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return base + path
    }
}

sealed interface PullOutcome {
    data class Loaded(
        /** 这一页的收藏；清单档里 `media` 为空、只有状态 */
        val favorites: List<FavoriteDto>,
        /** 下一页的游标；空串表示后台没有更多了 */
        val nextCursor: String = "",
        val hasMore: Boolean = false,
        /** 后台收藏总数，每页都一样；0 表示这个后台还没上报总数 */
        val total: Int = 0,
    ) : PullOutcome

    data class Failed(val reason: String) : PullOutcome
}

sealed interface LookupOutcome {
    data class Loaded(val favorites: List<FavoriteDto>) : LookupOutcome
    data class Failed(val reason: String) : LookupOutcome
}
