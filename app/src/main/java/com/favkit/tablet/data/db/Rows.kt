package com.favkit.tablet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.SyncBadge

@Entity(
    tableName = "favorites",
    indices = [Index("favoriteAt"), Index("badge")],
)
data class FavoriteRow(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bucket: String,
    val badge: String = SyncBadge.LOCAL_ONLY.name,
    val locallyCached: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 软删除时间：非空表示已标记删除，等待后台补传后物理清除 */
    val deletedAt: Long? = null,
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
 * 本地待同步队列：断网 / 重试耗尽后落在这里，联网后由 WorkManager 自动补传，用户无需手动操作。
 */
@Entity(tableName = "pending_ops", indices = [Index("nextRetryAt"), Index("favoriteId")])
data class PendingOpRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val favoriteId: String,
    val opType: String = OpType.UPSERT.name,
    /** 已序列化好的请求体（明文 JSON，出站前再整体信封加密） */
    val payload: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val nextRetryAt: Long = 0L,
    val lastError: String = "",
)
