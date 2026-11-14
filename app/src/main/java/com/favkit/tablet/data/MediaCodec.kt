package com.favkit.tablet.data

import com.favkit.tablet.net.MediaDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 后台返回的素材清单在本地只存一行 JSON，不单独建表 —— 这样 v2→v3 迁移只剩四条
 * `ALTER TABLE ADD COLUMN`。手写 CREATE TABLE 要精确猜中 Room 生成的 DDL，而云端 CI
 * 只编译、从不跑迁移，猜错就是装机即崩；ADD COLUMN 猜错最多读出 null，退回单视频老路径。
 */
internal object MediaCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val serializer = ListSerializer(MediaDto.serializer())

    /** 网络侧与本地侧共用同一套归一化：按后台给的顺序排，再重新编号成 0..n-1 */
    fun segments(dtos: List<MediaDto>): List<MediaSegment> =
        dtos.sortedBy { it.index }.mapIndexed { position, dto ->
            MediaSegment(
                index = position,
                kind = if (dto.kind.equals("image", ignoreCase = true)) MediaKind.IMAGE else MediaKind.VIDEO,
                title = dto.title,
                coverUrl = dto.coverUrl,
                url = dto.url,
                durationMs = dto.durationMs,
                quality = dto.quality,
            )
        }

    /** 空清单写成 null 而不是 "[]"，省得一列永远占着字节 */
    fun encode(dtos: List<MediaDto>): String? =
        if (dtos.isEmpty()) null else json.encodeToString(serializer, dtos)

    fun decode(raw: String?): List<MediaSegment> {
        if (raw.isNullOrBlank()) return emptyList()
        val dtos = runCatching { json.decodeFromString(serializer, raw) }.getOrNull() ?: return emptyList()
        return segments(dtos)
    }
}

/**
 * 段级播放进度：「段号:毫秒」逗号分隔。
 *
 * 存 favorites 而不是 video_info —— 后者每次取解析结果都会被后台响应整体覆盖写回，
 * 本地进度放那儿等于每刷新一次就丢一次。
 */
internal object SegmentProgressCodec {

    fun encode(progress: Map<Int, Long>): String? =
        if (progress.isEmpty()) null else progress.entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(raw: String?): Map<Int, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',')
            .mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size != 2) return@mapNotNull null
                val index = parts[0].toIntOrNull() ?: return@mapNotNull null
                val position = parts[1].toLongOrNull() ?: return@mapNotNull null
                index to position.coerceAtLeast(0L)
            }
            .toMap()
    }
}
