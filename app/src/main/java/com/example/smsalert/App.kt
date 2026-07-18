package com.example.smsalert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.example.smsalert.util.AppLog

/**
 * 应用入口：在启动时创建通知渠道。
 * - 强提醒渠道：IMPORTANCE_MAX + setBypassDnd(true)，是绕过“勿扰/静音”的关键。
 * - 监控渠道：低优先级常驻通知，仅用于维持前台服务。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.setContext(this)
        installCrashHandler()
        AppLog.i("App", "onCreate start, sdk=${Build.VERSION.SDK_INT}, pkg=$packageName")
        try {
            createChannels()
            AppLog.i("App", "notification channels created")
        } catch (e: Throwable) {
            AppLog.e("App", "createChannels failed", e)
        }
    }

    /** 全局崩溃捕获：把任何未捕获异常堆栈写入本地日志，便于事后导出分析 */
    private fun installCrashHandler() {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.e("CRASH", "Uncaught exception on thread=${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            def?.uncaughtException(thread, throwable)
        }
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
                // 渠道自带声音：由系统通知栈播放，是穿透勿扰/静音最可靠的方式
                // 同时 AlertService 会用 MediaPlayer 循环播放报警音，形成持续强提醒
                val soundUri = android.net.Uri.parse("android.resource://$packageName/${R.raw.alarm}")
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, attrs)
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
