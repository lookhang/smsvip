package com.example.smsalert.service

import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.example.smsalert.Constants
import com.example.smsalert.R
import com.example.smsalert.data.RulesRepository

/**
 * 备份触发通道：前台服务 + 监听短信收件箱的 ContentObserver。
 *
 * 为什么需要它？
 * 1) 部分 MIUI/HyperOS 版本在“省电/后台限制”下可能延迟或丢弃广播；
 * 2) 应用被短暂回收后，常驻前台服务能更快恢复；
 * 3) 与广播接收器互为冗余，提高命中率。
 *
 * 同时它也是“后台监控常驻”的可视化入口（低优先级通知）。
 */
class MonitorService : Service() {
    private var observer: SmsObserver? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        val cr = contentResolver
        observer = SmsObserver(Handler(mainLooper))
        cr.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, Constants.CHANNEL_MONITOR_ID)
        .setContentTitle("短信监控运行中")
        .setContentText("正在实时监听关键短信")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private inner class SmsObserver(handler: Handler) : ContentObserver(handler) {
        private var lastId = -1L

        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    if (id == lastId) return
                    lastId = id
                    val sender = it.getString(1) ?: ""
                    val body = it.getString(2) ?: ""
                    if (RulesRepository(this@MonitorService).matches(sender, body) != null) {
                        AlertService.trigger(this@MonitorService, sender, body)
                    }
                }
            }
        }
    }
}
