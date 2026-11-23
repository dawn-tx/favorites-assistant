package com.favkit.tablet.net

import com.favkit.tablet.data.AppSettings

/**
 * 控制面统一入口：WebSocket 在线就走 WS，失败/离线自动回退到现有 HTTP 接口。
 *
 * 上传（`POST /sync`）不在这里 —— 阶段 1 仍由 [SyncApi.push] 走 HTTP，
 * 读请求（清单、lookup）与「重新准备」才切到 WS。
 */
class ControlClient(
    private val api: SyncApi,
    private val settings: AppSettings,
    val realtime: RealtimeSocket,
) {

    suspend fun pullManifest(cursor: String?, limit: Int): PullOutcome {
        if (realtime.connected) {
            val viaWs = realtime.list("min", cursor, limit)
            if (viaWs is PullOutcome.Loaded) return viaWs
        }
        return api.pullManifest(cursor, limit)
    }

    suspend fun pull(cursor: String?, limit: Int): PullOutcome {
        if (realtime.connected) {
            val viaWs = realtime.list(null, cursor, limit)
            if (viaWs is PullOutcome.Loaded) return viaWs
        }
        return api.pull(cursor, limit)
    }

    suspend fun lookup(urls: List<String>): LookupOutcome {
        if (urls.isEmpty()) return LookupOutcome.Loaded(emptyList())
        if (realtime.connected) {
            val viaWs = realtime.lookup(urls)
            if (viaWs is LookupOutcome.Loaded) return viaWs
        }
        return api.lookup(urls)
    }

    suspend fun peek(url: String): LookupOutcome = lookup(listOf(url))

    suspend fun resolve(urls: List<String>): ResolveOutcome {
        if (urls.isEmpty()) return ResolveOutcome()
        if (realtime.connected) {
            val snapshot = settings.current()
            val rule = SliceRuleDto(mode = "size", bytes = snapshot.sliceRuleBytes)
            val viaWs = realtime.resolve(urls, rule)
            if (viaWs.error == null) return viaWs
        }
        return api.resolve(urls)
    }
}