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
)

/** 后台解析出的视频元数据缓存；离线时靠它继续展示列表 */
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
 * [nextRetryAt] 语义是「这条什么时候才允许被取走」：新入队为 0（立刻可跑），
 * 上一条传完后由上传器按「服务端返回的等待时间 ?? 设置里的上传间隔」写入。
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
