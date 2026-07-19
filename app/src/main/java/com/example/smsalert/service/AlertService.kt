package com.example.smsalert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import androidx.core.app.NotificationCompat
import com.example.smsalert.Constants
import com.example.smsalert.R
import com.example.smsalert.data.SettingsRepository
import com.example.smsalert.ui.AlertActivity
import com.example.smsalert.util.AppLog

/**
 * 强提醒播放引擎。
 *
 * 设计要点：
 * 1) 声音采用“双重保险”：
 *    a) 通知渠道自带声音（setBypassDnd 渠道 + setSound）——由系统通知栈负责播放，
 *       这是穿透勿扰/静音最可靠的方式；
 *    b) 同时用 MediaPlayer 循环播放内置报警音（USAGE_ALARM + 最大音量 + 请求音频焦点），
 *       Ringtone 作为兜底，保证“持续响”的报警效果。
 * 2) 强制把闹钟音量拉到最大并取消闹钟流静音（小米常把闹钟流静音）；
 * 3) 周期保活：重新拉满音量/取消静音/确保仍在播放/重新震动；
 * 4) 强震动（长波形重复）；
 * 5) PARTIAL_WAKE_LOCK 保持 CPU，配合 AlertActivity 点亮屏幕；
 * 6) 只有“关闭提醒”动作才会释放资源并停止自身。
 *
 * 稳定性：onStartCommand 全程 try/catch，startForeground 必定最先调用（带兜底），
 * 重活移交 Handler 稍后执行，规避“startForegroundService 未在时限内调用 startForeground”
 * 导致的 RemoteServiceException 闪退；重复触发用 alerting 幂等标志防重复初始化。
 */
