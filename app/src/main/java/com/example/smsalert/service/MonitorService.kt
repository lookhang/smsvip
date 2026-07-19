package com.example.smsalert.service

import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.example.smsalert.Constants
import com.example.smsalert.R
import com.example.smsalert.util.AppLog

/**
 * 备份触发通道：前台服务 + 监听短信收件箱的 ContentObserver + 定时轮询兜底。
 *
 * 为什么需要这么多层？
 * 1) SmsReceiver（广播）在小米/HyperOS 下常被拦截，尤其 App 非默认短信 App、或被省电/后台限制时；
 * 2) ContentObserver 依赖本服务常驻，服务被杀即失效；
 * 3) 因此再增加“定时轮询收件箱”兜底：即使广播与 Observer 双双失效，也能在 15 秒内抓到新短信。
 *
 * 另外：每条被 App 看到的短信都会写入 SmsLog（无论是否命中规则），
 * 既能验证 App 是否真的读到了短信，也作为“提醒记录”供查询。
 */
class MonitorService : Service() {
    private var observer: SmsObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSeenId: Long = -1L

    companion object {
        private const val POLL_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Throwable) {
                AppLog.e("MonitorService", "start failed", e)
            }
        }

        /** 服务是否正在运行（用于主页在 onResume 时按需拉起） */
        fun isRunning(context: Context): Boolean {
            val nm = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (s in nm.getRunningServices(Int.MAX_VALUE)) {
                if (s.service.className == MonitorService::class.java.name) return true
            }
            return false
        }

        private const val PREFS_MONITOR = "monitor_prefs"
        private const val KEY_LAST_SEEN_ID = "last_seen_sms_id"
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("MonitorService", "onCreate")
        lastSeenId = getLastSeenId()
        startForegroundSafe()
        val cr = contentResolver
        observer = SmsObserver(Handler(mainLooper))
        cr.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer!!)
        // 兜底轮询：即使广播/Observer 失效，也能抓到新短信
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        AppLog.i("MonitorService", "started, polling every ${POLL_INTERVAL_MS}ms")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        return START_STICKY
    }

    /** 安全启动前台通知：失败只记录并停止自身，绝不直接拖垮 App 进程 */
    private fun startForegroundSafe() {
        try {
            startForeground(1, buildNotification())
        } catch (e: Throwable) {
            AppLog.e("MonitorService", "startForeground failed, stopSelf", e)
            try { stopSelf() } catch (_: Throwable) { }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 任务被划掉时尽量自愈重启（小米下仍可能受省电限制，但多一层保险）
        try {
            val i = Intent(this, MonitorService::class.java)
            startForegroundService(i)
        } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        handler.removeCallbacks(pollRunnable)
        // 销毁时也尝试自愈（除非系统强制停止）
        try {
            val i = Intent(this, MonitorService::class.java)
            startForegroundService(i)
        } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                scanInbox()
            } catch (_: Throwable) { }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, Constants.CHANNEL_MONITOR_ID)
        .setContentTitle("短信监控运行中")
        .setContentText("正在实时监听关键短信（广播+常驻+轮询三重保障）")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private inner class SmsObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            try { scanInbox() } catch (_: Throwable) { }
        }
    }

    /**
     * 扫描收件箱最新一条，处理所有 id > lastSeenId 的新短信（按 id 升序，避免乱序）。
     * 每条都写入 SmsLog；命中规则则触发强提醒。
     */
    private fun scanInbox() {
        val cr: ContentResolver = contentResolver
        val cursor = cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            null, null,
            "${Telephony.Sms._ID} ASC"
        )
        if (cursor == null) {
            AppLog.w("MonitorService", "scanInbox: query returned null (likely 无短信读取权限)")
            return
        }
        cursor.use {
            val idxId = it.getColumnIndex(Telephony.Sms._ID)
            val idxAddr = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val idxBody = it.getColumnIndex(Telephony.Sms.BODY)
            var newCount = 0
            while (it.moveToNext()) {
                val id = if (idxId >= 0) it.getLong(idxId) else -1L
                if (id <= lastSeenId) continue
                val sender = if (idxAddr >= 0) it.getString(idxAddr) ?: "" else ""
                val body = if (idxBody >= 0) it.getString(idxBody) ?: "" else ""
                lastSeenId = id
                saveLastSeenId(id)
                newCount++
                processMessage(sender, body)
            }
            if (newCount > 0) AppLog.i("MonitorService", "scanInbox: $newCount 条新短信已处理")
        }
    }

    private fun processMessage(sender: String, body: String) {
        // 统一交给 AlertDispatch：记录日志 + 命中规则时触发强提醒（含跨通道去重）
        AppLog.i("MonitorService", "processMessage from=$sender bodyLen=${body.length}")
        AlertDispatch.handle(this, sender, body)
    }

    private fun getLastSeenId(): Long {
        val prefs = getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_SEEN_ID, -1L)
    }

    private fun saveLastSeenId(id: Long) {
        val prefs = getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SEEN_ID, id).apply()
    }
}
