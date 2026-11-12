package com.favkit.tablet.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * 回调式网络监听：注册一次 ConnectivityManager 回调，不做任何轮询，
 * 因此常驻后台时不会因为网络探测产生额外唤醒，满足"不明显增加耗电"。
 */
class Connectivity(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val isOnline: Boolean
        get() {
            val network = manager?.activeNetwork ?: return false
            val capabilities = manager?.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        }

    /** 只在"断网 -> 有网"跳变上发 true，供待同步队列补传使用 */
    fun onlineTransitions(): Flow<Boolean> = callbackFlow {
        val cm = manager
        if (cm == null) {
            awaitClose { }
            return@callbackFlow
        }
        var online = isOnline
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = announce()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) announce()
            }

            override fun onLost(network: Network) {
                online = isOnline
            }

            private fun announce() {
                if (!online) {
                    online = true
                    trySend(true)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.conflate()

    /** 当前联网状态（离线角标用），同样只在真实变化上发射 */
    fun onlineState(): Flow<Boolean> = callbackFlow {
        val cm = manager
        if (cm == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        var current = isOnline
        trySend(current)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()

            override fun onLost(network: Network) = publish()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()

            private fun publish() {
                val now = isOnline
                if (now != current) {
                    current = now
                    trySend(now)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.conflate()
}
