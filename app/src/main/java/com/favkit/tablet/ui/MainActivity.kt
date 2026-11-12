package com.favkit.tablet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.favkit.tablet.App
import com.favkit.tablet.R
import com.favkit.tablet.core.Notifications
import com.favkit.tablet.floating.FloatingAssistantService
import com.favkit.tablet.ui.main.MainScreen
import com.favkit.tablet.ui.main.MainViewModel
import com.favkit.tablet.ui.theme.FavoritesTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 平板主入口：默认强制横屏。
 *
 * 旋转、深色模式、分屏尺寸变化都由 configChanges 承接，Activity 不重建，
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        observeRotationPreference()
        setContent {
            FavoritesTheme {
                val settings by appGraph.settings.snapshot.collectAsStateWithLifecycle()
                MainScreen(
                    viewModel = mainModel,
                    assistantAvailable = Settings.canDrawOverlays(this),
                    assistantEnabledByUser = settings.assistantEnabled,
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
        // 用户开启过助手且已授权，就把它拉起来；服务本身 START_STICKY，常驻不轮询
        lifecycleScope.launch {
            val snapshot = appGraph.settings.current()
            if (snapshot.assistantEnabled && Settings.canDrawOverlays(this@MainActivity)) {
                FloatingAssistantService.start(this@MainActivity)
            }
        }
    }

    private fun observeRotationPreference() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appGraph.settings.snapshot
                    .map { it.allowFreeRotation }
                    .distinctUntilChanged()
                    .collect { free ->
                        requestedOrientation = if (free) {
                            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
            }
        }
    }

    private fun enableAssistantNow() {
        lifecycleScope.launch { appGraph.settings.setAssistantEnabled(true) }
        FloatingAssistantService.start(this)
    }

    private fun requestAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifications.canPostNotifications(this)
        ) {
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
