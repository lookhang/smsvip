package com.example.smsalert.data

import android.content.Context
import android.media.RingtoneManager
import com.example.smsalert.Constants

/**
 * 设置仓库：铃声、重复间隔、最大音量、震动、闪屏等开关。
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(Constants.PREFS_SETTINGS, Context.MODE_PRIVATE)

    /** 铃声 Uri 字符串；为空时回退到系统默认闹钟声 */
    var ringtoneUri: String?
        get() = prefs.getString(Constants.KEY_RINGTONE_URI, null)
        set(v) = prefs.edit().putString(Constants.KEY_RINGTONE_URI, v).apply()

    /** 重复保活间隔（秒）：每隔该时间重新拉满音量/震动，抵抗系统重置 */
    var repeatIntervalSec: Int
        get() = prefs.getInt(Constants.KEY_REPEAT_INTERVAL, 15)
        set(v) = prefs.edit().putInt(Constants.KEY_REPEAT_INTERVAL, v.coerceAtLeast(5)).apply()

    /** 是否强制拉满闹钟音量（绕过静音/媒体音量） */
    var maxVolume: Boolean
        get() = prefs.getBoolean(Constants.KEY_MAX_VOLUME, true)
        set(v) = prefs.edit().putBoolean(Constants.KEY_MAX_VOLUME, v).apply()

    /** 是否震动 */
    var vibrate: Boolean
        get() = prefs.getBoolean(Constants.KEY_VIBRATE, true)
        set(v) = prefs.edit().putBoolean(Constants.KEY_VIBRATE, v).apply()

    /** 是否闪屏（在 AlertActivity 中通过背景闪烁实现） */
    var screenFlash: Boolean
        get() = prefs.getBoolean(Constants.KEY_SCREEN_FLASH, true)
        set(v) = prefs.edit().putBoolean(Constants.KEY_SCREEN_FLASH, v).apply()

    /** 默认铃声 Uri（优先闹钟声，回退到铃声） */
    fun defaultRingtoneUri(): String {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        return uri.toString()
    }
}
