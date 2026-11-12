package com.favkit.tablet.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.favkit.tablet.R
import com.favkit.tablet.floating.FloatingAssistantService
import com.favkit.tablet.ui.MainActivity

object Notifications {
    const val CHANNEL_ASSISTANT = "assistant_overlay"
    const val NOTIFICATION_ASSISTANT = 1001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ASSISTANT) != null) return
        // MINIMUM：静默，不震动不亮屏，符合"后台常驻不明显增加耗电"
        val channel = NotificationChannel(
            CHANNEL_ASSISTANT,
            context.getString(R.string.assistant_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.assistant_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 常驻通知只用于维持前台服务，两个动作分别通向设置页和停止助手 */
    fun assistantNotification(context: Context, text: String): Notification =
        Notification.Builder(context, CHANNEL_ASSISTANT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_running))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(activityIntent(context))
            .addAction(
                0,
                context.getString(R.string.notification_action_settings),
                activityIntent(context),
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_stop),
                serviceIntent(context, FloatingAssistantService.ACTION_STOP),
            )
            .build()

    private fun activityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = FloatingAssistantService.ACTION_OPEN_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, REQUEST_SETTINGS, intent, immutableFlags())
    }

    private fun serviceIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, FloatingAssistantService::class.java).apply { this.action = action }
        return PendingIntent.getService(context, REQUEST_STOP, intent, immutableFlags())
    }

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private const val REQUEST_SETTINGS = 2001
    private const val REQUEST_STOP = 2002
}
