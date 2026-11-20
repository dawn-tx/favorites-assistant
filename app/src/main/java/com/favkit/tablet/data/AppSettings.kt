package com.favkit.tablet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.favkit.tablet.BuildConfig
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
    /** 长视频切片规则：size 按大小、duration 按时长；只影响新收藏与手动「重新准备」 */
    val sliceRuleMode: String,
    val sliceRuleBytes: Long,
    val sliceRuleSeconds: Long,
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
        val SLICE_MODE = stringPreferencesKey("slice_rule_mode")
        val SLICE_BYTES = longPreferencesKey("slice_rule_bytes")
        val SLICE_SECONDS = longPreferencesKey("slice_rule_seconds")
        val DEVICE_ID = stringPreferencesKey("device_id")
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
            sliceRuleMode = p[Keys.SLICE_MODE] ?: SLICE_MODE_SIZE,
            sliceRuleBytes = p[Keys.SLICE_BYTES] ?: DEFAULT_SLICE_BYTES,
            sliceRuleSeconds = p[Keys.SLICE_SECONDS] ?: DEFAULT_SLICE_SECONDS,
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

    suspend fun setSliceRuleMode(value: String) =
        update { this[Keys.SLICE_MODE] = if (value == SLICE_MODE_DURATION) SLICE_MODE_DURATION else SLICE_MODE_SIZE }

    /** 切片服务接受的单段上限：64KB – 1GB */
    suspend fun setSliceRuleBytes(value: Long) =
        update { this[Keys.SLICE_BYTES] = value.coerceIn(MIN_SLICE_BYTES, MAX_SLICE_BYTES) }

    suspend fun setSliceRuleSeconds(value: Long) =
        update { this[Keys.SLICE_SECONDS] = value.coerceIn(1L, MAX_SLICE_SECONDS) }

    companion object {
        const val DEFAULT_UPLOAD_INTERVAL_MS = 3_000L
        const val MAX_UPLOAD_INTERVAL_MS = 600_000L

        const val SLICE_MODE_SIZE = "size"
        const val SLICE_MODE_DURATION = "duration"
        /** 与切片服务的默认规则一致：每片 2MB、关键帧对齐 */
        const val DEFAULT_SLICE_BYTES = 2_000_000L
        const val DEFAULT_SLICE_SECONDS = 300L
        const val MIN_SLICE_BYTES = 64L * 1024L
        const val MAX_SLICE_BYTES = 1024L * 1024L * 1024L
        const val MAX_SLICE_SECONDS = 3600L
    }
}