class AlertService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusReq: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsRepository
    private var sender: String = ""
    private var body: String = ""
    private var alerting = false
    private var released = false

    // 语音播报（女声念出关键词/号码）
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsEnabled = true
    private var ttsReadBody = false
    private var matchedValue: String = ""
    private var ruleType: String = ""
    private var pendingSpeak: String? = null

    companion object {
        private const val NOTIF_ID = 1001

        /** 入口：拉起前台播放服务 + 全屏 Activity */
        fun trigger(
            context: Context,
            sender: String,
            body: String,
            matchedValue: String = "",
            ruleType: String = ""
        ) {
            AppLog.i("AlertService", "trigger from=$sender bodyLen=${body.length} matched=$matchedValue type=$ruleType")
            val svc = Intent(context, AlertService::class.java).apply {
                putExtra(Constants.EXTRA_SENDER, sender)
                putExtra(Constants.EXTRA_BODY, body)
                putExtra(Constants.EXTRA_MATCHED_VALUE, matchedValue)
                putExtra(Constants.EXTRA_RULE_TYPE, ruleType)
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
        ttsEnabled = settings.ttsEnabled
        ttsReadBody = settings.ttsReadBody
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == Constants.ACTION_STOP_ALERT) {
                stopAlert()
                return START_NOT_STICKY
            }
            sender = intent?.getStringExtra(Constants.EXTRA_SENDER) ?: ""
            body = intent?.getStringExtra(Constants.EXTRA_BODY) ?: ""
            matchedValue = intent?.getStringExtra(Constants.EXTRA_MATCHED_VALUE) ?: ""
            ruleType = intent?.getStringExtra(Constants.EXTRA_RULE_TYPE) ?: ""
            AppLog.i("AlertService", "onStartCommand alerting=$alerting sender=$sender")

            // 1) 立即进入前台（必须在 startForegroundService 后限定时间内调用，否则系统直接杀进程 / 闪退）
            startForegroundSafely()

            // 2) 播放/震动等较重工作放到主线程消息队列稍后执行；用 alerting 幂等标志防重复初始化
            if (!alerting) {
                alerting = true
                handler.post { startAlert() }
            }
            return START_STICKY
        } catch (e: Throwable) {
            try { stopAlert() } catch (_: Throwable) { }
            return START_NOT_STICKY
        }
    }

    private fun startForegroundSafely() {
        try {
            ensureChannel()
            startForeground(NOTIF_ID, buildNotification())
            AppLog.i("AlertService", "startForeground ok")
        } catch (e: Throwable) {
            AppLog.e("AlertService", "startForeground failed, try fallback", e)
            try {
                val fallback = NotificationCompat.Builder(this, Constants.CHANNEL_ALERT_ID)
                    .setContentTitle("关键短信强提醒")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIF_ID, fallback)
            } catch (e2: Throwable) {
                AppLog.e("AlertService", "fallback startForeground also failed", e2)
                try { stopSelf() } catch (_: Throwable) { }
            }
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
                    // 渠道自带声音：由系统通知栈播放，是穿透勿扰/静音最可靠的方式
                    setSound(alarmSoundUri(), alarmAudioAttrs)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private val alarmAudioAttrs: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /** 当前选择的内置报警音 raw 资源 id */
    private fun alarmRawResId(): Int = when (settings.builtinAlarm) {
        Constants.ALARM_SIREN -> R.raw.siren
        Constants.ALARM_BEEP -> R.raw.beep
        Constants.ALARM_PULSE -> R.raw.pulse
        else -> R.raw.alarm
    }

    private fun alarmSoundUri(): Uri =
        Uri.parse("android.resource://$packageName/${alarmRawResId()}")

    private fun startAlert() {
        if (released) return
        AppLog.i("AlertService", "startAlert begin")
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlert::AlertWake").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Throwable) {
            AppLog.e("AlertService", "acquire wakeLock failed", e)
        }

        // 强制闹钟流：拉满音量 + 取消静音
        forceAlarmAudio()
        // 请求音频焦点，确保我们的报警声压过其他声音
        requestAlarmFocus()

        // 播放声音：MediaPlayer 主，Ringtone 兜底
        startSound()

        // 强震动
        if (settings.vibrate) startVibration()
        else AppLog.i("AlertService", "vibrate disabled by setting")

        // 语音播报：女声念出关键词/号码
        if (settings.ttsEnabled) speakAlert()

        // 周期保活
        scheduleRepeat()
        AppLog.i("AlertService", "startAlert done")
    }

    /** 强制闹钟流：拉满音量 + 取消静音（关键修复点） */
    private fun forceAlarmAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (settings.maxVolume) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
        } catch (_: Throwable) { }
    }

    private fun requestAlarmFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(alarmAudioAttrs)
                    .build()
                am.requestAudioFocus(audioFocusReq!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: Throwable) { }
    }

    /** 播放报警声：MediaPlayer（主）→ Ringtone（兜底） */
    private fun startSound() {
        val ok = startMediaPlayer()
        if (!ok) startRingtoneFallback()
    }

    /** 主播放：MediaPlayer 循环播放内置报警音，走闹钟流、最大音量 */
    private fun startMediaPlayer(): Boolean {
        return try {
            stopSound()
            val afd = resources.openRawResourceFd(alarmRawResId())
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(alarmAudioAttrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            afd.close()
            AppLog.i("AlertService", "MediaPlayer started")
            true
        } catch (e: Throwable) {
            AppLog.e("AlertService", "MediaPlayer failed, will fallback to Ringtone", e)
            false
        }
    }

    /** 兜底：Ringtone 播放（用户自定义或内置） */
    private fun startRingtoneFallback(): Boolean {
        return try {
            stopSound()
            val userUri = settings.ringtoneUri
            val uri = if (!userUri.isNullOrBlank()) Uri.parse(userUri) else alarmSoundUri()
            AppLog.i("AlertService", "Ringtone fallback uri=$uri")
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = alarmAudioAttrs
                isLooping = true
                play()
            }
            if (ringtone != null) AppLog.i("AlertService", "Ringtone started") else AppLog.w("AlertService", "Ringtone null (getRingtone returned null)")
            ringtone != null
        } catch (e: Throwable) {
            AppLog.e("AlertService", "Ringtone fallback also failed", e)
            false
        }
    }

    private fun stopSound() {
        try { mediaPlayer?.stop() } catch (_: Throwable) { }
        try { mediaPlayer?.release() } catch (_: Throwable) { }
        mediaPlayer = null
        try { ringtone?.stop() } catch (_: Throwable) { }
        ringtone = null
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (released || !alerting) return
            try {
                forceAlarmAudio()
                // 确保仍在响：若被系统打断则重新播放
                val playing = mediaPlayer?.isPlaying == true || ringtone?.isPlaying == true
                if (!playing) startSound()
                if (settings.vibrate) startVibration()
                if (settings.ttsEnabled) speakAlert()
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

    /** 初始化 TTS 引擎（异步）；初始化完成后再播放待播文本 */
    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val t = tts ?: return@TextToSpeech
                    try { t.language = Locale.SIMPLIFIED_CHINESE } catch (_: Throwable) { }
                    pickFemaleChineseVoice(t)
                    try {
                        t.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    } catch (_: Throwable) { }
                    ttsReady = true
                    // 初始化期间若已有待播文本（alert 已先触发），补播
                    pendingSpeak?.let { speakNow(it); pendingSpeak = null }
                    AppLog.i("AlertService", "TTS ready")
                } else {
                    AppLog.e("AlertService", "TTS init failed status=$status", null)
                }
            }
        } catch (e: Throwable) {
            AppLog.e("AlertService", "TTS construct failed", e)
        }
    }

    /** 在引擎提供的声音中优先挑“中文女声”；找不到则以高音模拟 */
    private fun pickFemaleChineseVoice(t: TextToSpeech) {
        try {
            val voices: Set<Voice> = t.voices ?: return
            val zh = voices.filter {
                val l = it.locale
                l.language == "zh" || l.language == "cmn" || l.country == "CN"
            }
            val pool = if (zh.isNotEmpty()) zh else voices
            val female = pool.firstOrNull { v ->
                val n = v.name.lowercase()
                n.contains("female") || n.contains("女") || n.contains("xiaoxiao") ||
                        n.contains("xiaoyi") || n.contains("yaoyao") || n.contains("ting") ||
                        n.contains("huihui") || n.contains("mei") || n.contains("yue") ||
                        n.contains("yating") || n.contains("yuna")
            }
            if (female != null) {
                t.voice = female
                AppLog.i("AlertService", "TTS female voice=${female.name}")
            } else {
                t.setPitch(1.35f) // 无女声可用时提高音调解近女声
                AppLog.i("AlertService", "TTS no female voice, use pitch=1.35")
            }
            t.setSpeechRate(0.95f)
        } catch (e: Throwable) {
            AppLog.e("AlertService", "pickFemaleChineseVoice failed", e)
        }
    }

    /** 组合播报文本并发声；TTS 未就绪则暂存，待初始化完成后补播 */
    private fun speakAlert() {
        if (!ttsEnabled) return
        val text = buildSpeakText()
        if (text.isBlank()) return
        if (ttsReady) speakNow(text) else pendingSpeak = text
    }

    private fun buildSpeakText(): String {
        val sb = StringBuilder()
        when (ruleType) {
            "KEYWORD" -> sb.append("收到含关键词 ${safe(matchedValue)} 的短信")
            "SENDER" -> sb.append("收到来自号码 ${safe(matchedValue)} 的短信")
            "TEST" -> sb.append("这是一条测试提醒")
            else -> sb.append("收到一条关键短信")
        }
        if (ttsReadBody && body.isNotBlank()) {
            sb.append("。内容：${body.take(120)}")
        }
        return sb.toString()
    }

    private fun safe(s: String): String = if (s.isBlank()) "未知" else s

    private fun speakNow(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert_tts")
        } catch (e: Throwable) {
            AppLog.e("AlertService", "tts.speak failed", e)
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

    private fun releaseResources() {
        if (released) return
        released = true
        alerting = false
        handler.removeCallbacks(repeatRunnable)
        stopSound()
        try { tts?.stop() } catch (_: Throwable) { }
        try { tts?.shutdown() } catch (_: Throwable) { }
        tts = null
        ttsReady = false
        pendingSpeak = null
        try { vibrator?.cancel() } catch (_: Throwable) { }
        vibrator = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusReq != null) {
                am.abandonAudioFocusRequest(audioFocusReq!!)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) { }
        audioFocusReq = null
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
