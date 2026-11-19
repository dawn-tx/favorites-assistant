package com.favkit.tablet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.favkit.tablet.BuildConfig
import com.favkit.tablet.net.Hlc
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "assistant_settings")

data class SettingsSnapshot(
    val syncEndpoint: String,
    val syncToken: String,
    val assistantEnabled: Boolean,
    val pasteSuggestion: Boolean,
    /** 悬浮球上的同步状态角标要不要画 */
    val ballStatusBadge: Boolean,
    val defaultPlaybackSpeed: Float,
    val cacheLimitMb: Long,
    /** 图片磁盘缓存（Coil）的容量上限，改了要重建 ImageLoader 才生效 */
    val imageCacheLimitMb: Long,
    /** 上一条传完后，后台没给等待时间时用的间隔 */
    val uploadIntervalMs: Long,
    val deviceId: String,
)

class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private object Keys {
        val ENDPOINT = stringPreferencesKey("sync_endpoint")
        val TOKEN = stringPreferencesKey("sync_token")
        val ASSISTANT = booleanPreferencesKey("assistant_enabled")
        val PASTE_SUGGEST = booleanPreferencesKey("paste_suggestion")
        val BALL_BADGE = booleanPreferencesKey("ball_status_badge")
        val SPEED = floatPreferencesKey("default_speed")
        val CACHE_MB = longPreferencesKey("cache_limit_mb")
        val IMAGE_CACHE_MB = longPreferencesKey("image_cache_limit_mb")
        val UPLOAD_INTERVAL = longPreferencesKey("upload_interval_ms")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val HLC_WALL_MS = longPreferencesKey("hlc_wall_ms")
        val HLC_SEQ = intPreferencesKey("hlc_seq")
        val SINCE_REV = longPreferencesKey("since_rev")
        /** [SINCE_REV] 属于哪个账号；没有它，换过 token 之后的旧游标就是一把看不见的钳子 */
        val SINCE_REV_ACCT = stringPreferencesKey("since_rev_acct")
    }

    val snapshot: Flow<SettingsSnapshot> = appContext.settingsStore.data.map { p ->
        SettingsSnapshot(
            syncEndpoint = p[Keys.ENDPOINT]?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SYNC_ENDPOINT,
            syncToken = p[Keys.TOKEN] ?: "",
            assistantEnabled = p[Keys.ASSISTANT] ?: true,
            pasteSuggestion = p[Keys.PASTE_SUGGEST] ?: true,
            ballStatusBadge = p[Keys.BALL_BADGE] ?: true,
            defaultPlaybackSpeed = p[Keys.SPEED] ?: 1.0f,
            cacheLimitMb = p[Keys.CACHE_MB] ?: 512L,
            imageCacheLimitMb = p[Keys.IMAGE_CACHE_MB] ?: 32L,
            uploadIntervalMs = p[Keys.UPLOAD_INTERVAL] ?: DEFAULT_UPLOAD_INTERVAL_MS,
            deviceId = p[Keys.DEVICE_ID] ?: "",
        )
    }

    suspend fun current(): SettingsSnapshot = snapshot.first()

    suspend fun update(block: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) {
        appContext.settingsStore.edit { it.block() }
    }

    suspend fun ensureDeviceId(): String {
        val existing = current().deviceId
        if (existing.isNotBlank()) return existing
        val fresh = "tab-" + UUID.randomUUID().toString().take(12)
        update { this[Keys.DEVICE_ID] = fresh }
        return fresh
    }

    /**
     * HLC 状态 `(wall_ms, seq)` 的原子读改写：整段跑在 DataStore 的独占事务里，
     * 同一毫秒内的两次本地写因此不会拿到同一个读数 —— 遮罩层里连点标签就是这种并发。
     *
     * 刻意不进 [SettingsSnapshot]：那份快照是界面订阅的，本机每编辑一次它就变一次，
     * 挂进去等于每次保存都白触发一轮重组。
     */
    suspend fun mutateHlc(step: (wallMs: Long, seq: Int) -> Hlc): Hlc {
        var stamped = Hlc.ZERO
        appContext.settingsStore.edit { p ->
            val next = step(p[Keys.HLC_WALL_MS] ?: 0L, p[Keys.HLC_SEQ] ?: 0)
            p[Keys.HLC_WALL_MS] = next.wallMs
            p[Keys.HLC_SEQ] = next.seq
            stamped = next
        }
        return stamped
    }

    /**
     * 对账游标 `since_rev` 的读数：游标戳（账号指纹 + 端点）不符时返回 0（= 这个后台还没对过）。
     *
     * 后台的 rev 是**账号内**发号，游标不绑账号就会在换过 token 之后把新账号里 rev ≤ 旧游标的写入
     * 全部藏掉 —— 那里面可能正好有唯一能物理删本机行的墓碑（[com.favkit.tablet.data.FavoritesRepository.applyChanges]
     * 只认 `items[].deleted`），错过一笔删除比多对一遍更糟。不打网络问「这是哪个账号」：
     * 本机已有的账号把手就是 sync_token，指纹取它的 sha1 前 16 位，与后台 `acct-<sha1[:16]>` 同一口径，
     * 也顺手避免把 bearer 令牌在本机多存一份明文。匿名（令牌为空）时指纹是空串，与「没存过」同档。
     *
     * 刻意不进 [SettingsSnapshot]：跟 [mutateHlc] 一样，它是本机每次对账都要改的内部状态，
     * 挂进界面订阅的那份快照等于每对一次账白触发一轮重组。
     */
    suspend fun sinceRev(): Long {
        val stamp = cursorStamp(current())
        return appContext.settingsStore.data
            .map { p -> if (p[Keys.SINCE_REV_ACCT] == stamp) p[Keys.SINCE_REV] ?: 0L else 0L }
            .first()
    }

    /** 写游标：与游标戳同一次 edit 落，分开写会留下「游标是 A 那台后台的、戳是 B 那台的」那半态 */
    suspend fun setSinceRev(rev: Long) {
        val stamp = cursorStamp(current())
        update {
            this[Keys.SINCE_REV] = rev
            this[Keys.SINCE_REV_ACCT] = stamp
        }
    }

    /**
     * 游标真正绑的是「哪台后台上的哪个账号」这一对：只绑 token 指纹的话，把同一串令牌指到另一台后台
     * 时旧游标照样成立，而那台后台的 rev 是从自己的计数器发的 —— 新后台里 rev ≤ 旧游标的写入会被整段藏掉，
     * 错过的那里面同样可能有能物理删本机行的墓碑。所以端点也进这一枚戳（存的就是比对用的整串，不单独存端点）。
     */
    private fun cursorStamp(config: SettingsSnapshot): String =
        "${accountFingerprint(config.syncToken)}|${config.syncEndpoint.trim()}"

    private fun accountFingerprint(token: String): String =
        if (token.isBlank()) ""
        else MessageDigest.getInstance("SHA-1").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    suspend fun setSyncEndpoint(value: String) = update { this[Keys.ENDPOINT] = value.trim() }
    suspend fun setSyncToken(value: String) = update { this[Keys.TOKEN] = value.trim() }
    suspend fun setAssistantEnabled(value: Boolean) = update { this[Keys.ASSISTANT] = value }
    suspend fun setPasteSuggestion(value: Boolean) = update { this[Keys.PASTE_SUGGEST] = value }
    suspend fun setBallStatusBadge(value: Boolean) = update { this[Keys.BALL_BADGE] = value }
    suspend fun setDefaultPlaybackSpeed(value: Float) = update { this[Keys.SPEED] = value }
    suspend fun setCacheLimitMb(value: Long) = update { this[Keys.CACHE_MB] = value }
    suspend fun setImageCacheLimitMb(value: Long) = update { this[Keys.IMAGE_CACHE_MB] = value }

    /** 0 表示不限速：上一条一结束就立刻传下一条 */
    suspend fun setUploadIntervalMs(value: Long) =
        update { this[Keys.UPLOAD_INTERVAL] = value.coerceIn(0L, MAX_UPLOAD_INTERVAL_MS) }

    companion object {
        const val DEFAULT_UPLOAD_INTERVAL_MS = 3_000L
        const val MAX_UPLOAD_INTERVAL_MS = 600_000L
    }
}
