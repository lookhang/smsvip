package com.example.smsalert.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
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
 * 1) 使用 STREAM_ALARM 播放循环铃声 —— 闹钟流通常不受“静音/媒体音量/勿扰(闹钟类)”影响；
 * 2) 启动时强制把闹钟音量拉到最大（可配置）；
 * 3) 每隔 repeatInterval 秒“重新拉满音量 + 重新震动 + 确保仍在播放”，对抗系统重置；
 * 4) 强震动（长波形重复）；
 * 5) PARTIAL_WAKE_LOCK 保持 CPU，配合 AlertActivity 点亮屏幕；
 * 6) 最高优先级 + setBypassDnd + 全屏意图 的通知，锁屏上层弹出；
 * 7) 只有“关闭提醒”动作才会释放资源并停止自身 —— 不自动消失。
 */
class AlertService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(mainLooper)
    private lateinit var settings: SettingsRepository
    private var sender: String = ""
    private var body: String = ""
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
        if (intent?.action == Constants.ACTION_STOP_ALERT) {
            stopAlert()
            return START_NOT_STICKY
        }
        sender = intent?.getStringExtra(Constants.EXTRA_SENDER) ?: ""
        body = intent?.getStringExtra(Constants.EXTRA_BODY) ?: ""

        startForeground(NOTIF_ID, buildNotification())
        startAlert()
        return START_STICKY
    }

    private fun startAlert() {
        // 1) 保持 CPU 唤醒（至多 10 分钟，期间会反复续期）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlert::AlertWake").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }

        // 2) 强制闹钟音量到最大（绕过静音/媒体音量）
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (settings.maxVolume) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        }

        // 3) 准备并循环播放铃声：优先用户自定义铃声，失败则用内置报警音
        //    （内置 res/raw/alarm.wav 不依赖系统默认铃声，避免部分小米设备无默认闹钟声导致 NPE/无声音）
        val userUri = settings.ringtoneUri
        val ok = if (!userUri.isNullOrBlank()) startPlayer(userUri) else false
        if (!ok) startPlayerBuiltIn()

        // 4) 强震动
        if (settings.vibrate) startVibration()

        // 5) 周期性“保活”：重新拉满音量、确保播放、重新震动
        scheduleRepeat()
    }

    private fun startPlayer(uriStr: String): Boolean {
        return try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlertService, Uri.parse(uriStr))
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 用内置报警音（res/raw/alarm.wav）播放，走 STREAM_ALARM 以绕过静音/勿扰。
     * 不依赖系统默认铃声，彻底规避部分小米设备无默认闹钟声导致的 NPE/无声音。
     */
    private fun startPlayerBuiltIn(): Boolean {
        return try {
            mediaPlayer?.release()
            resources.openRawResourceFd(R.raw.alarm)?.use { afd ->
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                    isLooping = true
                    prepare()
                    start()
                }
            }
            mediaPlayer != null
        } catch (e: Exception) {
            false
        }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (released) return
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (settings.maxVolume) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0
                    )
                }
                if (mediaPlayer?.isPlaying != true) mediaPlayer?.start()
                if (settings.vibrate) startVibration()
            } catch (_: Exception) {
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 0)
            }
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
        handler.removeCallbacks(repeatRunnable)
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        mediaPlayer?.release()
        mediaPlayer = null
        try { vibrator?.cancel() } catch (_: Exception) { }
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    /** 关闭提醒：唯一停止途径 */
    fun stopAlert() {
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
