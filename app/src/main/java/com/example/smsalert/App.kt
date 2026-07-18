package com.example.smsalert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 应用入口：在启动时创建通知渠道。
 * - 强提醒渠道：IMPORTANCE_MAX + setBypassDnd(true)，是绕过“勿扰/静音”的关键。
 * - 监控渠道：低优先级常驻通知，仅用于维持前台服务。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val alertChannel = NotificationChannel(
                Constants.CHANNEL_ALERT_ID,
                "强提醒通知",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "关键短信强提醒（最高优先级，可绕过勿扰）"
                setBypassDnd(true)
                // 声音由 AlertService 用 MediaPlayer 自行播放，渠道本身不重复响铃
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val monitorChannel = NotificationChannel(
                Constants.CHANNEL_MONITOR_ID,
                "后台监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "短信监控服务常驻通知"
                setBypassDnd(false)
            }

            nm.createNotificationChannels(listOf(alertChannel, monitorChannel))
        }
    }
}
