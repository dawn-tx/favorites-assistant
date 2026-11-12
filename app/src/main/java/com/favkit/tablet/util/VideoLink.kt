package com.favkit.tablet.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 视频链接识别 + 剪贴板桥接。
 *
 * 系统约束（必须在 UI 上如实告知用户）：Android 10 起只有"当前获得输入焦点的应用"
 * 才能读取剪贴板。悬浮面板自身窗口拿到焦点时可以读，但第三方 App 处于前台时读不到，
 * 因此本项目不申请无障碍权限，走两条确定可用的路径：
 *   1) 面板内的「粘贴」按钮（此刻焦点在本 App 窗口，读取合法）；
 *   2) 第三方 App 的「分享 → 收藏助手」（SEND intent，链接直接送进来）。
 */
object VideoLinkDetector {

    private val URL_REGEX = Regex("""https?://[^\s"'<>，。；、）】]{6,300}""")

    private val HOST_LABELS = linkedMapOf(
        "bilibili.com" to "哔哩哔哩",
        "b23.tv" to "哔哩哔哩",
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "douyin.com" to "抖音",
        "ixigua.com" to "西瓜视频",
        "v.qq.com" to "腾讯视频",
        "youku.com" to "优酷",
        "mgtv.com" to "芒果TV",
        "sohu.com" to "搜狐视频",
        "ximalaya.com" to "喜马拉雅",
        "xiaohongshu.com" to "小红书",
        "acfun.cn" to "AcFun",
        "miguvideo.com" to "咪咕视频",
        "vimeo.com" to "Vimeo",
        "dailymotion.com" to "Dailymotion",
    )

    fun extract(text: String): String? =
        URL_REGEX.find(text.trim())?.value?.trimEnd('.', ',', ';', ')', ']', '】')

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()

    /** 来源标识：优先已知视频站名，否则用域名，最后兜底"浏览器/其它" */
    fun sourceLabel(url: String): String {
        val host = hostOf(url) ?: return "其它来源"
        HOST_LABELS.forEach { (key, label) -> if (host == key || host.endsWith(".$key")) return label }
        return host
    }

    fun guessTitle(url: String): String {
        val host = hostOf(url) ?: return url.take(40)
        val tail = runCatching { java.net.URI(url).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull()
        return if (tail.isNullOrBlank()) sourceLabel(url) else "${sourceLabel(url)} · ${tail.take(28)}"
    }
}

data class ClipReading(val text: String?, val restricted: Boolean)

class ClipboardBridge(context: Context) {

    private val appContext = context.applicationContext
    private val clipboardManager: ClipboardManager? =
        appContext.getSystemService(ClipboardManager::class.java)

    /**
     * 读取剪贴板。返回 restricted=true 表示被系统策略拦截（本 App 不是焦点窗口），
     * 此时 UI 必须降级为「手动粘贴」而不是假装读到了内容。
     */
    fun read(): ClipReading {
        val manager = clipboardManager ?: return ClipReading(null, true)
        val clip: ClipData = runCatching { manager.primaryClip }.getOrNull()
            ?: return ClipReading(null, true)
        if (clip.itemCount == 0) return ClipReading(null, true)
        val value = runCatching { clip.getItemAt(0).coerceToText(appContext).toString() }
            .getOrNull()?.trim()
        return if (value.isNullOrEmpty()) ClipReading(null, true) else ClipReading(value, false)
    }
}
