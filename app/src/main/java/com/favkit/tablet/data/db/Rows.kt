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
 * [url] 是同步身份（后台那张表也用它当主键），所以本机对它建唯一索引：同一个链接只能有一条。
 * [id] 只是本地句柄，卡片、标签外键、播放队列都用它，永不出站。
 *
 * 内容信息是异步就绪的：后台要先解析、下载、把素材转存到存储服务器，所以 [mediaState] 会经历
 * `pending → ready`（或 `failed`）。这与最早那版「content_status 列 + 轮询接口」不是同一件事 ——
 * 那时以为要另开一条取结果的通道，现在结果走的是同一条列表/回执，只是可能晚一点到。
 */
@Entity(
    tableName = "favorites",
    indices = [Index("favoriteAt"), Index("badge"), Index(value = ["url"], unique = true)],
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

    // —— 后台回传的行状态 ——
    /**
     * 后台这一行最后一次变化的时刻（`updated_at`）。对账拿它判断「这条要不要重新取内容」：
     * 清单档只带它和状态、不带内容，省掉把 media 全搬一遍的流量。
     */
    val serverUpdatedAt: Long = 0L,
    /**
     * 内容就绪状态：`pending`（后台正在解析/下载/切片转存）、`ready`、`failed`。
     *
     * 空串表示这台设备还没从后台拿到过状态（刚保存、还没上传）。
     */
    val mediaState: String = "",

    // —— 以下是纯本地状态，不参与上传 ——
    val badge: String = SyncBadge.QUEUED.name,
    val locallyCached: Boolean = false,
    /** 软删除时间：非空表示已标记删除，等待后台补传后物理清除 */
    val deletedAt: Long? = null,
    /** 旧版本记录的段级播放进度；现在不写不读，列保留以避免动数据库版本 */
    val segmentProgress: String? = null,
    /** 旧版本记录的「上次停在第几段」；现在不写不读 */
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
