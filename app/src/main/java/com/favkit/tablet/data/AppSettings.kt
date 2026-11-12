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
    val serverPublicKeyPem: String,
    val assistantEnabled: Boolean,
    val allowFreeRotation: Boolean,
    val pasteSuggestion: Boolean,
    val defaultPlaybackSpeed: Float,
    val cacheLimitMb: Long,
    val deviceId: String,
)

class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private object Keys {
        val ENDPOINT = stringPreferencesKey("sync_endpoint")
        val TOKEN = stringPreferencesKey("sync_token")
        val SERVER_KEY = stringPreferencesKey("server_public_key")
        val ASSISTANT = booleanPreferencesKey("assistant_enabled")
        val FREE_ROTATION = booleanPreferencesKey("allow_free_rotation")
        val PASTE_SUGGEST = booleanPreferencesKey("paste_suggestion")
        val SPEED = floatPreferencesKey("default_speed")
        val CACHE_MB = longPreferencesKey("cache_limit_mb")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val snapshot: Flow<SettingsSnapshot> = appContext.settingsStore.data.map { p ->
        SettingsSnapshot(
            syncEndpoint = p[Keys.ENDPOINT]?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SYNC_ENDPOINT,
            syncToken = p[Keys.TOKEN] ?: "",
            serverPublicKeyPem = p[Keys.SERVER_KEY]?.takeIf { it.isNotBlank() }
                ?: BuildConfig.SERVER_RSA_PUBLIC_KEY_PEM,
            assistantEnabled = p[Keys.ASSISTANT] ?: true,
            allowFreeRotation = p[Keys.FREE_ROTATION] ?: false,
            pasteSuggestion = p[Keys.PASTE_SUGGEST] ?: true,
            defaultPlaybackSpeed = p[Keys.SPEED] ?: 1.0f,
            cacheLimitMb = p[Keys.CACHE_MB] ?: 512L,
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
    suspend fun setServerPublicKey(value: String) = update { this[Keys.SERVER_KEY] = value.trim() }
    suspend fun setAssistantEnabled(value: Boolean) = update { this[Keys.ASSISTANT] = value }
    suspend fun setAllowFreeRotation(value: Boolean) = update { this[Keys.FREE_ROTATION] = value }
    suspend fun setPasteSuggestion(value: Boolean) = update { this[Keys.PASTE_SUGGEST] = value }
    suspend fun setDefaultPlaybackSpeed(value: Float) = update { this[Keys.SPEED] = value }
    suspend fun setCacheLimitMb(value: Long) = update { this[Keys.CACHE_MB] = value }
}
