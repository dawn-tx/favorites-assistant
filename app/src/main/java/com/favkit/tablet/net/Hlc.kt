package com.favkit.tablet.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 混合逻辑时钟的一个读数：`(wall_ms, seq)`。
 *
 * `wall_ms` 取「本地墙钟与已知下界的较大值」，下界由每次收到的后台 `server_time` 抬高。
 * 所以这台设备的表上写着几点可以被时区或错钟随意平移，**因果顺序却不会反转**：本机只要收到过
 * 一次后台响应，之后的写入就不会早于后台受理那次响应的时刻 —— 别的设备先落地的改动不会被子钟盖掉。
 * `seq` 补上墙钟给不了的那一半 —— 同一写者连改两次落在同一毫秒时，谁新谁旧仍然确定。
 *
 * 持久化与推进见 [com.favkit.tablet.data.SyncClock]。
 */
data class Hlc(val wallMs: Long, val seq: Int) : Comparable<Hlc> {

    /** 同一台设备内部的先后：墙钟不够看，再比 seq */
    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, { it.wallMs }, { it.seq })

    companion object {
        /** DataStore 里还没有任何记录时的起点（第一次本地写就会把它推到一个真实读数上） */
        val ZERO = Hlc(0L, 0)

        /**
         * 两个写者之间的决胜序：`wall_ms` → `seq` → 写者标识。
         *
         * 最后一档必须有，否则两台设备给出同一个读数时胜负由到达顺序决定 —— 那正是多设备
         * 场景里最不可复现的一种翻车。写者标识取字典序，规则固定，结果可写进测试。
         */
        fun compare(left: Hlc, leftWriter: String, right: Hlc, rightWriter: String): Int {
            val byTime = left.compareTo(right)
            return if (byTime != 0) byTime else leftWriter.compareTo(rightWriter)
        }
    }
}

/**
 * [Hlc] 上线用的形状：两个整数 —— 墙钟毫秒 + seq。
 *
 * 单独一个类型而不是给 [Hlc] 标 `@Serializable`：协议键名是 `wall_ms`，本地字段名是 `wallMs`，
 * 交给插件按本地字段名生成会写成驼峰、后台读不到；逐字段补 `@SerialName` 虽然对得上，但
 * [com.favkit.tablet.data.SyncClock] 推进的那个类型就从此挂在协议形状上，改字段名等于动时钟。
 */
@Serializable
data class HlcDto(@SerialName("wall_ms") val wallMs: Long, val seq: Int)

fun Hlc.toDto(): HlcDto = HlcDto(wallMs, seq)
