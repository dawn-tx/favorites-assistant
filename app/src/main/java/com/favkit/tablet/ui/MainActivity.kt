package com.favkit.tablet.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.favkit.tablet.App
import com.favkit.tablet.R
import com.favkit.tablet.core.Notifications
import com.favkit.tablet.floating.FloatingAssistantService
import com.favkit.tablet.ui.main.MainScreen
import com.favkit.tablet.ui.main.MainViewModel
import com.favkit.tablet.ui.theme.FavoritesTheme
import kotlinx.coroutines.launch

/**
 * 平板主入口。
 *
 * 不锁方向：布局完全由窗口宽度自适应（见 MainScreen），横屏、竖屏、分屏、折叠屏展开都能用。
 * 旋转、深浅色、分屏尺寸变化由 manifest 的 configChanges 承接，Activity 不重建，
 * 因此列表滚动位置、选中标签、正在输入的备注全部原生保留。
 */
class MainActivity : ComponentActivity() {

    private val appGraph by lazy { (application as App).graph }
    private val mainModel: MainViewModel by lazy {
        ViewModelProvider(this, MainViewModel.factory(appGraph))[MainViewModel::class.java]
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            enableAssistantNow()
            Toast.makeText(this, "悬浮收藏助手已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.overlay_missing), Toast.LENGTH_LONG).show()
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) enableAssistantNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FavoritesTheme {
                val uiState by mainModel.state.collectAsStateWithLifecycle()
                MainScreen(
                    viewModel = mainModel,
                    assistantAvailable = Settings.canDrawOverlays(this),
                    assistantEnabledByUser = uiState.settings?.assistantEnabled ?: true,
                    onEnableAssistant = { requestAssistant() },
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 熄屏/Doze 可能漏掉一次联网恢复回调：回前台先按当前网络重算一次，避免一直显示离线
        appGraph.connectivity.refresh()
        // 控制面 WS 只在前台保持：推送负责 pending → ready 与别的设备的变更
        appGraph.sync.attachRealtime()
        // 用户开启过助手且已授权，就把它拉起来；服务本身 START_STICKY，常驻不轮询
        lifecycleScope.launch {
            val snapshot = appGraph.settings.current()
            if (snapshot.assistantEnabled && Settings.canDrawOverlays(this@MainActivity)) {
                FloatingAssistantService.start(this@MainActivity)
            }
        }
        // 对账挂在这条 Runnable 上而不是直接 launch：lifecycleScope 是 Main.immediate，
        // launch 会就地跑到第一个挂起点，冷启动时那等于在首帧之前发出请求（还会顺手把 ViewModel 建出来）。
        // decorView 未 attach 时 post 进的是 attach 队列，真跑起来是第一次 traversal 之后的下一条消息。
        window.decorView.post { mainModel.onEnterForeground() }
    }

    override fun onPause() {
        // 后台不保持 WS：Android 会挂起连接，留着重连反而耗电；回前台再连并走一次兜底
        appGraph.sync.detachRealtime()
        super.onPause()
    }

    private fun enableAssistantNow() {
        lifecycleScope.launch { appGraph.settings.setAssistantEnabled(true) }
        FloatingAssistantService.start(this)
    }

    private fun requestAssistant() {
        if (!Notifications.canPostNotifications(this)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        enableAssistantNow()
    }

    /** 分享进来的链接在主 App 内用同一个悬浮面板 UI 完成收藏，不需要悬浮窗权限 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == FloatingAssistantService.ACTION_OPEN_SETTINGS) {
            mainModel.showSettings(true)
            return
        }
        intent.getStringExtra(EXTRA_SHARE_URL)
            ?.takeIf { it.isNotBlank() }
            ?.let { mainModel.startQuickCollect(it) }
    }

    companion object {
        const val EXTRA_SHARE_URL = "com.favkit.tablet.extra.SHARE_URL"
    }
}
