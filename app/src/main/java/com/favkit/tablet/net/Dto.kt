package com.favkit.tablet.net

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.OpType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后台协议：全部明文 JSON，改任何一边都要同步改 mock-server/server.mjs。
 *
 * 收藏只有三条数据接口（`POST /sync`、`GET /favorites`、`POST /resolve`），共同点是
 * **响应里的每条收藏都自带解析结果**：后台保存与解析在同一次调用里完成，所以 App 侧
 * 不存在「先拿到链接、再等内容」的中间态，也没有专门取解析结果的轮询接口。
 */
@Serializable
data class FavoriteDto(
    val id: String,
    val url: String,
    val note: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("source_app") val sourceApp: String = "",
    @SerialName("favorite_at") val favoriteAt: Long = 0L,
    /** 后台的解析结果。上传载荷里永远为 null，只有响应里才带 */
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
 * 解析出来的单个素材。[url] 对视频段是可播地址，对图片段就是图片本身，
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

@Serializable
data class OpDto(
    /** UPSERT | DELETE */
    val type: String,
    @SerialName("favorite_id") val favoriteId: String? = null,
    val favorite: FavoriteDto? = null,
)

@Serializable
data class SyncRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("sent_at") val sentAt: Long,
    val ops: List<OpDto>,
)

/** [favorites] 是这次上传被后台收下并解析完的收藏，直接回填本地，不用再问一次 */
@Serializable
data class SyncResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val applied: Int = 0,
    @SerialName("unknown_ops") val unknownOps: List<String> = emptyList(),
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
    /** 后台当前的收藏总数（不是这一页的条数）；老后台不下发时为 0，App 退回只显示本地条数 */
    val total: Int = 0,
    val error: String = "",
)

/** 手动重新解析的响应：结构与收藏分页一致，只是没有游标 */
@Serializable
data class ResolveResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val favorites: List<FavoriteDto> = emptyList(),
    @SerialName("unknown_ids") val unknownIds: List<String> = emptyList(),
    @SerialName("next_wait_ms") val nextWaitMs: Long? = null,
    val error: String = "",
)

sealed interface SyncOutcome {
    /**
     * 只有服务端明确确认收到的条数才算成功。
     * [nextWaitMs] 为空表示服务端没给节奏，由上传器退回本地配置。
     */
    data class Success(
        val applied: Int,
        val serverTime: Long,
        val unknownOps: List<String> = emptyList(),
        val favorites: List<FavoriteDto> = emptyList(),
        val nextWaitMs: Long? = null,
    ) : SyncOutcome

    /** 网络/5xx/429：可重试 */
    data class Retryable(val reason: String, val retryAfterMs: Long? = null) : SyncOutcome

    /** 4xx：重试无意义，标 FAILED 并保留在队列里等用户处理 */
    data class Rejected(val reason: String) : SyncOutcome
}

/** 手动重新解析的结果：[favorites] 自带新的解析内容信息 */
data class ResolveOutcome(
    val favorites: List<FavoriteDto> = emptyList(),
    /** 后台没有这些收藏 */
    val unknownIds: List<String> = emptyList(),
    /** 非空表示这次没拿到结果，原因在此 */
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null
}

fun Favorite.toDto(tagNames: List<String>) = FavoriteDto(
    id = id,
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

fun String.toDeleteOp() = OpDto(type = OpType.DELETE.name, favoriteId = this)

fun OpDto.targetId(): String? = favorite?.id ?: favoriteId
