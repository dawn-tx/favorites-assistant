package com.favkit.tablet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.SyncBadge

/**
 * 用户产生的数据。标题、封面、时长、播放地址不在这里 —— 那些属于 [VideoInfoRow]，由后台给出。
 */
@Entity(
    tableName = "favorites",
    indices = [Index("favoriteAt"), Index("badge")],
)
data class FavoriteRow(
    @PrimaryKey val id: String,
    val url: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,
    val lastPositionMs: Long = 0L,
    /** 协议透传字段：App 已不做归档分类，一律写 INBOX；留着是为了不改表、不影响老数据 */
    val bucket: String,
    val badge: String = SyncBadge.LOCAL_ONLY.name,
    val locallyCached: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 软删除时间：非空表示已标记删除，等待后台补传后物理清除 */
    val deletedAt: Long? = null,
    /** 「段号:毫秒」逗号分隔的段级进度；见 SegmentProgressCodec。纯本地，不上传 */
    val segmentProgress: String? = null,
    /** 上次停在第几段 */
    val lastSegmentIndex: Int? = null,
)

/**
 * 后台解析内容的元数据缓存；离线时靠它继续展示列表与说明文案。
 *
 * [summary] 与 [mediaJson] 声明成可空而不是带默认值的非空，迁移就能写成不带 DEFAULT 的
 * `ALTER TABLE ADD COLUMN` —— Room 生成的建表语句里也没有 DEFAULT，两边逐字对得上。
 */
@Entity(
    tableName = "video_info",
    foreignKeys = [
        ForeignKey(
            entity = FavoriteRow::class,
            parentColumns = ["id"],
            childColumns = ["favoriteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoInfoRow(
    @PrimaryKey val favoriteId: String,
    val title: String = "",
    val coverUrl: String = "",
    val author: String = "",
    val site: String = "",
    val durationMs: Long = 0L,
    val playUrl: String = "",
    val quality: String = "",
    val resolvedAt: Long = 0L,
    val status: String = com.favkit.tablet.data.ResolveStatus.PENDING.name,
    val error: String = "",
    val isMockSource: Boolean = false,
    /** 后台给的说明文案，卡片与遮罩层的内容主体 */
    val summary: String? = null,
    /** 素材清单（List<MediaDto> 序列化后的 JSON）；为空表示后台只给了单个视频 */
    val mediaJson: String? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val isPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "favorite_tags",
    primaryKeys = ["favoriteId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(
            entity = FavoriteRow::class,
            parentColumns = ["id"],
            childColumns = ["favoriteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagRow::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteTagRow(val favoriteId: String, val tagId: Long)

/**
 * 待上传队列：串行执行，一次只跑一条。
 * [nextRetryAt] 只管**这一条自己的**重试退避：新入队为 0（立刻可跑），失败后按退避时间推后。
 * 「两条之间隔多久」不再写到这里，由 [com.favkit.tablet.net.SyncGate] 在出站口统一把控。
 */
@Entity(tableName = "pending_ops", indices = [Index("nextRetryAt"), Index("favoriteId")])
data class PendingOpRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val favoriteId: String,
    val opType: String = OpType.UPSERT.name,
    /** 已序列化好的单个 op（明文 JSON，直接作为一个 ops 数组元素发出去） */
    val payload: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val nextRetryAt: Long = 0L,
    val lastError: String = "",
)
