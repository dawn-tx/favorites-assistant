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
    val defaultPlaybackSpeed: Float,
    val cacheLimitMb: Long,
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
        val SPEED = floatPreferencesKey("default_speed")
        val CACHE_MB = longPreferencesKey("cache_limit_mb")
        val UPLOAD_INTERVAL = longPreferencesKey("upload_interval_ms")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val snapshot: Flow<SettingsSnapshot> = appContext.settingsStore.data.map { p ->
        SettingsSnapshot(
            syncEndpoint = p[Keys.ENDPOINT]?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SYNC_ENDPOINT,
            syncToken = p[Keys.TOKEN] ?: "",
            assistantEnabled = p[Keys.ASSISTANT] ?: true,
            pasteSuggestion = p[Keys.PASTE_SUGGEST] ?: true,
            defaultPlaybackSpeed = p[Keys.SPEED] ?: 1.0f,
            cacheLimitMb = p[Keys.CACHE_MB] ?: 512L,
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

    suspend fun setSyncEndpoint(value: String) = update { this[Keys.ENDPOINT] = value.trim() }
    suspend fun setSyncToken(value: String) = update { this[Keys.TOKEN] = value.trim() }
    suspend fun setAssistantEnabled(value: Boolean) = update { this[Keys.ASSISTANT] = value }
    suspend fun setPasteSuggestion(value: Boolean) = update { this[Keys.PASTE_SUGGEST] = value }
    suspend fun setDefaultPlaybackSpeed(value: Float) = update { this[Keys.SPEED] = value }
    suspend fun setCacheLimitMb(value: Long) = update { this[Keys.CACHE_MB] = value }

    /** 0 表示不限速：上一条一结束就立刻传下一条 */
    suspend fun setUploadIntervalMs(value: Long) =
        update { this[Keys.UPLOAD_INTERVAL] = value.coerceIn(0L, MAX_UPLOAD_INTERVAL_MS) }

    companion object {
        const val DEFAULT_UPLOAD_INTERVAL_MS = 3_000L
        const val MAX_UPLOAD_INTERVAL_MS = 600_000L
    }
}
