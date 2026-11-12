package com.favkit.tablet.net

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.OpType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FavoriteDto(
    val id: String,
    val url: String,
    val title: String,
    val note: String,
    @SerialName("source_app") val sourceApp: String,
    @SerialName("favorite_at") val favoriteAt: Long,
    @SerialName("last_position_ms") val lastPositionMs: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val bucket: String,
    val tags: List<String> = emptyList(),
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

/** 出站信封：AES-256-GCM 密文 + 用服务端公钥包裹的会话密钥 */
@Serializable
data class Envelope(
    val alg: String,
    /** base64(wrapped session key)，设备密钥模式下为空 */
    val key: String = "",
    /** base64(12 字节 GCM IV) */
    val iv: String,
    /** base64(密文，含 GCM tag) */
    val data: String,
)

@Serializable
data class RemoteFavorite(
    val id: String = "",
    val url: String = "",
    val title: String = "",
    val note: String = "",
    @SerialName("source_app") val sourceApp: String = "",
    @SerialName("favorite_at") val favoriteAt: Long = 0L,
    @SerialName("last_position_ms") val lastPositionMs: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val bucket: String = "INBOX",
    val tags: List<String> = emptyList(),
)

@Serializable
data class SyncResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    val applied: Int = 0,
    @SerialName("unknown_ops") val unknownOps: List<String> = emptyList(),
    val favorites: List<RemoteFavorite> = emptyList(),
)

sealed interface SyncOutcome {
    data class Success(val applied: Int, val serverTime: Long, val backfill: List<RemoteFavorite>) : SyncOutcome
    /** 网络/5xx：可重试 */
    data class Retryable(val reason: String) : SyncOutcome
    /** 4xx：重试无意义，直接进本地待同步队列并提示 */
    data class Rejected(val reason: String) : SyncOutcome
}

fun Favorite.toDto(tagNames: List<String>) = FavoriteDto(
    id = id,
    url = url,
    title = title,
    note = note,
    sourceApp = sourceApp,
    favoriteAt = favoriteAt,
    lastPositionMs = lastPositionMs,
    durationMs = durationMs,
    bucket = bucket.name,
    tags = tagNames,
)

fun Favorite.toUpsertOp(tagNames: List<String>) = OpDto(
    type = OpType.UPSERT.name,
    favorite = toDto(tagNames),
)

fun String.toDeleteOp() = OpDto(type = OpType.DELETE.name, favoriteId = this)
