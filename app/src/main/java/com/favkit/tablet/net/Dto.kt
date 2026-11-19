package com.favkit.tablet.net

import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.OpType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后台协议：全部明文 JSON，改任何一边都要同步改 mock-server/server.mjs。
 *
 * 收藏四条数据接口（`POST /sync`、`GET /favorites`、`GET /favorites/changes`、`POST /resolve`），
 * 共同点是**响应里的每条收藏都自带解析结果**：后台保存与解析在同一次调用里完成，所以 App 侧
 * 不存在「先拿到链接、再等内容」的中间态，也没有专门取解析结果的轮询接口。
 * `/changes` 是这四条里唯一发**增量**的：一条一笔写入，带 id 不带内容的墓碑也算一种。
 */
@Serializable
data class FavoriteDto(
    val id: String,
    val url: String,
    val note: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("source_app") val sourceApp: String = "",
    @SerialName("favorite_at") val favoriteAt: Long = 0L,
    /**
     * 后台对这一行发的版本号，[com.favkit.tablet.data.FavoritesRepository.importRemote] 存下来当
     * 下次编辑的 `base_rev`。
     *
     * 只有响应里才有：可空加 `explicitNulls = false` 才真的让上传载荷不带这个键 —— 出站那份 JSON
     * 是 `encodeDefaults = true` 的，非空默认值会照样写出去。null 和 0 同样按「这个后台还没发号」处理。
     */
    val rev: Long? = null,
    /**
     * 这次写入的 HLC 读数，后台拿它做字段级的最后写入判定；不带 = 后台只认到达顺序的老语义。
     * 墙钟单值不够用：同一毫秒内的两次编辑并列，谁新谁旧就得靠 seq 一起发。
     *
     * [rev] 的镜像 —— rev 只在响应里有，这一位只在上传里有（组包见 [toUpsertOp]，本机从不读响应里的它）。
     * 挂在 favorite 上而不是 op 顶层是 §5 表格的登记口径：后台只在这一层取它，发在顶层等于没发，
     * 服务端会退回 server_time 兜底戳 —— 那比任何真实 HLC 都新，一笔旧改动会反过来赢。
     */
    @SerialName("write_hlc") val writeHlc: HlcDto? = null,
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
    /**
     * 这次编辑所基于的行版本，取自本机存的 [Favorite.rev]。
     *
     * 不带 = 后台不做版本冲突判定，直接按 HLC 合并。本机 rev 还是 0（从没从这个后台拿到过版本号）时
     * 也**不发**：0 不是「第一版」，把它当版本发出去，后台会把每一条都判成基于错的版本。
     */
    @SerialName("base_rev") val baseRev: Long? = null,
    /**
     * 这次真正改了哪些用户字段。上传送的是整条 favorite，里面 `url`、`source_app`、
     * `favorite_at` 大多是从后台抄回来的，不声明的话服务端会把它们全记成「这次写的」，
     * 另一台设备刚改的那个字段就被这条携带的旧值吞掉。
     *
     * 缺省（不带这个键）= 全字段覆盖 = 老语义，只为老客户端和手搓 curl 保留；
     * 本 App 的每一次写都如实填写，取值见 [ChangedFields]。
     */
    @SerialName("changed_fields") val changedFields: List<String>? = null,
)

/**
 * [OpDto.changedFields] 的取值口径，字段名用后台 favorites 的列名。
 *
 * 标签整体算一个字段：A 删一个、B 加另一个，B 的集合赢、A 的删除丢失 —— 这是「按字段合并」
 * 这个选择的直接代价，不是这里的实现漏了什么。
 */
object ChangedFields {
    /** 新建收藏：这五项确实都是这一次写进去的 */
    val CREATE = listOf("url", "note", "tags", "source_app", "favorite_at")

    /** 只改备注，其余字段带着旧值也不能算这次写的 */
    val NOTE = listOf("note")

    /** 只换标签（加标签、删标签、标签改名后重新排队都算这一类） */
    val TAGS = listOf("tags")
}

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
    /**
     * 后台按 op 下标给的局部拒绝原因：一条脏数据不该再拖垮整批，也不该被计进 `applied`。
     * 老后台不下发时是空表，判据退回 `applied + unknown_ops`。
     */
    @SerialName("op_errors") val opErrors: List<OpErrorDto> = emptyList(),
    /**
     * id 没命中、但 url 命中本账号另一条收藏：后台把这次写入并到那条上，本机要跟着改主键。
     * 没有归并发生过就是空表。
     */
    @SerialName("merged_into") val mergedInto: List<MergedIntoDto> = emptyList(),
    val favorites: List<FavoriteDto> = emptyList(),
    @SerialName("next_wait_ms") val nextWaitMs: Long? = null,
    val error: String = "",
)

/**
 * `op_errors` 的一项：某个下标上的 op 被后台拒绝。
 *
 * 定位以下标为准：同一批里同一条收藏出现两次时 id 是重复的，只有下标分得清拒的是哪一次。
 */
@Serializable
data class OpErrorDto(
    @SerialName("op_index") val opIndex: Int,
    /** 这条 op 指向的收藏 id；后台认不出时为空串 */
    val id: String = "",
    /** 给人看的中文拒绝原因 */
    val reason: String = "",
)

