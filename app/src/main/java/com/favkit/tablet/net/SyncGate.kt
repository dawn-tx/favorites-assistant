package com.favkit.tablet.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 后台请求的节奏闸门：所有出站请求**串行**通过，两次之间至少隔一个间隔。
 *
 * 间隔原本只在上传成功那条分支上 delay 一下，失败分支和取解析结果、回填这些旁路
 * 根本不排队，用户设的节奏就成了摆设。挪到唯一出口后，成功、失败、重试、旁路一视同仁。
 *
 * [nextFreeAt] 是「下一条请求最早什么时候能发」。等待是在锁**外**睡的，不占着闸门睡觉，
 * 所以上传器能问一句还要等多久，再决定是自己等还是交回 WorkManager 稍后再来。
 */
class SyncGate {

    private val lock = Mutex()

    @Volatile
    private var nextFreeAt = 0L

    suspend fun <R> run(intervalMs: Long, block: suspend () -> R): R {
        val slotStart = lock.withLock {
            val start = maxOf(System.currentTimeMillis(), nextFreeAt)
            nextFreeAt = start + intervalMs.coerceAtLeast(0L)
            start
        }
        val wait = slotStart - System.currentTimeMillis()
        if (wait > 0L) delay(wait)
        return lock.withLock { block() }
    }

    /**
     * 后台在响应里给的等待时间，优先于本地间隔。
     *
     * 不加锁：调用方就是正握着闸门发请求的那一个，而 [Mutex] 不可重入；
     * 与并发预留时隙相撞最多也只是某一次间隔略偏，不会漏请求。
     */
    fun noteServerWait(ms: Long?) {
        if (ms == null || ms <= 0L) return
        val until = System.currentTimeMillis() + ms.coerceAtMost(MAX_WAIT_MS)
        if (until > nextFreeAt) nextFreeAt = until
    }

    /** 距离下一个可用时隙还有多久 */
    fun timeUntilNextSlot(): Long = (nextFreeAt - System.currentTimeMillis()).coerceAtLeast(0L)

    private companion object {
        /** 后台等待时间的上限：再长也不让一个时隙被无限期占住 */
        const val MAX_WAIT_MS = 10 * 60_000L
    }
}
