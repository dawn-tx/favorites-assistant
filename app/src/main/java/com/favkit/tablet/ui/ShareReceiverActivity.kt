package com.favkit.tablet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.favkit.tablet.floating.FloatingAssistantService
import com.favkit.tablet.util.VideoLinkDetector

/**
 * 第三方视频 App「分享 → 收藏助手」的接收器。
 *
 * 无界面：解析链接后直接唤起悬浮收藏面板，finish() 后焦点回到原来的第三方 App，
 * 因此从分享入口进来也不会打断用户当前浏览。未授予悬浮窗权限时才退回到主 App 内收藏。
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            .orEmpty()
        val url = VideoLinkDetector.extract(sharedText)

        when {
            url == null -> {
                Toast.makeText(this, "分享内容里没找到视频链接", Toast.LENGTH_SHORT).show()
            }

            FloatingAssistantService.isAvailable(this) -> {
                FloatingAssistantService.openPanel(this, url)
            }

            else -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_SHARE_URL, url),
                )
            }
        }
        finish()
    }
}
