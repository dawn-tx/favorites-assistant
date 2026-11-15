package com.favkit.tablet.net

import com.favkit.tablet.data.AppSettings
import com.favkit.tablet.data.SettingsSnapshot
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 与收藏后台的唯一出口：明文 JSON，一条请求就是一批 ops。
 *
 * 成功判据只认后台明确确认的条数，由 [com.favkit.tablet.sync.SyncWorker] 核对
 * `applied + unknown_ops == 送出条数`。早期版本走信封加密，后台解不开时仍回 200 + applied=0，
 * App 误判成功并清空本地队列，数据被静默丢弃 —— 所以宁可后台回 4xx，也不接受「看起来成功的空响应」。
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

    /** 上传一批操作；后台在同一次调用里保存并解析，回执直接带回解析后的收藏 */
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
        return exchange(config, "/sync", body, ::mapSyncError) { text, code ->
            when {
                code == 429 -> {
                    val retryAfter = runCatching {
                        json.decodeFromString(SyncResponse.serializer(), text).nextWaitMs
                    }.getOrNull()
                    gate.noteServerWait(retryAfter)
                    SyncOutcome.Retryable("后台限速：HTTP 429", retryAfterMs = retryAfter)
                }

                code in 400..499 -> SyncOutcome.Rejected(rejectionReason(text) ?: "后台拒绝：HTTP $code")
                code >= 500 -> SyncOutcome.Retryable("后台繁忙：HTTP $code")
                else -> {
                    val parsed = json.decodeFromString(SyncResponse.serializer(), text)
                    gate.noteServerWait(parsed.nextWaitMs)
                    if (!parsed.ok) {
                        SyncOutcome.Rejected(parsed.error.ifBlank { "后台未应用任何改动（applied=0）" })
                    } else {
                        SyncOutcome.Success(
                            applied = parsed.applied,
                            serverTime = parsed.serverTime,
                            unknownOps = parsed.unknownOps,
                            favorites = parsed.favorites,
                            nextWaitMs = parsed.nextWaitMs,
                        )
                    }
                }
            }
        }
    }

    /**
     * 拉一页收藏。后台按 `favorite_at` 倒序发，游标是上一页最后那条的时间戳，
     * 所以中途新增的收藏不会被分页跨过，重复的那几条由入库时按 id 去掉。
     */
    suspend fun pull(cursor: String?, limit: Int): PullOutcome {
        val config = settings.current()
        val query = buildString {
            append("/favorites?limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        return exchange(config, query, null, { reason -> PullOutcome.Failed(reason) }) { text, code ->
            if (code in 400..499) {
                PullOutcome.Failed("后台拒绝：HTTP $code")
            } else {
                val parsed = json.decodeFromString(PullResponse.serializer(), text)
                if (!parsed.ok) {
                    PullOutcome.Failed(parsed.error.ifBlank { "后台没有返回这一页" })
                } else {
                    PullOutcome.Loaded(parsed.favorites, parsed.nextCursor, parsed.hasMore, parsed.total)
                }
            }
        }
    }

    /**
     * 手动重新解析这几条收藏：后台重算内容信息并原样带回，`favorite_at` 不变。
     *
     * 只有用户在遮罩层点「重新解析」时才发这一条请求 —— 自动重试没有意义，
     * 同一条链接的解析结果由链接本身决定，再问一遍只会多占一个出站时隙。
     */
    suspend fun resolve(ids: List<String>): ResolveOutcome {
        if (ids.isEmpty()) return ResolveOutcome()
        val body = json.encodeToString(ResolveRequest.serializer(), ResolveRequest(favoriteIds = ids))
        val config = settings.current()
        return exchange(config, "/resolve", body, { reason -> ResolveOutcome(error = reason) }) { text, code ->
            if (code in 400..499) {
                val parsed = runCatching { json.decodeFromString(ResolveResponse.serializer(), text) }.getOrNull()
                ResolveOutcome(error = parsed?.error?.ifBlank { null } ?: "后台拒绝：HTTP $code")
            } else {
                val parsed = json.decodeFromString(ResolveResponse.serializer(), text)
                gate.noteServerWait(parsed.nextWaitMs)
                if (!parsed.ok) {
                    ResolveOutcome(error = parsed.error.ifBlank { "后台没有返回解析结果" })
                } else {
                    ResolveOutcome(parsed.favorites, parsed.unknownIds, null)
                }
            }
        }
    }

    @Serializable
    private data class ResolveRequest(@SerialName("favorite_ids") val favoriteIds: List<String>)

    /**
     * 统一出口。[parse] 收到响应体与状态码；[onFailure] 负责把传输层/解析层失败映射成各自的失败类型，
     * 这样新增接口时不用再抄一遍 OkHttp 的错误分支。
     */
    private suspend fun <R> exchange(
        config: SettingsSnapshot,
        path: String,
        body: String?,
        onFailure: (String) -> R,
        parse: suspend (String, Int) -> R,
    ): R = gate.run(config.uploadIntervalMs) {
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

    /** 后台 4xx 的中文原因优先于状态码展示，方便在设置页直接看懂 */
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
        /** 这一页的收藏，每条都已带后台的解析结果 */
        val favorites: List<FavoriteDto>,
        /** 下一页的游标；空串表示后台没有更多了 */
        val nextCursor: String = "",
        val hasMore: Boolean = false,
        /** 后台收藏总数，每页都一样；0 表示这个后台还没上报总数 */
        val total: Int = 0,
    ) : PullOutcome

    data class Failed(val reason: String) : PullOutcome
}
