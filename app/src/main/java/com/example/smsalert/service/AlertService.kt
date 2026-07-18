package com.example.smsalert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.smsalert.Constants
import com.example.smsalert.R
import com.example.smsalert.data.SettingsRepository
import com.example.smsalert.ui.AlertActivity

/**
 * 强提醒播放引擎。
 *
 * 设计要点（对应“越强越好、静音/免打扰也能提醒、直到用户关闭”）：
 * 1) 使用闹钟流（STREAM_ALARM / USAGE_ALARM）播放循环铃声 —— 闹钟流通常不受“静音/媒体音量/勿扰(闹钟类)”影响；
 * 2) 启动时强制把闹钟音量拉到最大，并“取消闹钟流静音”（部分小米 ROM 会把闹钟流静音，只拉音量仍无声）；
 * 3) 每隔 repeatInterval 秒“重新拉满音量 + 取消静音 + 确保仍在播放 + 重新震动”，对抗系统重置；
 * 4) 强震动（长波形重复）；
 * 5) PARTIAL_WAKE_LOCK 保持 CPU，配合 AlertActivity 点亮屏幕；
 * 6) 最高优先级 + setBypassDnd + 全屏意图 的通知，锁屏上层弹出；
 * 7) 只有“关闭提醒”动作才会释放资源并停止自身 —— 不自动消失。
 *
 * 稳定性：onStartCommand 全程 try/catch，startForeground 必定最先调用（带兜底通知），
 * 重活移交 Handler 稍后执行，彻底规避 “startForegroundService 未在时限内调用 startForeground”
 * 导致的 RemoteServiceException 进程闪退；重复触发用 alerting 幂等标志防重复初始化。
 */
class AlertService : Service() {

    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(mainLooper)
    private lateinit var settings: SettingsRepository
    private var sender: String = ""
    private var body: String = ""
    private var alerting = false
    private var released = false

