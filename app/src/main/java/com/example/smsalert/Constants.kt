package com.example.smsalert

/**
 * 全局常量集中管理，便于各组件统一引用。
 */
object Constants {
    // 通知渠道
    const val CHANNEL_ALERT_ID = "sms_alert_channel"
    const val CHANNEL_MONITOR_ID = "sms_monitor_channel"

    // 强提醒服务动作
    const val ACTION_STOP_ALERT = "com.example.smsalert.action.STOP_ALERT"

    // 意图附加数据
    const val EXTRA_SENDER = "extra_sender"
    const val EXTRA_BODY = "extra_body"
    const val EXTRA_MATCHED_VALUE = "extra_matched_value"
    const val EXTRA_RULE_TYPE = "extra_rule_type"

    // SharedPreferences 文件名
    const val PREFS_RULES = "rules_prefs"
    const val PREFS_SETTINGS = "settings_prefs"

    // 规则存储键
    const val KEY_RULES_JSON = "rules_json"

    // 设置存储键
    const val KEY_RINGTONE_URI = "ringtone_uri"
    const val KEY_REPEAT_INTERVAL = "repeat_interval"
    const val KEY_MAX_VOLUME = "max_volume"
    const val KEY_VIBRATE = "vibrate"
    const val KEY_SCREEN_FLASH = "screen_flash"
    const val KEY_TTS_ENABLED = "tts_enabled"
    const val KEY_TTS_READ_BODY = "tts_read_body"
}