/** `merged_into` 的一项：这条 op 落在 [fromId] 上，后台把它并进了已有的 [toId] */
@Serializable
data class MergedIntoDto(
    @SerialName("op_index") val opIndex: Int,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
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

/**
 * `GET /favorites/changes` 的响应：`since_rev` 之后账号里发生过的每一笔写入，含墓碑。
 *
 * 增量只有 [items] 一个数组：被改过的已有收藏和只有 id 的墓碑混在同一列里按 rev 排，
 * 因为「先删后被同一个 url 复活」在同一批里是真会发生的，拆成两份就把这个先后弄丢了。
 * 契约里没有「还有没有下一批」那个键，翻页判据是 `items` 为空即对完（见 [com.favkit.tablet.net.ChangesOutcome.Loaded]）。
 * 字段名照接口契约原样写、一个都不改 —— 这条接口是 App 与后台要在同一个版本窗口里一起上的，
 * 名字各说各话对账就地失效。每一项都给了缺省值：`ignoreUnknownKeys` 只挡得住后台多返字段，
 * 挡不住后台只回一半字段的那种老实现。
 */
@Serializable
data class ChangesResponse(
    val ok: Boolean = true,
    @SerialName("server_time") val serverTime: Long = 0L,
    /** 按 [ChangeItemDto.rev] 升序，一个写者一笔 */
    val items: List<ChangeItemDto> = emptyList(),
    /** 下一批的 `since_rev`；不下发时退回本机这次的起点，免得从头再对一整段 */
    @SerialName("next_rev") val nextRev: Long = 0L,
    /** `since_rev` 已经被后台 GC 掉，中间丢了一段：这批不完整，调用方该丢弃游标改走全量翻页 */
    val truncated: Boolean = false,
    val error: String = "",
)

/** 对账增量里的一笔写入 */
@Serializable
data class ChangeItemDto(
    /** 这一行被写成现在这样时的版本号 */
    val rev: Long = 0L,
    @SerialName("favorite_id") val favoriteId: String = "",
    /**
     * 真表示这一笔是删除，后台这时不带 [favorite]，本机能拿到的只有 id。
     *
     * App 侧对它的定义是**物理删除本地行，连段进度一起删**：删除这件事归账号，
     * 看到哪一段归这台设备，远端既然说这条收藏没了，本机就没有续看的地方留着的理由。
     */
    val deleted: Boolean = false,
    /** 活条目的整行快照，含后台的解析结果 */
    val favorite: FavoriteDto? = null,
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
        /**
         * 后台按 op 下标点名的局部拒绝（`op_errors`），老后台不下发时是空表。
         *
         * 判据与落库都要认它：[com.favkit.tablet.sync.SyncWorker] 把被点名的这一条标 FAILED，
         * 并把后台给的 `reason` 原样写进 `lastError` —— 那句中文是对着这次的数据说的，
         * App 侧再翻译一遍就对不上了。
         */
        val opErrors: List<OpErrorDto> = emptyList(),
        val favorites: List<FavoriteDto> = emptyList(),
        /**
         * 后台把这次送出的收藏并到了账号里 url 相同的另一条上（`merged_into`）。
         *
         * 交给 [com.favkit.tablet.data.FavoritesRepository.importRemote] 改本地主键 —— 它必须在
         * `favorites` 那批回填**之前**生效，因为回执里那几条用的已经是 `to_id`。
         */
        val mergedInto: List<MergedIntoDto> = emptyList(),
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
    // rev 不回传：它是后台发号的结果，本机这次编辑基于哪一版已经写在 base_rev 里了
)

/**
 * 组一条 UPSERT。[changedFields] 必填：调用点最清楚这次动的是哪个字段，
 * 给了默认值就总有人图省事不填，那正是字段级合并判反的那条路。
 *
 * 类型可以是 null 但**没有默认值**：null 只留给本机说不出用户改的是哪一列的那种补投
 * （见 [com.favkit.tablet.data.FavoritesRepository.unqueuedOps]），如实少声明比猜一个字段诚实；出站编码是
 * `explicitNulls = false` 的，null 就是不写这个键，正好落进 [OpDto.changedFields] 说的全字段覆盖那一档。
 *
 * `base_rev`（op 层）与 `write_hlc`（favorite 层）都只取自这一行自己存着的值，不现取本机的钟：载荷是那次
 * 写入的快照，决胜要的时刻就得是同一次写入的时刻。两者一旦来自不同的瞬间（改完甲、排队前又改了乙），
 * 后台收到的就是「乙的时刻 + 甲的内容」这个从没发生过的组合。
 *
 * 行上没有戳时（[Favorite.updatedAt] 还是 0：这行从后台来、本机没改过）两个字段都不发 ——
 * 不发就是退回后台按到达顺序合并的老语义，跟 `base_rev` 在 rev==0 时不发同一个道理：宁可少说，不可说错。
 */
fun Favorite.toUpsertOp(tagNames: List<String>, changedFields: List<String>?) = OpDto(
    type = OpType.UPSERT.name,
    favorite = toDto(tagNames).copy(writeHlc = if (updatedAt > 0L) Hlc(updatedAt, hlcSeq).toDto() else null),
    changedFields = changedFields,
    baseRev = rev.takeIf { it > 0L },
)

fun String.toDeleteOp() = OpDto(type = OpType.DELETE.name, favoriteId = this)

fun OpDto.targetId(): String? = favorite?.id ?: favoriteId
