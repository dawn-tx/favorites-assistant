package com.favkit.tablet.data

/**
 * 后台下发的素材地址是相对路径（`/media/<key>.mp4`），相对的是设置里的接口地址
 * （含 `/api/v1`）。统一在仓库层拼成绝对地址，播放器、Coil 与缓存键拿到的都是完整 URL。
 *
 * 换后台地址后，收藏流会随设置重新解析，旧地址自然失效；已经是绝对地址的原样放行。
 */
object MediaUrls {

    fun resolve(endpoint: String, raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return url
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return url
        val base = endpoint.trim().trimEnd('/')
        if (!base.startsWith("http://", ignoreCase = true) && !base.startsWith("https://", ignoreCase = true)) return url
        return if (url.startsWith("/")) base + url else "$base/$url"
    }
}