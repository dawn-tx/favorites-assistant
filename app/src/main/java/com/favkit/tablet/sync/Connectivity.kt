package com.favkit.tablet.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * 回调式网络监听 + 主动重算。
 *
 * 只靠回调会漏事件：熄屏/Doze 期间网络切换或回调被延迟，系统恢复后不一定再补一条 onAvailable，
 * 界面就会一直停在「离线」。这里把状态做成进程级 StateFlow，并在亮屏、解锁与回前台时
 * 主动 [refresh] 一次；仍然没有轮询。
 */
class Connectivity(context: Context) {

    private val appContext = context.applicationContext
    private val manager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    init {
        manager?.let { cm ->
            runCatching { cm.registerDefaultNetworkCallback(callback) }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    screenReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
    }

    /** 现算一份，给 SyncWorker 这类只需要瞬时判断的地方 */
    val isOnline: Boolean get() = isOnlineNow()

    /** 系统回调、亮屏、回前台都可以调用；只在值真的变化时更新 */
    fun refresh() {
        _online.value = isOnlineNow()
    }

    private fun isOnlineNow(): Boolean {
        val cm = manager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    fun onlineState(): Flow<Boolean> = online

    /** 只在"断网 -> 有网"跳变上发 true；初始状态不算一次跳变 */
    fun onlineTransitions(): Flow<Boolean> = online
        .drop(1)
        .distinctUntilChanged()
        .filter { it }
}