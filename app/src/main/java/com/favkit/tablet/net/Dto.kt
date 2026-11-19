package com.favkit.tablet.net

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.OpType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后台协议：全部明文 JSON，改任何一边都要同步改 mock-server/server.mjs。
 *
 * 这个后台是**一个人**的收藏库，所以协议里没有账号、版本号、字段戳与冲突裁决：
 * 收藏的同步身份就是 `url`（本机的 `id` 只是个本地句柄，不出站）。
 *
 * 内容信息**不再**随保存一起回来：后台要先解析、下载、把素材转存到存储服务器（大视频切片），
 * 拿到存储 id 之后才算就绪。所以每条收藏都有一个 [mediaState]：`pending` / `ready` / `failed`。
 */
@Serializable
data class FavoriteDto(
    val url: String,
    val note: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("source_app") val sourceApp: String = "",
    @SerialName("favorite_at") val favoriteAt: Long = 0L,
    /**
     * 后台这一行最后一次变化（用户字段或内容）的时刻。
     *
     * 对账靠它判断「这条要不要重新取内容」：清单档带它、不带内容，省掉把 media 全搬一遍的流量。
     * 0 表示后台没给（老后台），那时退回「每次都当它变过」。
     */
    @SerialName("updated_at") val updatedAt: Long = 0L,
    /** `pending` / `ready` / `failed`；清单档也带，空串表示后台没这个字段 */
    @SerialName("media_state") val mediaState: String = "",
    /** 非空表示后台转存/解析失败，这句原因直接给用户看 */
    @SerialName("failed_reason") val failedReason: String = "",
    /** 内容信息。`pending` 时为空，`ready` 时必填 */
    val media: MediaDto? = null,
)

/** 一条链接解析回来的全部内容信息 */
@Serializable
data class MediaDto(
    val title: String = "",
    val author: String = "",
    val site: String = "",
    /** 对这个条目的一句话说明 */
    val summary: String = "",
    val segments: List<SegmentDto> = emptyList(),
    /** 非空表示解析失败，[segments] 此时必为空 */
    @SerialName("failed_reason") val failedReason: String = "",
    @SerialName("resolved_at") val resolvedAt: Long = 0L,
)

/**
 * 解析出来的单个素材。[url] 对视频段是可播地址（可能是切片后的 m3u8），对图片段就是图片本身，
 * 所以 App 不区分「播放地址字段」和「图片地址字段」。
 */
@Serializable
data class SegmentDto(
    val index: Int = 0,
    /** image | video */
    val kind: String = "video",
    val title: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val url: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val quality: String = "",
)

/**
 * 一次变更。`DELETE` 按 url 删（收藏链接就是身份），`UPSERT` 整行覆盖用户的四列。
 *
 * 没有 `changed_fields` 也没有时钟戳：这个后台不做字段级合并 —— 一个人在两台设备上同时改同一条的
 * 不同字段，后传的那台整行赢，这是明写的代价，不是漏实现。
 */
@Serializable
data class OpDto(
    /** UPSERT | DELETE */
    val type: String,
    /** DELETE 的目标链接 */
    val url: String? = null,
    val favorite: FavoriteDto? = null,
)

@Serializable
data class SyncRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("sent_at") val sentAt: Long,
    val ops: List<OpDto>,
)

/**
 * 上传回执。[favorites] 是这次涉及的那条的最新状态 —— 新建或改备注之后它大概率是 `pending`，
 * 内容要等后台转存完才就绪，那之后靠对账把它取回来。
 */
@Serializable
data class SyncResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val applied: Int = 0,
    val favorites: List<FavoriteDto> = emptyList(),
    @SerialName("next_wait_ms") val nextWaitMs: Long? = null,
    val error: String = "",
)

/** 分页拉取收藏：`next_cursor` 空或 `has_more` 假都表示已经到底 */
@Serializable
data class PullResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val favorites: List<FavoriteDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    /** 后台当前的收藏总数；0 表示后台没上报，App 退回只显示本地条数 */
    val total: Int = 0,
    val error: String = "",
)

/** 批量取内容：对账算出差异之后，只为这些 url 取回完整内容 */
@Serializable
data class LookupRequest(val urls: List<String>)

@Serializable
data class LookupResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val favorites: List<FavoriteDto> = emptyList(),
    val error: String = "",
)

/** 手动重建内容：后台丢弃旧结果重排一次转存，立刻返回（这条回到 `pending`） */
@Serializable
data class ResolveRequest(val urls: List<String>)

@Serializable
data class ResolveResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val favorites: List<FavoriteDto> = emptyList(),
    @SerialName("unknown_urls") val unknownUrls: List<String> = emptyList(),
    @SerialName("next_wait_ms") val nextWaitMs: Long? = null,
    val error: String = "",
)

sealed interface SyncOutcome {
    /** 后台明确收了这一条（`applied == 1`），回执里带着它的最新状态 */
    data class Success(
        val applied: Int,
        val serverTime: Long,
        val favorites: List<FavoriteDto> = emptyList(),
        val nextWaitMs: Long? = null,
    ) : SyncOutcome

    /** 网络/5xx/429：可重试 */
    data class Retryable(val reason: String, val retryAfterMs: Long? = null) : SyncOutcome

    /** 4xx：重试无意义，标 FAILED 并保留在队列里等用户处理 */
    data class Rejected(val reason: String) : SyncOutcome
}

/** 手动重建内容的结果：后台已经把这条重新排进转存队列（回执里是 pending 状态） */
data class ResolveOutcome(
    val favorites: List<FavoriteDto> = emptyList(),
    /** 后台没有这些链接 */
    val unknownUrls: List<String> = emptyList(),
    /** 非空表示这次没拿到结果，原因在此 */
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null
}

fun Favorite.toDto(tagNames: List<String>) = FavoriteDto(
    url = url,
    note = note,
    tags = tagNames,
    sourceApp = sourceApp,
    favoriteAt = favoriteAt,
)

fun Favorite.toUpsertOp(tagNames: List<String>) = OpDto(
    type = OpType.UPSERT.name,
    favorite = toDto(tagNames),
)

fun String.toDeleteOp() = OpDto(type = OpType.DELETE.name, url = this)