    companion object {
        private const val NOTIF_ID = 1001

        /** 入口：拉起前台播放服务 + 全屏 Activity */
        fun trigger(context: Context, sender: String, body: String) {
            val svc = Intent(context, AlertService::class.java).apply {
                putExtra(Constants.EXTRA_SENDER, sender)
                putExtra(Constants.EXTRA_BODY, body)
            }
            context.startForegroundService(svc)

            val act = Intent(context, AlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(Constants.EXTRA_SENDER, sender)
                putExtra(Constants.EXTRA_BODY, body)
            }
            context.startActivity(act)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == Constants.ACTION_STOP_ALERT) {
                stopAlert()
                return START_NOT_STICKY
            }
            sender = intent?.getStringExtra(Constants.EXTRA_SENDER) ?: ""
            body = intent?.getStringExtra(Constants.EXTRA_BODY) ?: ""

            // 1) 立即进入前台（必须在 startForegroundService 后限定时间内调用，否则系统直接杀进程 / 闪退）
            startForegroundSafely()

            // 2) 具体播放/震动等较重工作放到主线程消息队列稍后执行，确保 startForeground 先返回，
            //    彻底规避 “startForegroundService 未在时限内调用 startForeground” 的崩溃。
            //    用 alerting 幂等标志，避免对已运行的服务重复初始化导致异常。
            if (!alerting) {
                alerting = true
                handler.post { startAlert() }
            }
            return START_STICKY
        } catch (e: Throwable) {
            // 任何异常都不应拖垮整个进程（否则表现为“直接闪退”）
            try { stopAlert() } catch (_: Throwable) { }
            return START_NOT_STICKY
        }
    }

    private fun startForegroundSafely() {
        try {
            ensureChannel()
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Throwable) {
            // 极端情况下通知构建失败也要先占位，避免 RemoteServiceException
            try {
                val fallback = NotificationCompat.Builder(this, Constants.CHANNEL_ALERT_ID)
                    .setContentTitle("关键短信强提醒")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIF_ID, fallback)
            } catch (_: Throwable) { }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(Constants.CHANNEL_ALERT_ID) == null) {
                val ch = NotificationChannel(
                    Constants.CHANNEL_ALERT_ID,
                    "强提醒通知",
                    NotificationManager.IMPORTANCE_MAX
                ).apply {
                    description = "关键短信强提醒（最高优先级，可绕过勿扰）"
                    setBypassDnd(true)
                    // 声音由本服务用 Ringtone/MediaPlayer 自行播放，渠道本身不重复响铃
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun startAlert() {
        if (released) return
        try {
            // 1) 保持 CPU 唤醒（至多 10 分钟，期间会反复续期）
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlert::AlertWake").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        } catch (_: Throwable) { }

        // 2) 强制闹钟流：拉满音量 + 取消静音（小米常把闹钟流静音，只设音量仍无声）
        forceAlarmAudio()

        // 3) 播放铃声：优先用户自定义，否则内置报警音
        try {
            val userUri = settings.ringtoneUri
            val ok = if (!userUri.isNullOrBlank()) startRingtone(userUri) else false
            if (!ok) startBuiltIn()
        } catch (_: Throwable) {
            try { startBuiltIn() } catch (_: Throwable) { }
        }

        // 4) 强震动
        if (settings.vibrate) startVibration()

        // 5) 周期性“保活”：重新拉满音量/取消静音/确保播放/重新震动
        scheduleRepeat()
    }

    /** 强制闹钟流：拉满音量 + 取消静音（关键修复点） */
    private fun forceAlarmAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (settings.maxVolume) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 小米等 ROM 可能把闹钟流静音，仅设音量无效，必须显式取消静音
                    am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
        } catch (_: Throwable) { }
    }

    private val alarmAudioAttrs: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /** 用系统 Ringtone 播放（更稳，且自动走闹钟流） */
    private fun startRingtone(uriStr: String): Boolean {
        return try {
            stopSound()
            val uri = Uri.parse(uriStr)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = alarmAudioAttrs
                isLooping = true
                play()
            }
            ringtone != null
        } catch (e: Throwable) {
            false
        }
    }

    /** 内置报警音（res/raw/alarm.wav），Ringtone 不可用时回退 MediaPlayer */
    private fun startBuiltIn(): Boolean {
        return try {
            stopSound()
            val uri = Uri.parse("android.resource://$packageName/${R.raw.alarm}")
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = alarmAudioAttrs
                isLooping = true
                play()
            }
            if (ringtone != null) true else startBuiltInMediaPlayer()
        } catch (e: Throwable) {
            startBuiltInMediaPlayer()
        }
    }

    /** 内置报警音的 MediaPlayer 兜底（Ringtone 不可用时） */
    private fun startBuiltInMediaPlayer(): Boolean {
        return try {
            stopSound()
            resources.openRawResourceFd(R.raw.alarm)?.use { afd ->
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(alarmAudioAttrs)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    isLooping = true
                    prepare()
                    start()
                }
            }
            mediaPlayer != null
        } catch (e: Throwable) {
            false
        }
    }

    private fun stopSound() {
        try { ringtone?.stop() } catch (_: Throwable) { }
        ringtone = null
        try { mediaPlayer?.stop() } catch (_: Throwable) { }
        try { mediaPlayer?.release() } catch (_: Throwable) { }
        mediaPlayer = null
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (released || !alerting) return
            try {
                forceAlarmAudio()
                // 确保仍在响：若被系统打断则重新播放
                if (ringtone?.isPlaying != true) {
                    try { ringtone?.play() } catch (_: Throwable) {
                        try { startBuiltIn() } catch (_: Throwable) { }
                    }
                }
                if (settings.vibrate) startVibration()
            } catch (_: Throwable) { }
            handler.postDelayed(this, (settings.repeatIntervalSec * 1000).toLong())
        }
    }

    private fun scheduleRepeat() {
        handler.removeCallbacks(repeatRunnable)
        handler.postDelayed(repeatRunnable, (settings.repeatIntervalSec * 1000).toLong())
    }

    private fun startVibration() {
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }
        // 强烈、重复的长震动波形（毫秒）：响0.8s -> 停0.4s -> 响0.8s -> 停0.4s -> 响0.8s -> 循环
        val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
        vibrator?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, 0)
                }
            } catch (_: Throwable) { }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(Constants.EXTRA_SENDER, sender)
            putExtra(Constants.EXTRA_BODY, body)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 2001, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(this, AlertService::class.java).apply {
            action = Constants.ACTION_STOP_ALERT
        }
        val dismissPending = PendingIntent.getService(
            this, 2002, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.CHANNEL_ALERT_ID)
            .setContentTitle("\u26A0 关键短信强提醒")
            .setContentText("来自 $sender 的重要短信，请查看")
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭提醒", dismissPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    /** 释放所有资源（不含 stopSelf，避免与 onDestroy 互相递归） */
    private fun releaseResources() {
        if (released) return
        released = true
        alerting = false
        handler.removeCallbacks(repeatRunnable)
        stopSound()
        try { vibrator?.cancel() } catch (_: Throwable) { }
        vibrator = null
        if (wakeLock?.isHeld == true) try { wakeLock?.release() } catch (_: Throwable) { }
        wakeLock = null
    }

    /** 关闭提醒：唯一停止途径 */
    fun stopAlert() {
        releaseResources()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        try { stopSelf() } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
