package com.favkit.tablet

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.favkit.tablet.core.Notifications
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.di.GraphHolder

class App : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 冷启动只建容器：数据库、网络、播放缓存全部 lazy，主线程零磁盘 IO
        graph = AppGraph.of(this)
        GraphHolder.install(graph)
        Notifications.ensureChannels(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()
}

fun Application.graph(): AppGraph = (this as? App)?.graph ?: AppGraph.of(this)
