package com.favkit.tablet.di

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Compose 侧取依赖容器。悬浮面板运行在 Service 窗口里、没有 ViewModel 通道，
 * 因此用这个进程级持有者，避免把 AppGraph 一路透传到每个 composable。
 */
object GraphHolder {
    @Volatile
    lateinit var graph: AppGraph
        private set

    fun install(appGraph: AppGraph) {
        graph = appGraph
    }

    fun resolve(context: Context): AppGraph {
        if (::graph.isInitialized) return graph
        val application = context.applicationContext as? Application
            ?: error("非 Application 上下文且 GraphHolder 未初始化")
        return AppGraph.of(application).also { install(it) }
    }
}

@Composable
fun rememberAppGraph(): AppGraph {
    val context = LocalContext.current
    return remember(context) { GraphHolder.resolve(context) }
}
