package com.favkit.tablet.floating

import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.favkit.tablet.App
import com.favkit.tablet.R
import com.favkit.tablet.core.Notifications
import com.favkit.tablet.core.TabletMetrics
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.util.ClipReading
import com.favkit.tablet.util.ClipboardBridge
import com.favkit.tablet.util.VideoLinkDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全局悬浮收藏助手。
 *
 * 两个互相独立的悬浮窗：
 * - 悬浮球：`FLAG_NOT_FOCUSABLE`，只占球体大小，可拖拽，松手吸附最近的一侧边缘；
 *   贴边后自动半隐藏，只露出 `stripPx()` 窄边，点击窄边即恢复，不遮挡第三方 App 主内容。
 * - 收藏面板：可获得焦点（否则手写笔/输入法无法输入），非模态，面板之外的触摸仍会派发给
 *   下层 App，因此收藏全程不离开当前界面；`FLAG_WATCH_OUTSIDE_TOUCH` 用于点外部即收起。
 *
 * 耗电：无常驻定时器、无轮询；剪贴板只在面板打开那一刻按用户偏好读一次；补传由 WorkManager
 * 的网络约束与回调触发。球上的同步角标读的是两条 Room Flow 加一个联网回调，都只在真实写入或
 * 网络跳变上发射；设置里关掉角标就把这三条订阅一起取消。
 */
class FloatingAssistantService : Service() {

    private lateinit var windows: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph: AppGraph? get() = (application as? App)?.graph

    private var halfHidden by mutableStateOf(false)
    private var badge by mutableStateOf(BallBadge.None)
    private var badgeJob: Job? = null

    private var ballView: View? = null
    private var ballHost: OverlayHost? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelHost: OverlayHost? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var snap: ValueAnimator? = null
    private val clipboardBridge: ClipboardBridge by lazy { ClipboardBridge(this) }

    /** 拖拽用屏幕绝对坐标：窗口跟着手指走时，视图内坐标会反向抵消掉窗口位移 */
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false
    private var wasHalfHidden = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    /** 「打开面板时自动读取剪贴板」偏好，关掉后只能手动粘贴或用系统分享入口 */
    private var autoReadClipboard = true

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = relayoutForCurrentMetrics()
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        // 服务可能在熄屏后重启：先按当前网络重算一次在线状态
        graph?.connectivity?.refresh()
        goForeground()
        addBall()
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 只做一次 Flow 订阅，设置变更才发射，不轮询
        scope.launch {
            graph?.settings?.snapshot?.collect { values ->
                autoReadClipboard = values.pasteSuggestion
                applyBadgePreference(values.ballStatusBadge)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN_PANEL -> openPanel(intent.getStringExtra(EXTRA_URL))
            ACTION_STOP -> {
                // 通知栏「停止」是用户的明确意图，连同偏好一起关掉，避免下次进 App 又被拉起
                scope.launch {
                    graph?.settings?.setAssistantEnabled(false)
                    stopSelf()
                }
            }

            else -> if (ballView == null) addBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        snap?.cancel()
        runCatching { unregisterReceiver(configReceiver) }
        closePanel()
        removeBall()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val notification = Notifications.assistantNotification(this, getString(R.string.notification_hint))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.NOTIFICATION_ASSISTANT, notification, type)
    }

    /**
     * 角标订阅跟着偏好起停：开着才 combine，关掉就取消任务并把数字清掉。
     *
     * 三条 Flow 都是事件驱动的（Room 写触发、联网回调），没有定时器也没有轮询。
     * 代价是订阅会把数据库拉起来 —— 用户不想在球上看队列时，这份开销就不用付。
     */
    private fun applyBadgePreference(enabled: Boolean) {
        val appGraph = graph
        if (!enabled || appGraph == null) {
            badgeJob?.cancel()
            badgeJob = null
            badge = BallBadge.None
            return
        }
        if (badgeJob != null) return
        badgeJob = scope.launch {
            combine(
                appGraph.sync.queuedCount,
                appGraph.sync.failedCount,
                appGraph.connectivity.onlineState(),
            ) { queued, failed, online -> BallBadge.of(queued, failed, online) }
                .collect { badge = it }
        }
    }

