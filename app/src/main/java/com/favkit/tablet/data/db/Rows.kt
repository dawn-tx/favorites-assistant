package com.favkit.tablet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.favkit.tablet.data.OpType
import com.favkit.tablet.data.SyncBadge

/**
 * 一条收藏 = 一张表里的一行：用户产生的数据 ＋ 后台解析回来的内容信息。
 *
 * 后台保存与解析是同一次调用完成的，解析结果本来就来在收藏响应里，没有理由再拆一张表、
 * 再让界面去数「有没有解析完」。早期版本拆成 favorites/video_info 两张，是因为那时以为
 * 解析是异步的 —— 那个前提不成立，两侧就各多出一套状态、一套计数、一个轮询接口。
 */
@Entity(
    tableName = "favorites",
    indices = [Index("favoriteAt"), Index("badge")],
)
data class FavoriteRow(
    @PrimaryKey val id: String,

    // —— 以下四项是用户上传的内容，也是上传载荷里唯一的字段 ——
    val url: String,
    val note: String,
    val sourceApp: String,
    val favoriteAt: Long,

    // —— 以下是后台解析回来的内容信息，本地只当缓存，一律以后台为准 ——
    val title: String = "",
    val author: String = "",
    val site: String = "",
    /** 后台写的一句说明，卡片与遮罩层的内容主体 */
    val summary: String = "",
    /** 素材清单（List<SegmentDto> 序列化后的 JSON）；空表示后台没解析出可看可播的东西 */
    val segmentsJson: String? = null,
    /** 非空表示后台解析这条链接失败了，这里就是给用户看的原因 */
    val failedReason: String = "",
    val resolvedAt: Long = 0L,

    // —— 后台发的行版本号，本地只读（0 表示这条还没从后台拿到过 rev）——
    /** 账号内单调递增，由后台落库时 +1。本地编辑**不**动它，上传时作为 `base_rev` 的起点 */
    val rev: Long = 0L,

    // —— 以下是纯本地状态，不参与上传 ——
    val badge: String = SyncBadge.QUEUED.name,
    val locallyCached: Boolean = false,
    /**
     * 本行最后一次**本地**编辑的 HLC `wall_ms`（见 [com.favkit.tablet.data.SyncClock]）。
     *
     * 不是随手取的墙钟：字段级合并拿它当决胜键的一部分，写错一次这台设备就长期被判旧。
     * 0 表示这行从后台来、本机还没编辑过。
     */
    val updatedAt: Long = 0L,
    /**
     * [updatedAt] 那次写入的 HLC `seq`。墙钟只到毫秒，同一毫秒内的两次编辑靠它分先后，
     * 所以它得和墙钟记在同一行里：只留墙钟，这一行的写入时刻就永远是个残缺读数。
     */
    val hlcSeq: Int = 0,
    /** 软删除时间：非空表示已标记删除，等待后台补传后物理清除 */
    val deletedAt: Long? = null,
    /** 「段号:毫秒」逗号分隔的段级进度；见 SegmentProgressCodec */
    val segmentProgress: String? = null,
    /** 上次停在第几段 */
    val lastSegmentIndex: Int? = null,
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
