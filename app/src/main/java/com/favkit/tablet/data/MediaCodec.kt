package com.favkit.tablet.data

import com.favkit.tablet.net.SegmentDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 后台返回的素材清单在本地只存一行 JSON，不单独建表：一段一段建表要外键、要排序、
 * 要在每次覆盖解析结果时删了重插，而这条清单从头到尾只整体读、整体写。
 */
internal object MediaCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val serializer = ListSerializer(SegmentDto.serializer())

    /** 网络侧与本地侧共用同一套归一化：按后台给的顺序排，再重新编号成 0..n-1 */
    fun segments(dtos: List<SegmentDto>): List<MediaSegment> =
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
    fun encode(dtos: List<SegmentDto>): String? =
        if (dtos.isEmpty()) null else json.encodeToString(serializer, dtos)

    fun decode(raw: String?): List<MediaSegment> {
        if (raw.isNullOrBlank()) return emptyList()
        val dtos = runCatching { json.decodeFromString(serializer, raw) }.getOrNull() ?: return emptyList()
        return segments(dtos)
    }
}
