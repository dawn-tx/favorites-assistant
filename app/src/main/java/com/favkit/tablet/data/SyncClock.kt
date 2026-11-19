package com.favkit.tablet.data

import com.favkit.tablet.net.Hlc

/**
 * 本机 HLC 状态的唯一读写口。
 *
 * 状态落在 DataStore 而不是 Room：`AppDatabase` 挂的是 `fallbackToDestructiveMigration()`，
 * 升级清库会把表里的时钟一起清掉 —— seq 归零加上界丢失，这台设备之后的每次写入都会被后台
 * 判成旧的，改动静默输给别的设备。DataStore 不在这套迁移的管辖范围内，清库碰不到它。
 *
 * 推进都在 [AppSettings.mutateHlc] 的独占事务里完成，两次本地写不会拿到同一个读数。
 */
class SyncClock(private val settings: AppSettings) {

    /** 一次本地写：取一个全新的 HLC 读数，并把本机状态往前推一步 */
    suspend fun nextWrite(): Hlc = settings.mutateHlc { wallMs, seq ->
        val local = System.currentTimeMillis()
        // 墙钟走到了下界之前 → 开新的 wall_ms 并把 seq 归零；还停在同一个 wall_ms 上 → 只加 seq
        if (local > wallMs) Hlc(local, 0) else Hlc(wallMs, seq + 1)
    }

    /**
     * 把后台响应里的 `server_time` 吸收成新的时间下界。
     *
     * 只在它比已知下界更新时抬升，抬升的同时把 seq 归零（新下界之后本机的第一次写又是另一段因果链）。
     * 后台没下发这个字段时读数是 0，永远抬不动下界，因此不需要调用方各自判空。
     */
    suspend fun absorbServerTime(serverTimeMs: Long) {
        if (serverTimeMs <= 0L) return
        settings.mutateHlc { wallMs, seq ->
            if (serverTimeMs > wallMs) Hlc(serverTimeMs, 0) else Hlc(wallMs, seq)
        }
    }
}