    // region 悬浮球
    private fun addBall() {
        if (ballView != null) return
        if (!Settings.canDrawOverlays(this)) {
            shortToast(getString(R.string.overlay_missing))
            stopSelf()
            return
        }
        val size = TabletMetrics.ballSizePx(this)
        val host = OverlayHost(this)
        val content = host.createView {
            FloatingBall(
                halfHidden = halfHidden,
                badge = badge,
            )
        }
        // Compose 子视图不参与命中：所有触摸由这层拦截，服务层用 raw 坐标改窗口位置
        val view = TouchInterceptLayout(this) { event -> handleBallTouch(event) }
        view.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, defaultBallX(size))
            y = prefs.getInt(KEY_Y, screenHeight() / 2 - size / 2)
        }
        clampBall(params)
        if (!runCatching { windows.addView(view, params) }.isSuccess) {
            shortToast(getString(R.string.overlay_missing))
            stopSelf()
            return
        }
        ballView = view
        ballHost = host
        ballParams = params
        host.resume()
        halfHidden = isHalfHidden(params.x, size)
    }

    /**
     * 拖拽/点击都走这里：用 `rawX/rawY` 的屏幕位移改窗口坐标。
     *
     * 原来的 Compose 视图内坐标不能用：窗口本身在跟着手指移动，下一次事件的局部坐标会反向
     * 变化，位移互相抵消，球看起来拖不动，也就永远到不了贴边半隐那一档。
     */
    private fun handleBallTouch(event: MotionEvent): Boolean {
        val params = ballParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                wasHalfHidden = halfHidden
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragging && abs(dx) + abs(dy) > touchSlop) dragging = true
                if (dragging) {
                    if (wasHalfHidden) halfHidden = false
                    params.x = (dragStartX + dx).toInt()
                    params.y = (dragStartY + dy).toInt()
                    clampBall(params)
                    runCatching { windows.updateViewLayout(ballView, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> snapToEdge()
                    wasHalfHidden -> expandBall()
                    else -> openPanel(null)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapToEdge()
                return true
            }
        }
        return true
    }

    private fun clampBall(params: WindowManager.LayoutParams) {
        val size = TabletMetrics.ballSizePx(this)
        val strip = stripPx()
        params.y = params.y.coerceIn(0, (screenHeight() - size).coerceAtLeast(0))
        params.x = params.x.coerceIn(-(size - strip), screenWidth() - strip)
    }

    /**
     * 松手吸附：球心离边不到一颗球那么远就半隐藏成一条窄边，否则完全贴边停靠。
     *
     * 判据用「球心到边的距离」而不是原来的 `x < size * 0.3f`：那颗球本来就贴着边拖，
     * 老阈值要求它整颗都探过边缘才算靠近，实际永远够不着，于是「靠边自动半隐」从来没发生过。
     */
    private fun snapToEdge() {
        val params = ballParams ?: return
        val size = TabletMetrics.ballSizePx(this)
        val margin = edgeMarginPx()
        val strip = stripPx()
        val center = params.x + size / 2
        val toLeft = center < screenWidth() / 2
        val nearEdge = if (toLeft) center < size else screenWidth() - center < size
        val targetX = when {
            toLeft && nearEdge -> -(size - strip)
            toLeft -> margin
            !toLeft && nearEdge -> screenWidth() - strip
            else -> screenWidth() - size - margin
        }
        animateBallTo(targetX)
        halfHidden = isHalfHidden(targetX, size)
    }

    private fun expandBall() {
        val params = ballParams ?: return
        val size = TabletMetrics.ballSizePx(this)
        val margin = edgeMarginPx()
        val targetX = if (params.x + size / 2 < screenWidth() / 2) {
            margin
        } else {
            screenWidth() - size - margin
        }
        animateBallTo(targetX)
        halfHidden = false
    }

    private fun animateBallTo(targetX: Int) {
        val params = ballParams ?: return
        snap?.cancel()
        snap = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = SNAP_MS
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windows.updateViewLayout(ballView, params) }
            }
            start()
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, targetX)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    private fun removeBall() {
        ballHost?.destroy()
        runCatching { ballView?.let { windows.removeView(it) } }
        ballView = null
        ballHost = null
        ballParams = null
    }
    // endregion

    // region 收藏面板
    private fun openPanel(prefillUrl: String?) {
        if (panelView != null) {
            if (!prefillUrl.isNullOrBlank()) applyLink(prefillUrl)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            shortToast(getString(R.string.overlay_missing))
            return
        }
        // 此刻面板窗口即将获得焦点，系统允许本 App 读剪贴板；读不到或被关掉就退回手动粘贴
        val reading = if (prefillUrl == null && autoReadClipboard) {
            clipboardBridge.read()
        } else {
            ClipReading(null, restricted = false)
        }
        val link = prefillUrl ?: VideoLinkDetector.extract(reading.text.orEmpty())
        FloatingDraftStore.update {
            copy(
                url = link.orEmpty(),
                sourceLabel = link?.let(VideoLinkDetector::sourceLabel).orEmpty(),
                clipboardRestricted = reading.restricted,
                status = PanelStatus.IDLE,
                message = "",
                showNewTagInput = false,
                newTagName = "",
            )
        }
        FloatingDraftStore.setVisible(true)

        val host = OverlayHost(this)
        val view = host.createView {
            FloatingPanelScreen(onSave = ::saveAndDismiss, onClose = ::closePanel)
        }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) closePanel()
            false
        }
        val params = WindowManager.LayoutParams(
            TabletMetrics.panelWidthPx(this),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        if (!runCatching { windows.addView(view, params) }.isSuccess) {
            shortToast(getString(R.string.overlay_missing))
            return
        }
        panelView = view
        panelHost = host
        panelParams = params
        host.resume()
        scope.launch { graph?.tags?.ensurePresets() }
    }

    private fun applyLink(url: String) {
        FloatingDraftStore.update {
            copy(url = url, sourceLabel = VideoLinkDetector.sourceLabel(url), clipboardRestricted = false)
        }
    }

    private fun closePanel() {
        FloatingDraftStore.setVisible(false)
        panelHost?.destroy()
        runCatching { panelView?.let { windows.removeView(it) } }
        panelView = null
        panelHost = null
        panelParams = null
    }

    private fun saveAndDismiss() {
        val draft = FloatingDraftStore.draft.value
        val useCase = graph?.let(::FloatingSaveUseCase)
        if (useCase == null) {
            FloatingDraftStore.update { copy(status = PanelStatus.FAILED, message = getString(R.string.graph_missing)) }
            return
        }
        FloatingDraftStore.update { copy(status = PanelStatus.SAVING) }
        scope.launch {
            when (val outcome = useCase.save(draft.url, draft.note, draft.selectedTagIds, draft.newTagName)) {
                is SaveOutcome.Queued -> {
                    shortToast(outcome.reason)
                    FloatingDraftStore.clearAfterSave()
                    closePanel()
                }

                is SaveOutcome.Duplicate -> {
                    // 面板不关、草稿不清：用户可能只是顺手重发了同一条，输入的东西不该消失
                    shortToast(outcome.reason)
                    FloatingDraftStore.update { copy(status = PanelStatus.NOTICE, message = outcome.reason) }
                }

                is SaveOutcome.Rejected -> {
                    FloatingDraftStore.update { copy(status = PanelStatus.FAILED, message = outcome.reason) }
                }
            }
        }
    }

    // endregion

    /** 旋转 / 分屏改变尺寸后重算窗口大小；草稿保存在进程级单例，输入与选中标签不丢 */
    private fun relayoutForCurrentMetrics() {
        ballParams?.let { params ->
            val size = TabletMetrics.ballSizePx(this)
            params.width = size
            params.height = size
            clampBall(params)
            halfHidden = isHalfHidden(params.x, size)
            runCatching { windows.updateViewLayout(ballView, params) }
        }
        panelParams?.let { params ->
            params.width = TabletMetrics.panelWidthPx(this)
            runCatching { windows.updateViewLayout(panelView, params) }
        }
    }

    private fun shortToast(text: String) {
        // LENGTH_SHORT 约 2 秒，对应"轻量提示 + 面板自动收起"
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun defaultBallX(size: Int): Int = screenWidth() - size - edgeMarginPx()

    /** 屏幕上只剩一条窄边、球心已经探出去，就算半隐藏 */
    private fun isHalfHidden(x: Int, size: Int): Boolean =
        x < -(size / 2) || x > screenWidth() - size / 2

    /** 半隐藏时露出的窄边：12dp。9dp 的时候连角标数字的一半都容不下，手指也难再点一次 */
    private fun stripPx(): Int = TabletMetrics.dpToPx(this, 12f)

    /**
     * 完整停靠时距屏幕边只留 4dp。
     *
     * 需求里「边缘控件距边 20mm」说的是 App 自己界面里的按钮，悬浮球反过来是要贴边的系统级浮窗，
     * 按 20mm 停就永远够不到半隐藏那一档了；4dp 只是别跟屏幕圆角和侧边手势带叠死。
     */
    private fun edgeMarginPx(): Int = TabletMetrics.dpToPx(this, 4f)

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    companion object {
        const val ACTION_OPEN_PANEL = "com.favkit.tablet.action.OPEN_PANEL"
        const val ACTION_OPEN_SETTINGS = "com.favkit.tablet.action.OPEN_SETTINGS"
        const val ACTION_STOP = "com.favkit.tablet.action.STOP"
        const val EXTRA_URL = "extra_url"

        private const val PREFS = "floating_ball"
        private const val KEY_X = "ball_x"
        private const val KEY_Y = "ball_y"
        private const val SNAP_MS = 180L

        fun isAvailable(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!isAvailable(context)) return
            ContextCompat.startForegroundService(context, Intent(context, FloatingAssistantService::class.java))
        }

        fun openPanel(context: Context, url: String?) {
            val intent = Intent(context, FloatingAssistantService::class.java).apply {
                action = ACTION_OPEN_PANEL
                putExtra(EXTRA_URL, url)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingAssistantService::class.java))
        }
    }
}

/**
 * 悬浮球窗口的触摸全部由这一层接住：Compose 子视图不参与命中，
 * 服务层直接用 `rawX/rawY` 改窗口坐标，拖动不会被窗口位移反向抵消。
 */
private class TouchInterceptLayout(
    context: Context,
    private val onTouch: (MotionEvent) -> Boolean,
) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
    override fun onTouchEvent(event: MotionEvent): Boolean = onTouch(event)
}
