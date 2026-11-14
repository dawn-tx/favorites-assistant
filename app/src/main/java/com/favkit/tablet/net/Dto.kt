package com.favkit.tablet.net

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.data.VideoInfo
import com.favkit.tablet.data.MediaCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后台协议：全部明文 JSON。收藏只上传用户产生的数据，内容信息由后台解析后回传。
 * 字段命名与 mock-server/server.mjs 一一对应，改任何一边都要同步改另一边。
 */
@Serializable
data class FavoriteDto(
    val id: String,
    val url: String,
    val note: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("source_app") val sourceApp: String = "",
    @SerialName("favorite_at") val favoriteAt: Long = 0L,
    /** App 已不做归档分类，但后台字段保留：一律上传 INBOX，服务端与老数据都不受影响 */
    val bucket: String = "INBOX",
    @SerialName("last_position_ms") val lastPositionMs: Long = 0L,
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

/**
 * 一条链接解析出来的单个素材。[url] 对视频段是可播地址，对图片段就是图片本身，
 * 所以 App 不再区分「播放地址字段」和「图片地址字段」。
 */
@Serializable
data class MediaDto(
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
data class VideoDto(
    @SerialName("favorite_id") val favoriteId: String = "",
    val title: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val author: String = "",
    val site: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0L,
    @SerialName("play_url") val playUrl: String = "",
    val quality: String = "",
    @SerialName("resolved_at") val resolvedAt: Long = 0L,
    /** pending | ready | failed */
    val status: String = "pending",
    val error: String = "",
    @SerialName("is_mock_source") val isMockSource: Boolean = false,
    /** 后台对这个条目解析结果的一句话说明 */
    val summary: String = "",
    /** 解析出来的素材清单；老后台不给时 App 用上面的单视频字段兜底 */
    val media: List<MediaDto> = emptyList(),
)

@Serializable
data class SyncResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val applied: Int = 0,
    @SerialName("unknown_ops") val unknownOps: List<String> = emptyList(),
    val favorites: List<FavoriteDto> = emptyList(),
    val videos: List<VideoDto> = emptyList(),
    /** 服务端建议的「下一条上传前的等待时间」；缺省时 App 用设置里的上传间隔 */
    @SerialName("next_wait_ms") val nextWaitMs: Long? = null,
    val error: String = "",
)

/** 分页拉取收藏：`next_cursor` 空或 `has_more` 假都表示已经到底 */
@Serializable
data class PullResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val favorites: List<FavoriteDto> = emptyList(),
    val videos: List<VideoDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    /** 后台当前的收藏总数（不是这一页的条数）；老后台不下发时为 0，App 退回只显示本地条数 */
    val total: Int = 0,
    val error: String = "",
)

@Serializable
data class VideosResponse(
    val ok: Boolean = true,
    val videos: List<VideoDto> = emptyList(),
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
        val videos: List<VideoDto> = emptyList(),
        val nextWaitMs: Long? = null,
    ) : SyncOutcome

    /** 网络/5xx/429：可重试 */
    data class Retryable(val reason: String, val retryAfterMs: Long? = null) : SyncOutcome

    /** 4xx：重试无意义，标 FAILED 并保留在队列里等用户处理 */
    data class Rejected(val reason: String) : SyncOutcome
}

fun Favorite.toDto(tagNames: List<String>) = FavoriteDto(
    id = id,
    url = url,
    note = note,
    tags = tagNames,
    sourceApp = sourceApp,
    favoriteAt = favoriteAt,
    lastPositionMs = lastPositionMs,
)

fun Favorite.toUpsertOp(tagNames: List<String>) = OpDto(
    type = OpType.UPSERT.name,
    favorite = toDto(tagNames),
)

fun String.toDeleteOp() = OpDto(type = OpType.DELETE.name, favoriteId = this)

fun OpDto.targetId(): String? = favorite?.id ?: favoriteId

fun VideoDto.toDomain(favoriteId: String): VideoInfo = VideoInfo(
    favoriteId = this.favoriteId.ifBlank { favoriteId },
    title = title,
    coverUrl = coverUrl,
    author = author,
    site = site,
    durationMs = durationMs,
    playUrl = playUrl,
    quality = quality,
    resolvedAt = resolvedAt,
    status = when (status.lowercase()) {
        "ready" -> ResolveStatus.READY
        "failed" -> ResolveStatus.FAILED
        else -> ResolveStatus.PENDING
    },
    error = error,
    isMockSource = isMockSource,
    summary = summary,
    media = MediaCodec.segments(media),
)
